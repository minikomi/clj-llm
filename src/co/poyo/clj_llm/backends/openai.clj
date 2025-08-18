(ns co.poyo.clj-llm.backends.openai
  "OpenAI and OpenAI-compatible API provider implementation.
   
   Supports OpenAI, OpenRouter, Together.ai, and any OpenAI-compatible endpoint."
  (:require [cheshire.core :as json]
            [clojure.core.async :as a :refer [chan go-loop <! >! close!]]
            [clojure.string :as str]
            [co.poyo.clj-llm.net :as net]
            [co.poyo.clj-llm.sse :as sse]
            [co.poyo.clj-llm.schema :as schema]
            [co.poyo.clj-llm.protocol :as proto]
            [co.poyo.clj-llm.errors :as errors]
            [co.poyo.clj-llm.helpers :as helpers]))

(def ^:private default-config
  {:api-key-env "OPENAI_API_KEY"
   :api-base "https://api.openai.com/v1"
   :default-model "gpt-5-mini"
   :timeout-ms 60000})

(def api-opts-keys [:temperature
                    :top-p
                    :max-tokens
                    :frequency-penalty
                    :presence-penalty
                    :response-format
                    :seed
                    :stop])

(defn- convert-options-for-api
  "Convert kebab-case options to underscore format for OpenAI API"
  [opts]
  (into {}
        (map (fn [[k v]]
               [(helpers/kebab->underscore k) v])
             (select-keys opts api-opts-keys))))

(defn- build-body
  "Build OpenAI API request body"
  [model messages {:keys [schema system-prompt] :as opts}]
  (let [final-messages (mapv (fn [{:keys [role content]} {:role (name role) :content content}]) messages)
        schema-config (when schema
                        {:tools [(co.poyo.clj-llm.schema/malli->json-schema schema)]
                         :tool_choice "required"})
        api-opts (convert-options-for-api opts)]
    (merge {:model model
            :stream true
            :stream_options {:include_usage true}
            :messages final-messages}
           schema-config
           api-opts)))

(defn- parse-chunk
  "Parse a single SSE chunk from OpenAI stream"
  [chunk]
  (when-let [data (get chunk "data")]
    (when (not= data "[DONE]")
      (try
        (json/parse-string data true)
        (catch Exception e
          {:error (str "Failed to parse chunk: " (.getMessage e))})))))

(defn- chunk->events
  "Convert OpenAI chunk to our event format"
  [chunk opts]
  (when-let [parsed (parse-chunk chunk)]
    (cond
      (:error parsed)
      [{:type :error :error (:error parsed)}]

      (get-in parsed [:choices 0 :delta :content])
      [{:type :content :content (get-in parsed [:choices 0 :delta :content])}]

      (get-in parsed [:choices 0 :delta :tool_calls])
      (let [tool-calls (get-in parsed [:choices 0 :delta :tool_calls])
            tool-call (first tool-calls)
            has-args? (get-in tool-call [:function :arguments])]
        (cond
          (and (:schema opts) has-args?)
          [{:type :content :content (get-in tool-call [:function :arguments])}]

          :else nil))

      (:usage parsed)
      [{:type :usage
        :prompt-tokens (get-in parsed [:usage :prompt_tokens])
        :completion-tokens (get-in parsed [:usage :completion_tokens])
        :total-tokens (get-in parsed [:usage :total_tokens])}]

      :else nil)))

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
  [api-base api-key model messages opts]
  (let [events-chan (chan 1024)
        url (str api-base "/chat/completions")
        headers {"Authorization" (str "Bearer " api-key)
                 "Content-Type" "application/json"}
        body (json/generate-string (build-body model messages opts))]

    (net/post-stream
     url
     headers
     body
     (fn [response]
       (if (= 200 (:status response))
         (let [sse-chan (sse/parse-sse (:body response))]
           (go-loop []
             (if-let [chunk (<! sse-chan)]
               (do
                 (doseq [event (chunk->events chunk opts)]
                   (when event
                     (>! events-chan event)))
                 (recur))
               (do
                 (>! events-chan {:type :done})
                 (close! events-chan)))))
         (do
           (a/put! events-chan (handle-error-response response))
           (close! events-chan)))))

    events-chan))

(defrecord OpenAIBackend [api-base api-key default-model default-opts timeout-ms]
  proto/LLMProvider
  (request-stream [_ model messages opts]
    (create-event-stream api-base api-key model messages opts)))

(defn backend
  "Create an OpenAI backend instance."
  ([]
   (backend {}))

  ([config-or-model]
   (cond
     ;; String model shorthand
     (string? config-or-model)
     (backend {:backend {:default-options {:model config-or-model}}})

     ;; Empty map - use all defaults
     (and (map? config-or-model) (empty? config-or-model))
     (backend {:backend {}})

     ;; Check if it's a full config with :backend key
     (contains? config-or-model :backend)
     (let [{:keys [backend defaults]} config-or-model
           ;; Merge with defaults FIRST
           backend-config (merge default-config (or backend {}))
           resolved-key (or (:api-key backend-config)
                            (when-let [env (:api-key-env backend-config)]
                              (System/getenv env)))]

       ;; Validate API key
       (when-not resolved-key
         (throw (errors/error "Missing API key"
                              {:provider "openai"
                               :api-key-env (:api-key-env backend-config)})))

       ;; Create backend record with defaults
       (->OpenAIBackend (:api-base backend-config)
                        resolved-key
                        (:default-model backend-config)
                        defaults
                        (:timeout-ms backend-config)))

     ;; Shorthand config - wrap in :backend
     :else
     (backend {:backend config-or-model}))))

;; Make backend print nicely in REPL
(defmethod print-method OpenAIBackend [backend writer]
  (.write writer
          (format "#OpenAI[model: %s, timeout: %dms%s]"
                  (:default-model backend)
                  (:timeout-ms backend)
                  (if (:default-opts backend)
                    (format ", defaults: %s" (pr-str (:default-opts backend)))
                    ""))))
