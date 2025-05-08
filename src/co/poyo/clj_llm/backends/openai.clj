(ns co.poyo.clj-llm.backends.openai
  (:require [co.poyo.clj-llm.protocol :as proto]
            [co.poyo.clj-llm.stream :refer [process-sse extract-sse-data]]
            [co.poyo.clj-llm.registry :as reg]
            [clojure.core.async :as async :refer [chan go <! >! close!]]
            [clojure.string :as str]
            [org.httpkit.client :as http]
            [cheshire.core :as json]
            [malli.core :as m]))

;; ──────────────────────────────────────────────────────────────
;; OpenAI specific option schema
;; ──────────────────────────────────────────────────────────────
(def openai-opts-schema
  [:map
   [:response-format   {:optional true} [:enum "text" "json"]]
   [:tools             {:optional true} [:sequential map?]]
   [:tool-choice       {:optional true} [:or keyword? map?]]
   [:functions         {:optional true} [:sequential map?]]
   [:function-call     {:optional true} [:or string? map?]]
   [:stream-options    {:optional true} [:map
                                         [:include_usage {:optional true} boolean?]]]])

;; ──────────────────────────────────────────────────────────────
;; SSE Stream Processing
;; ──────────────────────────────────────────────────────────────


(def openai-transformer
  "Transform OpenAI SSE events"
  (fn [event]
    (when event
      (cond
        (:done event) {:type :done}
        (:error event) {:type :error, :message (:error event)}
        :else
        (let [choices (get event :choices [])
              first-choice (first choices)
              delta (get first-choice :delta {})
              content (get delta :content "")
              tool-calls (get delta :tool_calls)
              finish-reason (get first-choice :finish-reason)
              usage (get event :usage)]
          (cond
            ;; Tool calls take priority
            tool-calls
            {:type :tool-call, :tool-calls tool-calls, :raw-event event}

            (not (str/blank? content))
            {:type :content, :content content}

            finish-reason
            {:type :finish, :reason finish-reason}

            usage
            {:type :usage, :usage usage}

            :else
            {:type :other, :data event}))))))

(defn process-openai-stream
  "Process an OpenAI SSE stream with a channel for chunks and a metadata atom"
  [input-stream metadata-atom]
  (let [chunks-chan (chan)
        tool-calls-by-index (atom {})]

    ;; Process the stream
    (process-sse input-stream
                 {:extract-fn extract-sse-data
                  :transform-fn openai-transformer
                  :on-content (fn [result]
                                (case (:type result)
                                  :content
                                  (async/>!! chunks-chan (:content result))

                                  :tool-call
                                  (let [raw-event (:raw-event result)
                                        tool-calls (get-in raw-event [:choices 0 :delta :tool_calls])]

                                    ;; Store the raw event for metadata
                                    (swap! metadata-atom update :tool-calls-events
                                           (fnil conj []) raw-event)

                                    ;; Update the tool-calls-by-index atom for each tool call fragment
                                    (doseq [tool-call tool-calls]
                                      (let [idx (or (:index tool-call) 0)]
                                        (swap! tool-calls-by-index update idx
                                               (fnil conj []) tool-call))))

                                  :usage
                                  (swap! metadata-atom assoc :usage (:usage result))

                                  :finish
                                  (swap! metadata-atom assoc :finish-reason (:reason result))

                                  :other
                                  (swap! metadata-atom update :raw-events conj (:data result))

                                  nil))

                  :on-error (fn [error-msg]
                              (swap! metadata-atom assoc :error error-msg)
                              (close! chunks-chan))

                  :on-done (fn []
                             ;; Now that we're done, add the consolidated tool calls to the text
                             (let [normalized-tool-calls (normalize-tool-calls
                                                           (:tool-calls-events @metadata-atom))]

                               ;; Add the tool calls as JSON to the text channel
                               (when (seq normalized-tool-calls)
                                 (let [tool-calls-json (json/generate-string
                                                         {:tool_calls normalized-tool-calls}
                                                         {:pretty true})]
                                   (async/>!! chunks-chan tool-calls-json))))

                             ;; Close the channel when done
                             (close! chunks-chan))})
    chunks-chan))

