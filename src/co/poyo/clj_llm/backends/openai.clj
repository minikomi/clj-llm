(ns co.poyo.clj-llm.backends.openai
  "OpenAI and OpenAI-compatible API provider implementation.
   
   Supports OpenAI, OpenRouter, Together.ai, and any OpenAI-compatible endpoint."
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
  {:api-key-env "OPENAI_API_KEY"
   :api-base "https://api.openai.com/v1"})

(defn- convert-options-for-api
  "Convert kebab-case options to snake_case format for OpenAI API"
  [opts]
  (when opts
    (walk/postwalk
     (fn [x]
       (if (map? x)
         (update-keys x csk/->snake_case_keyword)
         x))
     opts)))

(defn- build-body
  "Build OpenAI API request body"
  [messages schema opts]
  (let [schema-config (when schema
                        {:tools [(co.poyo.clj-llm.schema/malli->json-schema schema)]
                         :tool_choice "required"})
        api-opts (convert-options-for-api opts)]
    (merge
     {:stream true
      :stream_options {:include_usage true}
      :messages messages}
     api-opts
     schema-config)))

(defn- data->internal-event
  "Convert OpenAI chunk to our event format"
  [data schema]
  (cond
    (:error data)
    [{:type :error :error (:error data)}]

    (get-in data [:choices 0 :delta :content])
    [{:type :content :content (get-in data [:choices 0 :delta :content])}]

    (get-in data [:choices 0 :delta :tool-calls])
    (let [tool-calls (get-in data [:choices 0 :delta :tool-calls])
          tool-call (first tool-calls)
          has-args? (get-in tool-call [:function :arguments])]
      (cond
        (and schema has-args?)
        [{:type :content :content (get-in tool-call [:function :arguments])}]
        :else nil))

    (get-in data [:choices 0 :finish-reason])
    [{:type :done :reason (get-in data [:choices 0 :finish-reason])}]

    (:usage data)
    [{:type :usage
      :prompt-tokens (get-in data [:usage :prompt-tokens])
      :completion-tokens (get-in data [:usage :completion-tokens])
      :total-tokens (get-in data [:usage :total-tokens])
      :prompt-tokens-details (get-in data [:usage :prompt-tokens-details])
      :completion-tokens-details (get-in data [:usage :completion-tokens-details])
      :model (:model data)
      :id (:id data)
      :created (:created data)
      :service-tier (:service-tier data)
      :system-fingerprint (:system-fingerprint data)}]

    :else (prn "failed" data)))

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
          error (errors/parse-http-error "openai" status body)]
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
  [api-base api-key messages schema opts]
  (let [events-chan (chan 1024)
        url (str api-base "/chat/completions")
        headers {"Authorization" (str "Bearer " api-key)
                 "Content-Type" "application/json"}
        body (json/generate-string (build-body messages schema opts))]
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

                                     ;; SSE parsing or stream error
                                     (::sse/error chunk)
                                     (do
                                       (>! events-chan {:type :error :error (::sse/error chunk)})
                                       (recur))

                                     ;; Unparsed JSON - skip
                                     (get-in chunk [::sse/data ::sse/unparsed])
                                     (recur)

                                     ;; Valid data chunk - process events
                                     :else
                                     (do
                                       (doseq [internal-event (data->internal-event (::sse/data chunk) schema)]
                                         (when internal-event
                                           (>! events-chan internal-event)))
                                       (recur)))))
                               (catch Exception e
                                 (>! events-chan {:type :error :error e}))
                               (finally
                                 (close! events-chan)))))
                         (go
                           (>! events-chan (handle-error-response response))
                           (close! events-chan)))))
    events-chan))

;; ==========================================
;; OpenAI Backend Implementation
;; ==========================================

(defrecord OpenAIBackend [api-base api-key defaults]
  proto/LLMProvider
  (request-stream [_ messages schema provider-opts]
    (create-event-stream api-base api-key messages schema provider-opts)))

(defn ->openai
  ([] (->openai {}))
  ([config]
   (let [;; Extract API config
         api-key (::api-key config)
         api-env-var (::api-env-var config)
         api-base (::api-base config)

         ;; Extract prompt defaults (everything except ::openai/ keys)
         defaults (into {}
                        (remove (fn [[k _]]
                                  (#{::api-key ::api-env-var ::api-base} k))
                                config))

         ;; Resolve API config
         resolved-api-env (or api-env-var (:api-key-env default-config))
         resolved-key (or api-key (System/getenv resolved-api-env))
         resolved-base (or api-base (:api-base default-config))]

     (when-not resolved-key
       (throw (errors/error "Missing API key"
                            {:provider "openai" :api-key-env resolved-api-env})))

     (->OpenAIBackend resolved-base resolved-key defaults))))

(defmethod print-method OpenAIBackend [backend writer]
  (let [defaults (:defaults backend)
        model (or (:co.poyo.clj-llm.core/model defaults)
                  (:model (:co.poyo.clj-llm.core/provider-opts defaults)))]
    (.write writer "#OpenAI")
    (when model
      (.write writer (str " " (pr-str model))))
    (.write writer (str " " (pr-str (:api-base backend))))))
