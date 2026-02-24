(ns co.poyo.clj-llm.core
  (:require
   [clojure.core.async :as a :refer [go-loop <! <!! >! chan]]
   [clojure.set]
   [cheshire.core :as json]
   [malli.core :as m]
   [malli.error :as me]
   [malli.transform :as mt]
   [co.poyo.clj-llm.protocol :as proto]
   [co.poyo.clj-llm.errors :as errors]
   [co.poyo.clj-llm.helpers :as helpers]))

;; ════════════════════════════════════════════════════════════════════
;; Known option keys (everything else is rejected)
;; ════════════════════════════════════════════════════════════════════

(def ^:private known-keys
  #{:model :system-prompt :schema :tools :tool-choice :temperature :max-tokens :top-p :provider-opts})

(def ^:private agent-keys
  "Additional keys accepted by run-agent."
  #{:tools :tool-choice :max-steps :stop-when})

;; Keys that get forwarded to the provider API (not consumed by clj-llm itself)
(def ^:private api-forward-keys
  #{:temperature :max-tokens :top-p})

(defn- validate-opts [opts]
  (let [unknown (remove known-keys (keys opts))]
    (when (seq unknown)
      (throw (errors/error
              (str "Unknown options: " (pr-str (vec unknown)))
              {:error-type   :llm/invalid-request
               :unknown-keys (vec unknown)
               :valid-keys   known-keys}))))
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
                {:error-type :llm/invalid-request
                 :schema schema
                 :value result
                 :errors (me/humanize (m/explain schema result))}))))
    (catch clojure.lang.ExceptionInfo e (throw e))
    (catch Exception _
      (throw (errors/error
              "Failed to parse structured output"
              {:error-type :llm/invalid-request
               :schema schema :input text})))))

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
                  {:error-type :llm/invalid-request
                   :input input}))))

