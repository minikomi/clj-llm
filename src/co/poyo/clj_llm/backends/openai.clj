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
            [co.poyo.clj-llm.errors :as errors]))

;; ──────────────────────────────────────────────────────────────
;; Configuration
;; ──────────────────────────────────────────────────────────────

(def ^:private openai-models
  "Set of available OpenAI models"
  #{"gpt-4o" "gpt-4o-mini" "gpt-4-turbo" "gpt-4" "gpt-3.5-turbo"
    "o1-preview" "o1-mini" "o3-mini"})

(def ^:private default-config
  {:api-key-env "OPENAI_API_KEY"
   :api-base "https://api.openai.com/v1"
   :default-model "gpt-4o-mini"
   :timeout-ms 60000})

;; ──────────────────────────────────────────────────────────────
;; Request Building
;; ──────────────────────────────────────────────────────────────

(defn- format-message
  "Format a single message for OpenAI API"
  [{:keys [role content] :as msg}]
  {:role (name role)
   :content content})
(defn- make-messages
  "Create messages array with optional system prompt"
  [prompt system-prompt]
  (cond-> []
    system-prompt (conj {:role "system" :content system-prompt})
    prompt (conj {:role "user" :content prompt})))

(defn- build-body
  "Build OpenAI API request body"
  [model prompt {:keys [schema system-prompt] :as opts}]
  (let [messages (make-messages prompt system-prompt)
        tools (when schema {:tools [(co.poyo.clj-llm.schema/malli->json-schema schema)]
                            :tool_choice "required"})
        ;; Parameter mapping
        param-map {:temperature     :temperature
                   :top-p           :top_p
                   :max-tokens      :max_tokens
                   :frequency-penalty :frequency_penalty
                   :presence-penalty  :presence_penalty
                   :response-format   :response_format
                   :seed              :seed}]
    (cond-> {:model model
             :stream true
             :stream_options {:include_usage true}
             :messages messages}
      tools (merge tools)
      (:stop opts) (assoc :stop (let [s (:stop opts)]
                                  (if (string? s) [s] s)))
      ;; Apply parameter mappings
      true (merge (into {} (for [[k v] param-map
                                 :when (contains? opts k)]
                             [v (get opts k)]))))))


;; ──────────────────────────────────────────────────────────────
;; Response Parsing  
;; ──────────────────────────────────────────────────────────────

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
  [chunk]
  (when-let [parsed (parse-chunk chunk)]
    (cond
      ;; Error in chunk
      (:error parsed)
      [{:type :error :error (:error parsed)}]

      ;; Content chunk
      (get-in parsed [:choices 0 :delta :content])
      [{:type :content :content (get-in parsed [:choices 0 :delta :content])}]

      ;; Tool call chunk
      (get-in parsed [:choices 0 :delta :tool_calls])
      (let [tool-calls (get-in parsed [:choices 0 :delta :tool_calls])
            tool-call (first tool-calls)
            function-args (get-in tool-call [:function :arguments])]
        (when function-args
          [{:type :content :content function-args}]))

      ;; Usage information (comes at the end for some providers)
      (:usage parsed)
      [{:type :usage
        :prompt-tokens (get-in parsed [:usage :prompt_tokens])
        :completion-tokens (get-in parsed [:usage :completion_tokens])
        :total-tokens (get-in parsed [:usage :total_tokens])}]

      ;; Finish reason
      (get-in parsed [:choices 0 :finish_reason])
      [] ;; We'll send :done when stream closes

      :else [])))

;; ──────────────────────────────────────────────────────────────
;; HTTP & Streaming
;; ──────────────────────────────────────────────────────────────

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
          ;; Create proper error based on status and response
          error (errors/parse-http-error "openai" status body {:url (:url response)})]
      {:type :error
       :error (.getMessage error)
       :status status
       :provider-error body
       :exception error})
    (catch Exception e
      ;; If we can't parse the response, create a generic error
      {:type :error
       :error (str "HTTP " (:status response) ": " (:body response))
       :status (:status response)
       :exception (errors/network-error
                   (str "Failed to parse error response: " (.getMessage e))
                   {:response response}
                   :cause e)})))