(defn normalize-tool-calls
  "Convert raw tool call events into a normalized EDN format"
  [events]
  (when (seq events)
    (let [;; Extract all tool call deltas and group by index
          by-index (reduce
                    (fn [acc event]
                      (let [tool-calls (get-in event [:choices 0 :delta :tool_calls] [])]
                        (if (seq tool-calls)
                          (reduce
                           (fn [a tool-call]
                             (let [idx (or (:index tool-call) 0)]
                               (update a idx (fnil conj []) tool-call)))
                           acc
                           tool-calls)
                          acc)))
                    {}
                    events)

          ;; Merge fragments for each tool call
          merged-tool-calls (map
                             (fn [[idx fragments]]
                               (reduce
                                (fn [acc fragment]
                                  (cond-> acc
                                    (:id fragment) (assoc :id (:id fragment))
                                    (:type fragment) (assoc :type (:type fragment))
                                    (:index fragment) (assoc :index (:index fragment))
                                    (get-in fragment [:function :name])
                                    (assoc-in [:function :name] (get-in fragment [:function :name]))
                                    (get-in fragment [:function :arguments])
                                    (update-in [:function :arguments] str (get-in fragment [:function :arguments]))))
                                {}
                                fragments))
                             by-index)]

      ;; Parse arguments JSON strings to EDN
      (mapv (fn [tool-call]
              (if-let [args-str (get-in tool-call [:function :arguments])]
                (try
                  ;; Parse the JSON arguments string
                  (let [cleaned-args (-> args-str
                                         (str/replace #"^(\{\"lo)" "{\"lo")
                                         (str/replace #"(\"}$)" "\"}"))]
                    (update-in tool-call [:function :arguments]
                               (fn [_] (json/parse-string cleaned-args true))))
                  (catch Exception _
                    ;; If parsing fails, keep the original string
                    tool-call))
                tool-call))
            merged-tool-calls))))

;; ──────────────────────────────────────────────────────────────
;; OpenAI API Requests
;; ──────────────────────────────────────────────────────────────
(defn- make-messages
  "Convert prompt string to messages format"
  [prompt-str]
  (if (string? prompt-str)
    [{:role "user" :content prompt-str}]
    prompt-str))

(defn- get-api-key [opts]
  (or (:api-key opts)
      (System/getenv "OPENAI_API_KEY")
      (throw (ex-info "No OpenAI API key provided" {:missing-key :api-key}))))

(defn- build-request-body
  "Build OpenAI chat completion request body"
  [model-id prompt-str opts]
  (let [messages (make-messages prompt-str)
        stream-options (get opts :stream-options {:include_usage true})]
    (cond-> {:model model-id
             :messages messages
             :stream true
             :stream_options stream-options}

      (:temperature opts)       (assoc :temperature (:temperature opts))
      (:top-p opts)             (assoc :top_p (:top-p opts))
      (:max-tokens opts)        (assoc :max_tokens (:max-tokens opts))
      (:frequency-penalty opts) (assoc :frequency_penalty (:frequency-penalty opts))
      (:presence-penalty opts)  (assoc :presence_penalty (:presence-penalty opts))
      (:response-format opts)   (assoc :response_format (:response-format opts))
      (:seed opts)              (assoc :seed (:seed opts))
      (:tools opts)             (assoc :tools (:tools opts))
      (:tool-choice opts)       (assoc :tool_choice (:tool-choice opts))
      (:functions opts)         (assoc :functions (:functions opts))
      (:function-call opts)     (assoc :function_call (:function-call opts))
      (:logit-bias opts)        (assoc :logit_bias (:logit-bias opts))
      (:stop opts)              (assoc :stop [(:stop opts)]))))

(defn- make-openai-request
  "Make a streaming request to OpenAI API"
  [model-id prompt-str opts]
  (let [api-key (get-api-key opts)
        request-body (build-request-body model-id prompt-str opts)
        metadata-atom (atom {:raw-events []})
        {:keys [status body error]} @(http/request
                                      {:method :post
                                       :url "https://api.openai.com/v1/chat/completions"
                                       :headers {"Content-Type" "application/json"
                                                "Authorization" (str "Bearer " api-key)}
                                       :body (json/generate-string request-body)
                                       :as :stream})]

    ;; Handle HTTP errors
    (when (or error (not= status 200))
      (throw (ex-info "OpenAI API request failed"
                     {:status status :error error :model-id model-id})))

    ;; Return the streaming channel and metadata atom
    {:channel (process-openai-stream body metadata-atom)
     :metadata metadata-atom}))

;; ──────────────────────────────────────────────────────────────
;; OpenAI Backend Implementation
;; ──────────────────────────────────────────────────────────────
(defrecord OpenAIBackend []
  proto/LLMBackend

  (-prompt [this model-id prompt-str opts]
    (let [{:keys [channel metadata]} (proto/-stream this model-id prompt-str opts)]
      (loop [result ""]
        (if-let [chunk (async/<!! channel)]
          (recur (str result chunk))
          result))))

  (-stream [this model-id prompt-str opts]
    (make-openai-request model-id prompt-str opts))

  (-opts-schema [this model-id]
    openai-opts-schema)

  (-get-usage [this model-id metadata-atom]
    (:usage @metadata-atom))

  (-get-tool-calls [this model-id metadata-atom]
  (let [{:keys [tool-calls-events]} @metadata-atom]
    (normalize-tool-calls tool-calls-events)))

  (-get-raw-json [this model-id metadata-atom]
  (let [{:keys [raw-events usage finish-reason tool-calls-events error]} @metadata-atom
        tool-calls (normalize-tool-calls tool-calls-events)]
    (cond-> {}
      (seq raw-events) (assoc :events raw-events)
      usage (assoc :usage usage)
      finish-reason (assoc :finish_reason finish-reason)
      (seq tool-calls) (assoc :tool_calls tool-calls)
      error (assoc :error error)))))

;; ──────────────────────────────────────────────────────────────
;; Factory function
;; ──────────────────────────────────────────────────────────────
(defn create-backend []
  (->OpenAIBackend))


(comment
  (reg/register-backend! :openai (create-backend))
  (require '[co.poyo.clj-llm.core :as llm])

  (doseq [chunk (:chunks (llm/prompt :openai/gpt-4.1-nano "Explain quantum computing in simple terms, 10 words or less"))]
    (println "Chunk:" chunk)
    (flush))

  @(:json (llm/prompt :openai/gpt-4.1-nano "Explain quantum computing in simple terms, 10 words or less"))

  (defn try-tool-call []
    (let [tools [{:type "function"
                  :function {:name "get_weather"
                             :description "Get the current weather in a location"
                             :parameters {:type "object"
                                          :properties {"location" {:type "string"
                                                                   :description "The city and state, e.g. San Francisco, CA"}}
                                          :required ["location"]}}}]

          ;; Make the API call with tool definitions
          response (llm/prompt :openai/gpt-4.1-nano
                               "What's the weather like in Tokyo right now?"
                               :tools tools
                               :tool-choice "auto")


          ;; Get the full response text
          text @(:text response)

          ;; Get the raw JSON to check for tool calls
          json @(:json response)]

      ;; Print the results
      (println "Response text:" text)
      (println "Tool calls found:" (count (get-in json [:events 0 :choices 0 :delta :tool_calls] [])))

      ;; Return the full response for inspection
      response))

  (def a (try-tool-call))

  (println @(:text a))

  @(:text a)


)
