(ns co.poyo.clj-llm.backends.openai
  "OpenAI and OpenAI-compatible API provider implementation.
   
   Supports OpenAI, OpenRouter, Together.ai, and any OpenAI-compatible endpoint."
  (:require [cheshire.core :as json]
            [clojure.core.async :as a :refer [chan go go-loop <! >! close!]]
            [clojure.string :as str]
            [co.poyo.clj-llm.net :as net]
            [co.poyo.clj-llm.sse :as sse]
            [co.poyo.clj-llm.schema :as schema]
            [co.poyo.clj-llm.protocol :as proto]
            [co.poyo.clj-llm.errors :as errors]
            [co.poyo.clj-llm.helpers :as helpers]))

(def ^:private default-config
  {:api-key-env "OPENAI_API_KEY"
   :api-base "https://api.openai.com/v1"})

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
    (let [{:keys [error usage choices]} parsed
          {:keys [delta]} (first choices)
          {:keys [content tool_calls]} delta]
      (cond
        error
        [{:type :error :error error}]

        content
        [{:type :content :content content}]

        (seq tool_calls)
        (let [{{:keys [arguments]} :function} (first tool_calls)]
          (when (and schema arguments)
            [{:type :content :content arguments}]))

        usage
        [{:type :usage
          :prompt-tokens (:prompt_tokens usage)
          :completion-tokens (:completion_tokens usage)
          :total-tokens (:total_tokens usage)}]))))

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
    (net/post-stream url headers body
                     (fn handle-response [response]
                       (if (= 200 (:status response))
                         (let [sse-chan (sse/parse-sse (:body response))]
                           (go
                             (try
                               (loop []
                                 (when-let [chunk (<! sse-chan)]
                                   (doseq [event (chunk->events chunk schema)]
                                     (when event
                                       (>! events-chan event)))
                                   (recur)))
                               (>! events-chan {:type :done})
                               (catch Exception e
                                 (>! events-chan {:type :error :error e}))
                               (finally
                                 (close! events-chan)))))
                         (go
                           (>! events-chan (handle-error-response response))
                           (close! events-chan)))))
    events-chan))

(defrecord OpenAIBackend [api-base api-key defaults]
  proto/LLMProvider
  (request-stream [_ messages schema provider-opts]
    (let [final-opts (helpers/deep-merge defaults provider-opts)]
      (create-event-stream api-base api-key messages schema final-opts))))

(defn ->openai
  ([] (->openai default-config))
  ([{:keys [api-key api-env-var api-base defaults]}]
   (let [resolved-api-key (or api-env-var (:api-key-env default-config))
         resolved-key (or api-key (System/getenv resolved-api-key))
         resolved-base (or api-base (:api-base default-config))]

     (when-not resolved-key
       (throw (errors/error "Missing API key"
                            {:provider "openai" :api-key-env resolved-api-key})))

     (->OpenAIBackend resolved-base resolved-key defaults))))

;; Make backend print nicely in REPL
(defmethod print-method OpenAIBackend [backend writer]
  (.write writer
          (format "#OpenAI[%s%s]"
                  (:api-base backend)
                  (if (:defaults backend)
                    (format ", defaults: %s" (pr-str (:defaults backend)))
                    ""))))
