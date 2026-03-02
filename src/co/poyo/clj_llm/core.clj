(ns co.poyo.clj-llm.core
  (:require
   [clojure.core.async :as a :refer [go-loop <! <!! >! chan]]
   [clojure.set]
   [cheshire.core :as json]
   [malli.core :as m]
   [malli.error :as me]
   [malli.transform :as mt]
   [malli.util :as mu]
   [co.poyo.clj-llm.protocol :as proto]
))

;; ════════════════════════════════════════════════════════════════════
;; Option schemas (parse, don't validate)
;; ════════════════════════════════════════════════════════════════════

(def ^:private opts-schema
  "Schema for generate/request options."
  [:map {:closed true}
   [:model {:optional true} :string]
   [:system-prompt {:optional true} :string]
   [:schema {:optional true} :any]
   [:tools {:optional true} :any]
   [:tool-choice {:optional true} :any]
   [:temperature {:optional true} number?]
   [:max-tokens {:optional true} :int]
   [:top-p {:optional true} number?]
   [:provider-opts {:optional true} [:map-of :keyword :any]]])

(def ^:private agent-opts-schema
  "Schema for run-agent options (superset of opts-schema)."
  (mu/merge opts-schema
            [:map {:closed true}
             [:max-steps {:optional true} :int]
             [:stop-when {:optional true} fn?]
             [:on-tool-calls {:optional true} fn?]
             [:on-tool-result {:optional true} fn?]]))

;; Keys that get forwarded to the provider API (not consumed by clj-llm itself)
(def ^:private api-forward-keys
  #{:temperature :max-tokens :top-p})

(defn- parse-opts
  "Parse options against a malli schema. Returns a map with clj-llm keys
   and :provider-opts merged from api-forward-keys + explicit :provider-opts.
   Throws on unknown keys or invalid types."
  ([opts] (parse-opts opts opts-schema))
  ([opts schema]
   (let [explanation (m/explain schema opts)]
     (when explanation
       (throw (ex-info
               (str "Invalid options: " (pr-str (me/humanize explanation)))
               {:error-type :llm/invalid-request
                :errors     (me/humanize explanation)})))
     (let [provider-opts (not-empty
                          (merge
                           (-> (select-keys opts api-forward-keys)
                               (clojure.set/rename-keys {:max-tokens :max_tokens}))
                           (:provider-opts opts)))]
       (cond-> (apply dissoc opts (concat api-forward-keys [:provider-opts]))
         provider-opts (assoc :provider-opts provider-opts))))))

;; ════════════════════════════════════════════════════════════════════
(defn- unwrap!
  "Throw v if it's an exception, otherwise return it.
   Used to propagate errors delivered into promises."
  [v]
  (if (instance? Exception v) (throw v) v))

;; Response record
;; ════════════════════════════════════════════════════════════════════

(defrecord Response [chunks events text usage structured tool-calls]
  clojure.lang.IDeref
  (deref [_]
    (unwrap! @text)))

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
        (throw (ex-info
                "Schema validation failed"
                {:error-type :llm/invalid-request
                 :schema schema
                 :value result
                 :errors (me/humanize (m/explain schema result))}))))
    ;; Re-throw our own errors (schema validation); only catch parse failures below
    (catch clojure.lang.ExceptionInfo e (throw e))
    (catch Exception _
      (throw (ex-info
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
    :else (throw (ex-info
                  (str "Input must be a string, vector, or nil — got " (type input))
                  {:error-type :llm/invalid-request
                   :input input}))))

;; ════════════════════════════════════════════════════════════════════
;; Event stream consumer
;; ════════════════════════════════════════════════════════════════════

(def ^:private init-state
  "Initial accumulator for the event stream consumer."
  {:chunks [] :tool-calls [] :tool-call-positions {} :usage {} :finish-reason nil :done? false :error nil})

(defn- next-state
  "Pure state transition: state × event → state'.
   No side effects, no channels, no promises."
  [state event]
  (case (:type event)
    :content
    (update state :chunks conj (:content event))

    :tool-call
    (let [idx  (or (:index event) (count (:tool-calls state)))
          call (assoc event :arguments (or (:arguments event) ""))]
      (-> state
          (update :tool-calls conj call)
          (assoc-in [:tool-call-positions idx] (count (:tool-calls state)))))

    :tool-call-delta
    (let [pos (get (:tool-call-positions state) (:index event))]
      (if pos
        (update-in state [:tool-calls pos :arguments] str (:arguments event))
        state))

    :usage
    (update state :usage merge (dissoc event :type))

    :finish
    (assoc state :finish-reason (:reason event))

    :error
    (assoc state :done? true
                 :error (ex-info "LLM request failed"
                                 {:error-type :llm/server-error :event event}))

    :done
    (assoc state :done? true)

    ;; Forward-compat: ignore event types we don't recognize yet
    state))

(defn- state->text [{:keys [chunks error]}]
  (or error (apply str chunks)))

(defn- state->usage [{:keys [usage finish-reason]} provider-opts req-start]
  (let [u (cond-> usage finish-reason (assoc :finish-reason finish-reason))]
    (when (seq u)
      (let [now (System/currentTimeMillis)]
        (assoc u :type :usage
                 :clj-llm/provider-opts provider-opts
                 :clj-llm/req-start req-start
                 :clj-llm/req-end now
                 :clj-llm/duration (- now req-start))))))

(defn- consume-events
  "Consume events from source-chan, fan out to chunks/events channels,
   and deliver promises when the stream completes.

   State transitions are pure (next-state). This function knows nothing
   about structured output — that concern lives in `request`."
  [source-chan {:keys [text-chunks-chan events-chan
                       text-promise usage-promise tool-calls-promise
                       provider-opts req-start]}]
  (let [finalize! (fn [state]
                    (deliver text-promise (state->text state))
                    (deliver tool-calls-promise (not-empty (:tool-calls state)))
                    (deliver usage-promise (state->usage state provider-opts req-start))
                    (a/close! text-chunks-chan)
                    (a/close! events-chan))]

    ;; Go-loop: read event, compute next state, side-effects, recur
    (go-loop [state init-state]
      (if-let [event (<! source-chan)]
        (let [state' (next-state state event)]
          ;; Side effects: fan out to subscriber channels
          (>! events-chan event)
          (when (= :content (:type event))
            (>! text-chunks-chan (:content event)))
          ;; Continue or finalize
          (if (:done? state')
            (finalize! state')
            (recur state')))
        ;; Source channel closed without :done
        (finalize! state)))))

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
   (let [{:keys [model system-prompt schema tools tool-choice provider-opts] :as parsed}
         (parse-opts (merge (:defaults provider) opts))
         _             (when-not model
                         (throw (ex-info "No model specified"
                                         {:error-type :llm/invalid-request :opts parsed})))
         messages      (build-messages input)
         source-chan   (proto/request-stream provider
                        {:model model :system-prompt system-prompt :messages messages
                         :schema schema :tools tools :tool-choice tool-choice
                         :provider-opts (or provider-opts {})})
         ;; Dropping buffers: slow consumers (or nobody reading :chunks/:events)
         ;; must not block the go-loop that delivers text-promise.
         text-chunks   (chan (a/dropping-buffer 1024))
         events        (chan (a/dropping-buffer 1024))
         text-p        (promise)
         usage-p       (promise)
         structured-p  (promise)
         tool-calls-p  (promise)]

     (consume-events source-chan
                     {:text-chunks-chan   text-chunks
                      :events-chan        events
                      :text-promise       text-p
                      :usage-promise      usage-p
                      :tool-calls-promise tool-calls-p
                      :provider-opts     provider-opts
                      :req-start         (System/currentTimeMillis)})

     ;; Structured output: wait for text in a separate thread, parse + validate.
     ;; Decoupled from consume-events so the event loop stays generic.
     (if schema
       (future
         (try
           (let [text @text-p]
             (deliver structured-p
                      (if (instance? Exception text)
                        text
                        (parse-structured-output text schema))))
           (catch Exception e
             (deliver structured-p e))))
       (deliver structured-p nil))

     (map->Response {:chunks     text-chunks
                     :events     events
                     :text       text-p
                     :usage      usage-p
                     :structured structured-p
                     :tool-calls tool-calls-p}))))

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
        (throw (ex-info
                "Tool missing Malli schema. Use {:malli/schema ...}, mx/defn, or m/=>."
                {:error-type :llm/invalid-request :tool tool})))))

(defn- extract-input-schema
  "Extract the input map schema from a Malli function schema.
   Supports [:=> [:cat <map-schema>] <ret>] and [:-> <map-schema> <ret>]."
  [fn-schema]
  (let [fn-schema (m/schema fn-schema)
        schema-type (m/type fn-schema)
        ;; :-> is a flat proxy for :=> -- deref to normalize
        resolved (if (= :-> schema-type) (m/deref fn-schema) fn-schema)
        resolved-type (m/type resolved)]
    (when-not (= :=> resolved-type)
      (throw (ex-info
              "Tool schema must be [:=> [:cat ...] <return>] or [:-> <input> <return>]"
              {:error-type :llm/invalid-request :schema fn-schema})))
    (let [input-schema (first (m/children resolved))
          args (m/children (m/schema input-schema))]
      (when (empty? args)
        (throw (ex-info
                "Tool function schema has no input arguments"
                {:error-type :llm/invalid-request :schema fn-schema})))
      (first args))))

(defn- extract-tool-name
  "Get the tool name from a Malli schema's properties."
  [schema]
  (let [props (try (m/properties (m/schema schema)) (catch Exception _ nil))]
    (or (:name props)
        (throw (ex-info
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
              (throw (ex-info
                      (str "Unknown tool: " (:name tool-call))
                      {:error-type :llm/invalid-request
                       :name       (:name tool-call)
                       :available  (keys name->fn)})))]
    (f (:arguments tool-call))))

(defn tool-result
  "Create a tool result message for feeding back into message history.

   (tool-result \"call_abc\" \"Sunny, 22°C\")
   ;; => {:role :tool :tool-call-id \"call_abc\" :content \"Sunny, 22°C\"}"
  [tool-call-id content]
  {:role :tool :tool-call-id tool-call-id :content (str content)})


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
   (let [tools (or (:tools opts) (:tools (:defaults provider)))
         schema (or (:schema opts) (:schema (:defaults provider)))
         input-schemas (when tools (tools->input-schemas tools))
         api-opts (if tools
                    (assoc (dissoc opts :tools) :tools input-schemas)
                    opts)
         response (request provider api-opts input)
         text     @(:text response)]
     (unwrap! text)
     (cond
       tools
       (let [name->fn (build-name->fn tools input-schemas)
             parsed-calls (or (parse-tool-calls @(:tool-calls response)) [])]
         {:text       (not-empty text)
          :tool-calls parsed-calls
          :tool-results (mapv (partial execute-tool-call name->fn) parsed-calls)})

       schema
       (unwrap! @(:structured response))

       :else text))))




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
     :on-tool-calls   - (fn [{:keys [step tool-calls text]}] ...) called when the
                        model returns tool calls, before they are executed. Useful
                        for logging, UI updates, or progress indicators.
     :on-tool-result  - (fn [{:keys [step tool-call result error]}] ...) called
                        after each individual tool finishes. :error is the exception
                        if the tool threw (result will be the error string).
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
       \"do the thing\")

   Watch tool execution in real time:

     (run-agent ai tools
       {:on-tool-calls  (fn [{:keys [step tool-calls]}]
                          (println \"Step\" step \"calling:\" (map :name tool-calls)))
        :on-tool-result (fn [{:keys [step tool-call result]}]
                          (println \" \" (:name tool-call) \"->\" (subs result 0 80)))}
       \"research this topic\")"
  ([provider tools input]
   (run-agent provider tools {} input))
  ([provider tools opts input]
   (let [parsed (parse-opts opts agent-opts-schema)
         _      (when-not (and (sequential? tools) (seq tools))
                  (throw (ex-info "run-agent requires a non-empty tools vector"
                                   {:error-type :llm/invalid-request
                                    :tools tools})))
         input-schemas (tools->input-schemas tools)
         name->fn   (build-name->fn tools input-schemas)
         max-steps  (or (:max-steps parsed) 10)
         stop-when  (or (:stop-when parsed)
                        (fn [{:keys [tool-calls]}] (empty? tool-calls)))
         on-tool-calls  (:on-tool-calls parsed)
         on-tool-result (:on-tool-result parsed)
         request-opts (-> (dissoc parsed :max-steps :stop-when :on-tool-calls :on-tool-result)
                          (assoc :tools input-schemas))]
     (loop [history (build-messages input)
            steps []
            n 0]
       (let [response (request provider request-opts history)
             text     @(:text response)
             _        (unwrap! text)
             parsed-calls (or (parse-tool-calls @(:tool-calls response)) [])
             stop?    (stop-when {:tool-calls parsed-calls :text text})]
         (if stop?
           {:text       text
            :history    (conj history {:role :assistant :content (or text "")})
            :steps      steps
            :tool-calls (not-empty parsed-calls)}

           (let [_        (when (and on-tool-calls (seq parsed-calls))
                            (on-tool-calls {:step n :tool-calls parsed-calls :text text}))
                 results  (when (seq parsed-calls)
                            (mapv (fn [tc]
                                    (let [r (try
                                              {:call tc :result (execute-tool-call name->fn tc)}
                                              (catch Exception e
                                                {:call tc :result (str "Error: " (.getMessage e)) :error e}))]
                                      (when on-tool-result
                                        (on-tool-result {:step n
                                                         :tool-call tc
                                                         :result (:result r)
                                                         :error (:error r)}))
                                      r))
                                  parsed-calls))
                 msg      (if results
                            (tool-calls->assistant-message parsed-calls text)
                            {:role :assistant :content (or text "")})
                 msgs     (if results
                            (into [msg] (mapv (fn [{:keys [call result]}]
                                               (tool-result (:id call) (str result)))
                                             results))
                            [msg])
                 next-history (into history msgs)
                 next-steps   (if results
                                (conj steps {:tool-calls   (vec parsed-calls)
                                             :tool-results (mapv :result results)})
                                steps)]
             (if (>= (inc n) max-steps)
               {:text text :history next-history :steps next-steps :truncated true}
               (recur next-history next-steps (inc n))))))))))




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
