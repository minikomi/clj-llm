(ns co.poyo.clj-llm.core
  (:require
   [co.poyo.clj-llm.protocol :as proto]
   [co.poyo.clj-llm.errors :as errors]
   [co.poyo.clj-llm.helpers :as helpers]
   [cheshire.core :as json]
   [clojure.core.async :as a :refer [go-loop <! <!! chan]]
   [malli.core :as m]
   [malli.error :as me]
   [malli.transform :as mt]))

;; ════════════════════════════════════════════════════════════════════
;; Known option keys (everything else is rejected)
;; ════════════════════════════════════════════════════════════════════

(def ^:private known-keys
  #{:model :system-prompt :schema :tools :tool-choice
    :timeout-ms :message-history :provider-opts})

(defn- validate-opts [opts]
  (let [unknown (remove known-keys (keys opts))]
    (when (seq unknown)
      (throw (errors/error
              (str "Unknown options: " (pr-str (vec unknown)))
              {:unknown-keys (vec unknown)
               :valid-keys known-keys}))))
  opts)

;; ════════════════════════════════════════════════════════════════════
;; Response record
;; ════════════════════════════════════════════════════════════════════

(defrecord Response [chunks events text usage structured tool-calls]
  clojure.lang.IDeref
  (deref [_]
    (let [v @text]
      (if (instance? Exception v) (throw v) v))))

(defmethod print-method Response [r writer]
  (.write writer "#Response{:text ")
  (if (realized? (:text r))
    (.write writer (pr-str @(:text r)))
    (.write writer "<pending>"))
  (.write writer "}"))

;; ════════════════════════════════════════════════════════════════════
;; Provider defaults
;; ════════════════════════════════════════════════════════════════════

