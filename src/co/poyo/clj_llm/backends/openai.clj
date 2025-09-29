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

(defn- convert-options-for-api
  "Convert kebab-case options to underscore format for OpenAI API"
  [opts]
  (into {}
        (map (fn [[k v]]
               [(-> k name helpers/kebab->underscore keyword) v])
             opts)))

(defn- build-body
  "Build OpenAI API request body"
  [messages schema provider-opts]
  (let [schema-config (when schema
                        {:tools [(co.poyo.clj-llm.schema/malli->json-schema schema)]
                         :tool_choice "required"})
        api-opts (convert-options-for-api provider-opts)
        ;; Extract model from provider opts, default if not present
        model (:model provider-opts "gpt-4o-mini")]
    (merge {:model model
            :stream true
            :stream_options {:include_usage true}
            :messages messages}
           schema-config
           (dissoc api-opts :model))))

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
  [chunk schema]
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
          (and schema has-args?)
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
  [api-base api-key messages schema provider-opts]
  (let [events-chan (chan 1024)
        url (str api-base "/chat/completions")
        headers {"Authorization" (str "Bearer " api-key)
                 "Content-Type" "application/json"}
        body (json/generate-string (build-body messages schema provider-opts))]

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
                 (doseq [event (chunk->events chunk schema)]
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

(defrecord OpenAIBackend [api-base api-key defaults timeout-ms]
  proto/LLMProvider
  (request-stream [_ messages provider-opts]
    ;; Extract schema from the calling context via core.clj
    ;; Schema is handled in core.clj, but we need it here for OpenAI tools
    ;; For now, we'll look for it in a special key
    (let [schema (:__schema provider-opts)
          clean-opts (dissoc provider-opts :__schema)]
      (create-event-stream api-base api-key messages schema clean-opts))))

(defn ->openai
  "Create an OpenAI provider with optional defaults.
   
   config map:
   :api-key - Required OpenAI API key (or use :api-key-env)
   :api-key-env - Environment variable name for API key (default: OPENAI_API_KEY)
   :api-base - API base URL (default: https://api.openai.com/v1)
   :defaults - Optional default options (same shape as prompt opts)"
  [{:keys [api-key api-key-env api-base defaults] :as config}]
  (let [resolved-key (or api-key
                         (when-let [env-var (or api-key-env "OPENAI_API_KEY")]
                           (System/getenv env-var)))
        resolved-base (or api-base "https://api.openai.com/v1")]

    ;; Validate API key
    (when-not resolved-key
      (throw (errors/error "Missing API key"
                           {:provider "openai"
                            :api-key-env (or api-key-env "OPENAI_API_KEY")
                            :config config})))

    ;; Validate defaults if provided
    (when defaults
      ;; Note: We'd use PromptOpts here but it's not imported
      ;; For now just validate it's a map
      (when-not (map? defaults)
        (throw (errors/error "Defaults must be a map" {:defaults defaults}))))

    (->OpenAIBackend resolved-base
                     resolved-key
                     defaults
                     60000))) ; default timeout

;; Legacy backend function for compatibility
(defn backend
  "Create an OpenAI backend instance. DEPRECATED - use ->openai instead."
  ([] (->openai {}))
  ([config] (->openai config)))

;; Make backend print nicely in REPL
(defmethod print-method OpenAIBackend [backend writer]
  (.write writer
          (format "#OpenAI[%s%s]"
                  (:api-base backend)
                  (if (:defaults backend)
                    (format ", defaults: %s" (pr-str (:defaults backend)))
                    ""))))
