(ns co.poyo.clj-llm.backends.anthropic
  "Anthropic API provider implementation"
  (:require
   [camel-snake-kebab.core :as csk]
   [cheshire.core :as json]
   [clojure.core.async :as a :refer [chan go go-loop <! >! close!]]
   [clojure.string :as str]
   [co.poyo.clj-llm.net :as net]
   [co.poyo.clj-llm.sse :as sse]
   [co.poyo.clj-llm.schema :as schema]
   [co.poyo.clj-llm.protocol :as proto]
   [co.poyo.clj-llm.errors :as errors]
   [co.poyo.clj-llm.helpers :as helpers]
   [clojure.walk :as walk]))

(def ^:private default-config
  {:api-key-env "ANTHROPIC_API_KEY"
   :api-base "https://api.anthropic.com"
   :api-version "2023-06-01"})

(defn- convert-options-for-api
  "Convert kebab-case options to snake_case format for Anthropic API"
  [opts]
  (when opts
    (walk/postwalk
     (fn [x]
       (if (map? x)
         (update-keys x csk/->snake_case_keyword)
         x))
     opts)))

(defn- build-body
  "Build Anthropic API request body"
  [model system-prompt messages schema opts]
  (let [schema-config (when schema
                        {:tools [(co.poyo.clj-llm.schema/malli->json-schema schema)]
                         :tool_choice {:type "any"}})
        api-opts (convert-options-for-api opts)
        ;; Anthropic requires max_tokens
        max-tokens (or (:max_tokens api-opts) 4096)
        base-body (merge
                   {:model model
                    :max_tokens max-tokens
                    :messages messages
                    :stream true}
                   api-opts
                   schema-config)]
    ;; Add system prompt as top-level parameter if provided
    (if system-prompt
      (assoc base-body :system system-prompt)
      base-body)))

(defn- data->internal-event
  "Convert Anthropic SSE event to our internal event format"
  [data schema]
  (case (:type data)
    ;; Content delta - extract text
    "content_block_delta"
    {:type :content :content (get-in data [:delta :text])}

    ;; Message delta with usage info
    "message_delta"
    (when-let [usage (:usage data)]
      (into {:type :usage} usage))

    ;; Message stop - end of stream
    "message_stop"
    {:type :done}

    ;; Ignore lifecycle and ping events
    ("message_start" "content_block_start" "content_block_stop" "ping")
    nil

    ;; Error events
    "error"
    {:type :error :error (:error data)}

    ;; Unknown event type
    nil))

(defn- handle-error-response
  "Parse error response from API and create appropriate error event"
  [response]
  (try
    (let [body-str (cond
                     (string? (:body response)) (:body response)
                     (instance? java.io.InputStream (:body response)) (slurp (:body response))
                     :else (str (:body response)))
          body (try
                 (json/parse-string body-str true)
                 (catch Exception _
                   ;; If JSON parsing fails, use the raw string
                   body-str))
          status (:status response)
          error (errors/parse-http-error "anthropic" status body)]
      {:type :error
       :error (.getMessage error)
       :status status
       :provider-error body
       :exception error})
    (catch Exception e
      {:type :error
       :error (str "HTTP " (:status response) ": " (:body response))
       :status (:status response)
       :exception (errors/error
                   (str "Failed to parse error response: " (.getMessage e))
                   {:response response})})))

(defn- create-event-stream
  "Create event stream from HTTP response"
  [api-base api-key api-version model system-prompt messages schema opts]
  (let [events-chan (chan 1024)
        url (str api-base "/v1/messages")
        headers {"x-api-key" api-key
                 "anthropic-version" api-version
                 "Content-Type" "application/json"}
        body-map (build-body model system-prompt messages schema opts)
        body (json/generate-string body-map)]
    (net/post-stream url headers body
                     (fn handle-response [response]
                       (if (= 200 (:status response))
                         (let [sse-chan (sse/parse-sse (:body response))]
                           (go
                             (try
                               (loop []
                                 (when-let [chunk (<! sse-chan)]
                                   (cond
                                     ;; Done signal from SSE
                                     (::sse/done chunk)
                                     (>! events-chan {:type :done})

                                     ;; SSE parsing error
                                     (::sse/error chunk)
                                     (do
                                       (>! events-chan {:type :error :error (::sse/error chunk)})
                                       (recur))

                                     ;; Unparsed JSON - skip
                                     (get-in chunk [::sse/data ::sse/unparsed])
                                     (recur)

                                     ;; Valid event - process it
                                     :else
                                     (let [internal-event (data->internal-event
                                                           (::sse/data chunk)
                                                           schema)]
                                       (if internal-event
                                         (do
                                           (>! events-chan internal-event)
                                           ;; Stop after :done event
                                           (when (not= :done (:type internal-event))
                                             (recur)))
                                         ;; nil event (lifecycle) - continue processing
                                         (recur))))))
                               (catch Exception e
                                 (>! events-chan {:type :error :error e}))
                               (finally
                                 (close! events-chan)))))
                         (go
                           (>! events-chan (handle-error-response response))
                           (close! events-chan)))))
    events-chan))

;; ==========================================
;; Anthropic Backend Implementation
;; ==========================================

(defrecord AnthropicBackend [api-base api-key api-version defaults]
  proto/LLMProvider
  (request-stream [_ model system-prompt messages schema provider-opts]
    (create-event-stream api-base api-key api-version model system-prompt messages schema provider-opts)))

(defn ->anthropic
  ([] (->anthropic {}))
  ([config]
   (let [;; Extract API config
         api-key (::api-key config)
         api-env-var (::api-env-var config)
         api-base (::api-base config)
         api-version (::api-version config)

         ;; Extract prompt defaults (everything except ::anthropic/ keys)
         defaults (into {}
                        (remove (fn [[k _]]
                                  (#{::api-key ::api-env-var ::api-base ::api-version} k))
                                config))

         ;; Resolve API config
         resolved-api-env (or api-env-var (:api-key-env default-config))
         resolved-key (or api-key
                          (System/getenv resolved-api-env)
                          (System/getProperty resolved-api-env))
         resolved-base (or api-base (:api-base default-config))
         resolved-version (or api-version (:api-version default-config))]

     (when-not resolved-key
       (throw (errors/error "Missing API key"
                            {:provider "anthropic" :api-key-env resolved-api-env})))

     (->AnthropicBackend resolved-base resolved-key resolved-version defaults))))

(defmethod print-method AnthropicBackend [backend writer]
  (let [defaults (:defaults backend)
        model (:co.poyo.clj-llm.core/model defaults)]
    (.write writer "#Anthropic")
    (when model
      (.write writer (str " " (pr-str model))))
    (.write writer (str " " (pr-str (:api-base backend))))))