(defn with-defaults
  "Set call-time defaults on a provider (model, system-prompt, schema, etc.).
   These are merged with per-call opts, with per-call taking precedence.

   (-> (openai/->openai)
       (llm/with-defaults {:model \"gpt-4o-mini\"
                           :system-prompt \"you are a cat\"
                           :schema cat-schema}))"
  [provider defaults]
  (validate-opts defaults)
  (update provider :defaults #(helpers/deep-merge % defaults)))

;; ════════════════════════════════════════════════════════════════════
;; Internal helpers
;; ════════════════════════════════════════════════════════════════════

(defn- parse-structured-output
  "Parse JSON text and validate against a malli schema."
  [text schema]
  (try
    (let [parsed (json/parse-string text true)
          result (m/decode schema parsed mt/json-transformer)]
      (if (m/validate schema result)
        result
        (throw (errors/error
                "Schema validation failed"
                {:schema schema
                 :value result
                 :errors (me/humanize (m/explain schema result))}))))
    (catch clojure.lang.ExceptionInfo e (throw e))
    (catch Exception e
      (throw (errors/error
              "Failed to parse structured output"
              {:schema schema :input text})))))

(defn- build-messages
  "Build messages vector from prompt string and optional history."
  [prompt-input message-history]
  (cond-> (vec (or message-history []))
    prompt-input (conj {:role :user :content prompt-input})))

;; ════════════════════════════════════════════════════════════════════
;; Event stream consumer
;; ════════════════════════════════════════════════════════════════════

(defn- consume-events
  "Consume events from source-chan, fan out to chunks/events channels,
   and deliver promises when the stream completes."
  [source-chan {:keys [text-chunks-chan events-chan
                       text-promise usage-promise
                       structured-promise tool-calls-promise
                       schema provider-opts req-start]}]
  (let [finalize! (fn [text-val tool-calls-val]
                    (deliver text-promise text-val)
                    (deliver tool-calls-promise tool-calls-val)
                    (when-not (realized? usage-promise)
                      (deliver usage-promise nil))
                    (a/close! text-chunks-chan)
                    (a/close! events-chan))]

    ;; Consumer go-loop
    (go-loop [chunks []
              tool-calls []
              tc-index {}] ;; maps provider index -> position in tool-calls vec
      (if-let [event (<! source-chan)]
        (do
          (a/offer! events-chan event)
          (case (:type event)
            :content
            (do (a/offer! text-chunks-chan (:content event))
                (recur (conj chunks (:content event)) tool-calls tc-index))

            :tool-call
            (let [idx (or (:index event) (count tool-calls))
                  call (assoc event :arguments (or (:arguments event) ""))]
              (recur chunks
                     (conj tool-calls call)
                     (assoc tc-index idx (count tool-calls))))

            :tool-call-delta
            (let [pos (get tc-index (:index event))]
              (recur chunks
                     (if pos
                       (update-in tool-calls [pos :arguments] str (:arguments event))
                       tool-calls)
                     tc-index))

            :usage
            (do (deliver usage-promise
                        (assoc event
                               :clj-llm/provider-opts provider-opts
                               :clj-llm/req-start req-start
                               :clj-llm/req-end (System/currentTimeMillis)
                               :clj-llm/duration (- (System/currentTimeMillis) req-start)))
                (recur chunks tool-calls tc-index))

            :error
            (do (when-not (realized? structured-promise)
                  (deliver structured-promise (Exception. (str (:error event)))))
                (finalize! (errors/error "LLM request failed" {:event event}) nil))

            :done
            (finalize! (apply str chunks) (not-empty tool-calls))

            ;; Unknown event type — skip
            (recur chunks tool-calls tc-index)))

        ;; Source channel closed without :done
        (finalize! (apply str chunks) (not-empty tool-calls))))

    ;; Structured output processing (waits for text promise in a separate thread)
    (when schema
      (future
        (try
          (let [text @text-promise]
            (deliver structured-promise
                     (if (instance? Exception text)
                       text
                       (parse-structured-output text schema))))
          (catch Exception e
            (deliver structured-promise e)))))))

;; ════════════════════════════════════════════════════════════════════
;; Core API
;; ════════════════════════════════════════════════════════════════════

(defn prompt
  "Send a prompt to the LLM provider. Returns a Response record with:
    :text        - promise of full text string
    :chunks      - channel of text chunks as they stream
    :events      - channel of raw events
    :usage       - promise of token usage info
    :structured  - promise of parsed structured data (when :schema provided)
    :tool-calls  - promise of tool calls vector (when :tools provided)

   Supports @(prompt ...) via IDeref to block and get text.

   Options (plain keywords):
    :model           - model name string
    :system-prompt   - system prompt string
    :schema          - malli schema for structured output
    :tools           - vector of malli tool schemas
    :tool-choice     - tool choice strategy
    :timeout-ms      - request timeout
    :message-history - conversation history vector
    :provider-opts   - map passed through to the provider API"
  ([provider prompt-input]
   (prompt provider prompt-input {}))
  ([provider prompt-input opts]
   (let [merged (validate-opts (helpers/deep-merge (:defaults provider) opts))
         {:keys [model system-prompt schema tools tool-choice
                 message-history provider-opts]} merged

         _ (when-not model
             (throw (errors/error "No model specified" {:opts merged})))

         messages      (build-messages prompt-input message-history)
         source-chan    (proto/request-stream provider model system-prompt messages
                                             schema tools tool-choice
                                             (or provider-opts {}))
         text-chunks   (chan (a/dropping-buffer 1024))
         events        (chan (a/dropping-buffer 1024))
         text-p        (promise)
         usage-p       (promise)
         structured-p  (promise)
         tool-calls-p  (promise)]

     (consume-events source-chan
                     {:text-chunks-chan text-chunks
                      :events-chan      events
                      :text-promise     text-p
                      :usage-promise    usage-p
                      :structured-promise structured-p
                      :tool-calls-promise tool-calls-p
                      :schema          schema
                      :provider-opts   provider-opts
                      :req-start       (System/currentTimeMillis)})

     (->Response text-chunks events text-p usage-p structured-p tool-calls-p))))

(defn- parse-tool-calls
  "Parse JSON argument strings in tool calls."
  [raw-tool-calls]
  (when raw-tool-calls
    (mapv (fn [t]
            (let [args (try (json/parse-string (:arguments t) true)
                           (catch Exception _ (:arguments t)))]
              {:id (:id t) :name (:name t) :arguments args}))
          raw-tool-calls)))

(defn- tool-calls->assistant-message
  "Build the assistant message for tool call history round-tripping."
  [tool-calls]
  {:role :assistant
   :tool_calls (mapv (fn [{:keys [id name arguments]}]
                      {:id id :type "function"
                       :function {:name name
                                  :arguments (if (string? arguments)
                                               arguments
                                               (json/generate-string arguments))}})
                    tool-calls)})

(defn generate
  "Blocking generation. Always returns a map:

   (generate ai \"hello\")
   ;; => {:text \"Hello!\"}

   (generate ai \"extract\" {:schema person-schema})
   ;; => {:text \"{...}\" :structured {:name \"Alice\"}}

   (generate ai \"weather\" {:tools [weather-tool]})
   ;; => {:text \"\" :tool-calls [{:id \"...\" :name \"get_weather\" :arguments {:city \"Tokyo\"}}]
   ;;      :message {:role :assistant :tool_calls [...]}}

   :text       - always present
   :structured - present when :schema provided
   :tool-calls - present when tools were called
   :message    - assistant message formatted for history (present with tool calls)
   :usage      - token usage (when provider reports it)"
  ([provider prompt-input]
   (generate provider prompt-input {}))
  ([provider prompt-input opts]
   (let [response (prompt provider prompt-input opts)
         merged   (helpers/deep-merge (:defaults provider) opts)
         text     @(:text response)
         _        (when (instance? Exception text) (throw text))
         usage    (when (realized? (:usage response)) @(:usage response))
         result   {:text text}]
     (cond-> result
       usage
       (assoc :usage usage)

       (:schema merged)
       (assoc :structured
              (let [s @(:structured response)]
                (if (instance? Exception s) (throw s) s)))

       (:tools merged)
       ((fn [r]
          (let [tc (parse-tool-calls @(:tool-calls response))]
            (cond-> r
              tc (assoc :tool-calls tc
                       :message (tool-calls->assistant-message tc))))))))))

(defn tool-result
  "Create a tool result message for feeding back into message history.

   (tool-result \"call_abc\" \"Sunny, 22°C\")
   ;; => {:role :tool :tool_call_id \"call_abc\" :content \"Sunny, 22°C\"}"
  [tool-call-id content]
  {:role :tool :tool_call_id tool-call-id :content (str content)})

(defn stream
  "Returns a channel of text chunks as they stream from the LLM."
  ([provider prompt-input]
   (stream provider prompt-input {}))
  ([provider prompt-input opts]
   (:chunks (prompt provider prompt-input opts))))

(defn stream-print
  "Stream text to *out*, printing chunks as they arrive. Returns {:text ...}.
   Great for REPL use.

   (stream-print ai \"Tell me a story\")"
  ([provider prompt-input]
   (stream-print provider prompt-input {}))
  ([provider prompt-input opts]
   (let [ch (stream provider prompt-input opts)
         sb (StringBuilder.)]
     (loop []
       (when-let [chunk (<!! ch)]
         (.append sb chunk)
         (print chunk)
         (flush)
         (recur)))
     (println)
     {:text (.toString sb)})))

(defn events
  "Returns a channel of raw events from the LLM."
  ([provider prompt-input]
   (events provider prompt-input {}))
  ([provider prompt-input opts]
   (:events (prompt provider prompt-input opts))))