(defn- extract-provider-opts
  "Pull :temperature, :max-tokens, :top-p and :provider-opts from merged opts.
   Promoted keys are merged into :provider-opts (explicit :provider-opts wins on conflict)."
  [opts]
  (let [promoted (select-keys opts api-forward-keys)
        promoted (clojure.set/rename-keys promoted {:max-tokens :max_tokens})]
    (merge promoted (:provider-opts opts))))

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
  (let [finalize! (fn [text-val tool-calls-val usage-acc]
                    (deliver text-promise text-val)
                    (deliver tool-calls-promise tool-calls-val)
                    (deliver usage-promise
                            (when (seq usage-acc)
                              (assoc usage-acc
                                     :type :usage
                                     :clj-llm/provider-opts provider-opts
                                     :clj-llm/req-start req-start
                                     :clj-llm/req-end (System/currentTimeMillis)
                                     :clj-llm/duration (- (System/currentTimeMillis) req-start))))
                    (a/close! text-chunks-chan)
                    (a/close! events-chan))]

    ;; Consumer go-loop
    (go-loop [chunks []
              tool-calls []
              tc-index {} ;; maps provider index -> position in tool-calls vec
              usage-acc {} ;; accumulated usage across multiple :usage events
              finish-reason nil]
      (if-let [event (<! source-chan)]
        (do
          (>! events-chan event)
          (case (:type event)
            :content
            (do (>! text-chunks-chan (:content event))
                (recur (conj chunks (:content event)) tool-calls tc-index usage-acc finish-reason))

            :tool-call
            (let [idx (or (:index event) (count tool-calls))
                  call (assoc event :arguments (or (:arguments event) ""))]
              (recur chunks
                     (conj tool-calls call)
                     (assoc tc-index idx (count tool-calls))
                     usage-acc finish-reason))

            :tool-call-delta
            (let [pos (get tc-index (:index event))]
              (recur chunks
                     (if pos
                       (update-in tool-calls [pos :arguments] str (:arguments event))
                       tool-calls)
                     tc-index
                     usage-acc finish-reason))

            :usage
            (recur chunks tool-calls tc-index
                   (merge usage-acc (dissoc event :type)) finish-reason)

            :finish
            (recur chunks tool-calls tc-index usage-acc (:reason event))

            :error
            (do (when-not (realized? structured-promise)
                  (deliver structured-promise (Exception. (str (:error event)))))
                (finalize! (errors/error "LLM request failed" {:error-type :llm/server-error
                                                               :event event}) nil
                           (cond-> usage-acc finish-reason (assoc :finish-reason finish-reason))))

            :done
            (finalize! (apply str chunks) (not-empty tool-calls)
                       (cond-> usage-acc finish-reason (assoc :finish-reason finish-reason)))

            ;; Unknown event type — skip
            (recur chunks tool-calls tc-index usage-acc finish-reason)))

        ;; Source channel closed without :done
        (finalize! (apply str chunks) (not-empty tool-calls)
                   (cond-> usage-acc finish-reason (assoc :finish-reason finish-reason)))))

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

(defn request
  "Send a request to the LLM provider. Returns a Response record with:
    :text        - promise of full text string
    :chunks      - channel of text chunks as they stream
    :events      - channel of raw events
    :usage       - promise of token usage info
    :structured  - promise of parsed structured data (when :schema provided)
    :tool-calls  - promise of tool calls vector (when :tools provided)

   Supports @(request ...) via IDeref to block and get text.

   Input is the last arg — a string (prompt) or vector (message history).

   (request ai \"hello\")                              ; simple
   (request ai {:schema s} \"extract this\")            ; with opts
   (request ai {:tools t} [{:role :user ...} ...])    ; with history"
  ([provider input]
   (request provider {} input))
  ([provider opts input]
   (let [merged (validate-opts (helpers/deep-merge (:defaults provider) opts))
         {:keys [model system-prompt schema tools tool-choice]} merged]
     (when-not model
       (throw (errors/error "No model specified"
                            {:error-type :llm/invalid-request :opts merged})))
     (let [provider-opts (extract-provider-opts merged)
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

       (->Response text-chunks events text-p usage-p structured-p tool-calls-p)))))

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
  "Build the assistant message for tool call history round-tripping.
   Includes :content when the model returned text alongside tool calls."
  [tool-calls text]
  (cond-> {:role :assistant
           :tool-calls (mapv (fn [{:keys [id name arguments]}]
                               {:id id :type "function"
                                :function {:name name
                                           :arguments (if (string? arguments)
                                                        arguments
                                                        (json/generate-string arguments))}})
                             tool-calls)}
    (seq text) (assoc :content text)))

(defn- resolve-tool-schema
  "Get the Malli function schema ([:=> ...] or [:-> ...]) from a tool.
   Checks in order:
   1. :malli/schema on metadata (defn + {:malli/schema ...}, with-meta)
   2. :schema on metadata (mx/defn)
   3. Malli global function registry (m/=> annotation)"
  [tool]
  (let [m (meta tool)]
    (or (:malli/schema m)
        (:schema m)
        ;; Check Malli's global function registry (populated by m/=>)
        (when-let [ns-sym (some-> (:ns m) ns-name)]
          (:schema (get-in (m/function-schemas) [ns-sym (:name m)])))
        (throw (errors/error
                "Tool missing Malli schema. Use {:malli/schema ...}, mx/defn, or m/=>."
                {:error-type :llm/invalid-request :tool tool})))))

(defn- extract-input-schema
  "Extract the input map schema from a Malli function schema.
   Supports [:=> [:cat <map-schema>] <ret>] and [:-> <map-schema> <ret>]."
  [fn-schema]
  (let [s (m/schema fn-schema)
        t (m/type s)
        ;; :-> is a flat proxy for :=> -- deref to normalize
        resolved (if (= :-> t) (m/deref s) s)
        resolved-type (m/type resolved)]
    (when-not (= :=> resolved-type)
      (throw (errors/error
              "Tool schema must be [:=> [:cat ...] <return>] or [:-> <input> <return>]"
              {:error-type :llm/invalid-request :schema fn-schema})))
    (let [input-schema (first (m/children resolved))
          args (m/children (m/schema input-schema))]
      (when (empty? args)
        (throw (errors/error
                "Tool function schema has no input arguments"
                {:error-type :llm/invalid-request :schema fn-schema})))
      (first args))))

(defn- extract-tool-name
  "Get the tool name from a Malli schema's properties."
  [schema]
  (let [props (try (m/properties (m/schema schema)) (catch Exception _ nil))]
    (or (:name props)
        (throw (errors/error
                "Tool schema missing :name in properties"
                {:error-type :llm/invalid-request :schema schema})))))

(defn- tools->input-schemas
  "Extract Malli input schemas from a vector of tool vars/fns."
  [tools]
  (mapv (comp extract-input-schema resolve-tool-schema) tools))

(defn- build-name->fn
  "Build a map from tool name (string) to tool function."
  [tools input-schemas]
  (into {} (map (fn [t s] [(extract-tool-name s) t]) tools input-schemas)))

(defn- execute-tool-call
  "Look up and execute a single tool call. Returns the result."
  [name->fn tool-call]
  (let [f (or (get name->fn (:name tool-call))
              (throw (errors/error
                      (str "Unknown tool: " (:name tool-call))
                      {:error-type :llm/invalid-request
                       :name       (:name tool-call)
                       :available  (keys name->fn)})))]
    (f (:arguments tool-call))))

(defn generate
  "Blocking generation. Returns the natural value:

   - String input → string back
   - With :schema → parsed structured data

   (generate ai \"hello\")
   ;; => \"Hello!\"

   (generate ai {:schema person-schema} \"extract this\")
   ;; => {:name \"Alice\" :age 30}

   Common options:
     :model          - model name string
     :system-prompt   - system message string
     :schema          - Malli schema for structured output
     :temperature     - float, e.g. 0.7
     :max-tokens      - int, max tokens to generate
     :top-p           - float, nucleus sampling
     :provider-opts   - map of additional provider-specific API params

   Input is last — string or message-history vector. Threads with ->>:

   (->> \"raw text\"
        (llm/generate ai {:system-prompt \"Fix grammar\"})
        (llm/generate ai {:system-prompt \"Translate to French\"}))

   When :tools are provided, makes a single LLM call, executes any tool calls,
   and returns a map with all results:

   (generate ai {:tools [#'get-weather]} \"Weather in Tokyo?\")
   ;; => {:text nil
   ;;     :tool-calls [{:id \"call_1\" :name \"get_weather\" :arguments {:city \"Tokyo\"}}]
   ;;     :tool-results [\"Sunny, 22C in Tokyo\"]}

   For multi-turn tool loops, use `run-agent`.
   For streaming/usage, use `request`."
  ([provider input]
   (generate provider {} input))
  ([provider opts input]
   (let [merged (helpers/deep-merge (:defaults provider) opts)
         tools (:tools merged)
         input-schemas (when tools (tools->input-schemas tools))
         api-opts (if tools
                    (assoc (dissoc opts :tools) :tools input-schemas)
                    opts)
         response (request provider api-opts input)
         text     @(:text response)]
     (when (instance? Exception text) (throw text))
     (cond
       tools
       (let [name->fn (build-name->fn tools input-schemas)
             tc (or (parse-tool-calls @(:tool-calls response)) [])]
         {:text       (not-empty text)
          :tool-calls tc
          :tool-results (mapv (partial execute-tool-call name->fn) tc)})

       (:schema merged)
       (let [s @(:structured response)]
         (if (instance? Exception s) (throw s) s))

       :else text))))



(defn tool-result
  "Create a tool result message for feeding back into message history.

   (tool-result \"call_abc\" \"Sunny, 22°C\")
   ;; => {:role :tool :tool-call-id \"call_abc\" :content \"Sunny, 22°C\"}"
  [tool-call-id content]
  {:role :tool :tool-call-id tool-call-id :content (str content)})

(defn run-agent
  "Run an agentic tool-calling loop. Tools are plain functions with standard
   Malli function schemas attached via metadata.

   (run-agent ai [#'get-weather] \"Weather in Tokyo?\")
   (run-agent ai [#'get-weather #'search] {:max-steps 5} \"plan a trip\")

   tools: vector of tool vars or fns (each carries its Malli schema in metadata)

   opts (optional):
     :max-steps       - max iterations (default 10)
     :stop-when       - (fn [{:keys [tool-calls text]}] ...) called after each LLM
                        response, before executing tools. Return truthy to stop.
                        Default: stop when no tool calls (model is done).
     :model, :system-prompt, :temperature, :max-tokens, :top-p, :provider-opts

   Returns {:text ... :history ... :steps [...] :tool-calls ...}
     :text       - final text response (string)
     :history    - full message history (reusable)
     :steps      - vec of {:tool-calls [...] :tool-results [...]} per iteration
     :tool-calls - pending tool calls when :stop-when fired (nil otherwise)

   For structured output after tool use, compose with generate:

     (let [{:keys [history]} (run-agent ai tools \"find user 123\")]
       (generate ai {:schema result-schema} history))

   Stop the loop explicitly:

     ;; Stop when the model calls the 'done' tool
     (run-agent ai tools {:stop-when (fn [{:keys [tool-calls]}]
                                       (some #(= \"done\" (:name %)) tool-calls))}
       \"do the thing\")"
  ([provider tools input]
   (run-agent provider tools {} input))
  ([provider tools opts input]
   (let [unknown (remove (into known-keys agent-keys) (keys opts))]
     (when (seq unknown)
       (throw (errors/error
               (str "Unknown options: " (pr-str (vec unknown)))
               {:error-type   :llm/invalid-request
                :unknown-keys (vec unknown)}))))
   (when-not (and (sequential? tools) (seq tools))
     (throw (errors/error "run-agent requires a non-empty tools vector"
                          {:error-type :llm/invalid-request
                           :tools tools})))
   (let [input-schemas (tools->input-schemas tools)
         name->fn   (build-name->fn tools input-schemas)
         max-steps  (or (:max-steps opts) 10)
         stop-when  (or (:stop-when opts)
                        (fn [{:keys [tool-calls]}] (empty? tool-calls)))
         base-opts  (-> opts
                        (dissoc :max-steps :stop-when)
                        (assoc :tools input-schemas))]
     (loop [history (build-messages input)
            steps []
            n 0]
       (let [response (request provider base-opts history)
             text     @(:text response)
             _        (when (instance? Exception text) (throw text))
             tc       (parse-tool-calls @(:tool-calls response))
             stop?    (stop-when {:tool-calls (or tc []) :text text})]
         (if stop?
           ;; Stop condition met - return current state
           {:text    text
            :history (conj history {:role :assistant :content (or text "")})
            :steps   steps
            :tool-calls (not-empty tc)}
           ;; Execute tool calls and continue
           (if (empty? (or tc []))
             ;; No tool calls — model is reasoning. Append text and loop.
             (if (>= (inc n) max-steps)
               {:text text :history (conj history {:role :assistant :content (or text "")}) :steps steps :truncated true}
               (recur (conj history {:role :assistant :content (or text "")})
                      steps
                      (inc n)))
             ;; Has tool calls — execute them
             (let [msg (tool-calls->assistant-message tc text)]
               (if (>= (inc n) max-steps)
                 {:text text :history (conj history msg) :steps steps :truncated true}
                 (let [results    (mapv (fn [t]
                                          (try
                                            {:call t :result (execute-tool-call name->fn t)}
                                            (catch Exception e
                                              {:call t :result (str "Error: " (.getMessage e)) :error e})))
                                        tc)
                       result-msgs (mapv (fn [{:keys [call result]}]
                                          (tool-result (:id call) (str result)))
                                        results)
                       new-history (into (conj history msg) result-msgs)]
                   (recur new-history
                          (conj steps {:tool-calls (vec tc)
                                       :tool-results (mapv :result results)})
                          (inc n))))))))))))


(defn stream
  "Returns a channel of text chunks as they stream from the LLM.
   Input is last — string or message-history vector.

   (let [ch (llm/stream ai \"Count to 10\")]
     (loop []
       (when-let [chunk (<!! ch)]
         (print chunk) (flush)
         (recur))))"
  ([provider input]
   (stream provider {} input))
  ([provider opts input]
   (:chunks (request provider opts input))))

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
  "Returns a channel of raw SSE events from the LLM.
   Each event is a map with :type (:content, :tool-call, :tool-call-delta,
   :usage, :error, :done, :finish) and type-specific keys.

   Use this when you need fine-grained control over the event stream.
   For most cases, prefer `stream` (text chunks) or `request` (full response).

   Input is last — string or message-history vector."
  ([provider input]
   (events provider {} input))
  ([provider opts input]
   (:events (request provider opts input))))
