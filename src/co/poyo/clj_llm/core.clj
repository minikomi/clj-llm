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
    :timeout-ms :provider-opts})

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
;; Provider defaults — just data on the :defaults key.
;;
;;   (def ai (assoc (openai/->openai) :defaults {:model "gpt-4o-mini"}))
;;   (def extractor (update ai :defaults merge {:schema person-schema}))
;;
;; generate merges :defaults with per-call opts (per-call wins).
;; ════════════════════════════════════════════════════════════════════

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
  "Coerce input to a messages vector.
   String  → [{:role :user :content input}]
   Vector  → used as-is (message history)
   nil     → []"
  [input]
  (cond
    (string? input) [{:role :user :content input}]
    (vector? input) input
    (nil? input)    []
    :else (throw (errors/error
                  (str "Input must be a string, vector, or nil — got " (type input))
                  {:input input}))))

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

   Input is the last arg — a string (prompt) or vector (message history).

   (prompt ai \"hello\")                              ; simple
   (prompt ai {:schema s} \"extract this\")            ; with opts
   (prompt ai {:tools t} [{:role :user ...} ...])    ; with history"
  ([provider input]
   (prompt provider {} input))
  ([provider opts input]
   (let [merged (validate-opts (helpers/deep-merge (:defaults provider) opts))
         {:keys [model system-prompt schema tools tool-choice
                 provider-opts]} merged

         _ (when-not model
             (throw (errors/error "No model specified" {:opts merged})))

         messages      (build-messages input)
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
  "Blocking generation. Returns the natural value:

   (generate ai \"hello\")
   ;; => \"Hello!\"

   (generate ai {:schema person-schema} \"extract this\")
   ;; => {:name \"Alice\" :age 30}

   (generate ai {:tools [weather-tool]} \"weather in Tokyo\")
   ;; => [{:id \"...\" :name \"get_weather\" :arguments {:city \"Tokyo\"}}]
   ;; (meta result) => {:message {:role :assistant :tool_calls [...]}}

   Input is last — string or message-history vector. Threads with ->>:

   (->> \"raw text\"
        (llm/generate ai {:system-prompt \"Fix grammar\"})
        (llm/generate ai {:system-prompt \"Translate to French\"}))

   For streaming/usage, use `prompt` directly."
  ([provider input]
   (generate provider {} input))
  ([provider opts input]
   (let [response (prompt provider opts input)
         merged   (helpers/deep-merge (:defaults provider) opts)
         text     @(:text response)
         _        (when (instance? Exception text) (throw text))]
     (cond
       ;; Tools mode: return tool-calls vector with :message in meta, or text if no calls
       (:tools merged)
       (let [tc (parse-tool-calls @(:tool-calls response))]
         (if (seq tc)
           (with-meta tc {:message (tool-calls->assistant-message tc)})
           text))

       ;; Schema mode: return structured data directly
       (:schema merged)
       (let [s @(:structured response)]
         (if (instance? Exception s) (throw s) s))

       ;; Text mode: return string
       :else text))))

(defn tool-result
  "Create a tool result message for feeding back into message history.

   (tool-result \"call_abc\" \"Sunny, 22°C\")
   ;; => {:role :tool :tool_call_id \"call_abc\" :content \"Sunny, 22°C\"}"
  [tool-call-id content]
  {:role :tool :tool_call_id tool-call-id :content (str content)})

(defn run-agent
  "Run an agentic tool-calling loop. Calls the LLM, executes any requested
   tools via execute-fn, feeds results back, repeats until the LLM returns
   text (no more tool calls).

   execute-fn: (fn [tool-call] result-string)
     tool-call is {:id ... :name ... :arguments ...}

   Returns {:text ... :history ... :steps [...]}
     :text    - final text response
     :history - full message history (reusable)
     :steps   - vec of {:tool-calls [...] :tool-results [...]} per iteration

   opts must include :tools (or have them in provider :defaults).

   (run-agent ai {:tools [weather-tool]} executor \"Weather in Tokyo\")
   (run-agent ai {:tools [t] :max-steps 5} executor \"Weather in Tokyo\")"
  ([provider execute-fn input]
   (run-agent provider {} execute-fn input))
  ([provider opts execute-fn input]
   (let [max-steps (or (:max-steps opts) 10)
         base-opts (dissoc opts :max-steps)]
     (loop [history (build-messages input)
            steps []
            n 0]
       (let [result (generate provider base-opts history)]
         (if (vector? result)
           ;; Tool calls - execute and loop
           (let [tool-calls result
                 msg (:message (meta tool-calls))]
             (if (>= (inc n) max-steps)
               {:text "" :history history :steps steps :truncated true}
               (let [tool-results (mapv (fn [tc]
                                         {:call tc
                                          :result (execute-fn tc)})
                                       tool-calls)
                     result-msgs (mapv (fn [{:keys [call result]}]
                                        (tool-result (:id call) (str result)))
                                      tool-results)
                     new-history (into (conj history msg) result-msgs)]
                 (recur new-history
                        (conj steps {:tool-calls (vec tool-calls)
                                     :tool-results (mapv :result tool-results)})
                        (inc n)))))
           ;; Text response - done
           {:text result
            :history (conj history {:role :assistant :content result})
            :steps steps}))))))


(defn stream
  "Returns a channel of text chunks as they stream from the LLM.
   Input is last — string or message-history vector."
  ([provider input]
   (stream provider {} input))
  ([provider opts input]
   (:chunks (prompt provider opts input))))

(defn stream-print
  "Stream text to *out*, printing chunks as they arrive. Returns the full text string.
   Great for REPL use.

   (stream-print ai \"Tell me a story\")"
  ([provider input]
   (stream-print provider {} input))
  ([provider opts input]
   (let [ch (stream provider opts input)
         sb (StringBuilder.)]
     (loop []
       (when-let [chunk (<!! ch)]
         (.append sb chunk)
         (print chunk)
         (flush)
         (recur)))
     (println)
     (.toString sb))))

(defn events
  "Returns a channel of raw events from the LLM.
   Input is last — string or message-history vector."
  ([provider input]
   (events provider {} input))
  ([provider opts input]
   (:events (prompt provider opts input))))
