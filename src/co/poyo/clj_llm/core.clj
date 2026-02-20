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
  (deref [_] @text))

(defmethod print-method Response [r writer]
  (.write writer "#Response{:text ")
  (if (realized? (:text r))
    (.write writer (pr-str @(:text r)))
    (.write writer "<pending>"))
  (.write writer "}"))

;; ════════════════════════════════════════════════════════════════════
;; Provider builder helpers
;; ════════════════════════════════════════════════════════════════════

(defn with-defaults      [provider defaults]      (update provider :defaults #(helpers/deep-merge % defaults)))
(defn with-model         [provider model]         (assoc-in provider [:defaults :model] model))
(defn with-schema        [provider schema]        (assoc-in provider [:defaults :schema] schema))
(defn with-system-prompt [provider system-prompt]  (assoc-in provider [:defaults :system-prompt] system-prompt))
(defn with-timeout       [provider timeout-ms]     (assoc-in provider [:defaults :timeout-ms] timeout-ms))
(defn with-message-history [provider history]      (assoc-in provider [:defaults :message-history] history))
(defn with-provider-opts [provider opts]           (assoc-in provider [:defaults :provider-opts] opts))
(defn merge-provider-opts [provider opts]          (update-in provider [:defaults :provider-opts] merge opts))
(defn with-tools         [provider tools]          (assoc-in provider [:defaults :tools] tools))
(defn with-tool-choice   [provider tool-choice]    (assoc-in provider [:defaults :tool-choice] tool-choice))

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

(defn generate
  "Simple blocking generation. Returns text string, or structured data when
   :schema is provided.

   (generate ai \"hello\")                     ;; => \"Hello!\" 
   (generate ai \"extract\" {:schema my-schema}) ;; => {:name \"Alice\"}"
  ([provider prompt-input]
   (generate provider prompt-input {}))
  ([provider prompt-input opts]
   (let [response (prompt provider prompt-input opts)
         result   (if (:schema (helpers/deep-merge (:defaults provider) opts))
                    @(:structured response)
                    @(:text response))]
     (if (instance? Exception result)
       (throw result)
       result))))

(defn stream
  "Returns a channel of text chunks as they stream from the LLM."
  ([provider prompt-input]
   (stream provider prompt-input {}))
  ([provider prompt-input opts]
   (:chunks (prompt provider prompt-input opts))))

(defn events
  "Returns a channel of raw events from the LLM."
  ([provider prompt-input]
   (events provider prompt-input {}))
  ([provider prompt-input opts]
   (:events (prompt provider prompt-input opts))))