(defn- create-event-stream
  "Create event stream from HTTP response"
  [api-base api-key model messages opts]
  (let [events-chan (chan 1024)
        url (str api-base "/chat/completions")
        headers {"Authorization" (str "Bearer " api-key)
                 "Content-Type" "application/json"}
        ;; Convert messages back to prompt for build-body
        prompt (when (= 1 (count messages)) (:content (first messages)))
        body (json/generate-string (build-body model prompt opts))]

    ;; Make streaming request
    (net/post-stream
     url
     headers
     body
     (fn [response]
       (if (= 200 (:status response))
         ;; Success - set up SSE streaming
         (let [sse-chan (sse/parse-sse (:body response))]
           (go-loop []
             (if-let [chunk (<! sse-chan)]
               (do
                 ;; Convert chunk to events
                 (doseq [event (chunk->events chunk)]
                   (>! events-chan event))
                 (recur))
               ;; Stream ended
               (do
                 (>! events-chan {:type :done})
                 (close! events-chan)))))
         ;; Error response
         (do
           (>! events-chan (handle-error-response response))
           (close! events-chan)))))

    events-chan))

;; ──────────────────────────────────────────────────────────────
;; Backend Implementation
;; ──────────────────────────────────────────────────────────────

(defrecord OpenAIBackend [api-base api-key default-model timeout-ms]
  proto/LLMProvider
  (request-stream [this model messages opts]
    (create-event-stream api-base api-key model messages opts)))

;; ──────────────────────────────────────────────────────────────
;; Public API
;; ──────────────────────────────────────────────────────────────

(defn backend
  "Create an OpenAI backend instance.
   
   Args:
     config - Map with:
       :api-key      - OpenAI API key (required)
       :api-key-env  - Environment variable name for API key
       :api-base     - API base URL (default: https://api.openai.com/v1)
       :default-model - Default model to use (default: gpt-4o-mini)
       :timeout-ms   - Request timeout in milliseconds
       
   Returns:
     Backend instance for use with generate/stream/prompt functions
     
   Example:
     (def openai (backend {:api-key-env \"OPENAI_API_KEY\"}))"
  ([] (backend default-config))
  ([{:keys [api-key api-key-env] :as config}]
   (let [resolved-key (or api-key
                          (when api-key-env
                            (System/getenv api-key-env)))
         final-config (merge default-config config)]

     ;; Validate API key
     (when-not resolved-key
       (throw (errors/invalid-api-key "openai")))

     ;; Create backend
     (map->OpenAIBackend
      (assoc final-config :api-key resolved-key)))))

;; ──────────────────────────────────────────────────────────────
;; Convenience Constructors
;; ──────────────────────────────────────────────────────────────

(defn openrouter
  "Create an OpenRouter backend instance.
   
   OpenRouter provides access to many models through a unified API.
   
   Args:
     config - Same as backend, but uses OpenRouter defaults
     
   Example:
     (def router (openrouter {:api-key-env \"OPENROUTER_API_KEY\"}))"
  [config]
  (backend (merge {:api-base "https://openrouter.ai/api/v1"
                   :default-model "openai/gpt-4o-mini"}
                  config)))

(defn together
  "Create a Together.ai backend instance.
   
   Together provides fast inference for open source models.
   
   Args:
     config - Same as backend, but uses Together defaults
     
   Example:
     (def tog (together {:api-key-env \"TOGETHER_API_KEY\"}))"
  [config]
  (backend (merge {:api-base "https://api.together.xyz/v1"
                   :default-model "meta-llama/Llama-3-70b-chat-hf"}
                  config)))

(defn local
  "Create a backend for local OpenAI-compatible servers.
   
   Works with LM Studio, Ollama, llama.cpp server, etc.
   
   Args:
     config - Same as backend, but for local servers
     
   Example:
     (def local-llm (local {:api-base \"http://localhost:8080\"
                           :default-model \"llama2\"}))"
  [config]
  (backend (merge {:api-base "http://localhost:8080"
                   :api-key "not-needed" ;; Most local servers don't need auth
                   :default-model "llama2"}
                  config)))
