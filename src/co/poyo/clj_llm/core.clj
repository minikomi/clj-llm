(ns co.poyo.clj-llm.core
  (:require
   [clojure.core.async :as a]
   [clojure.set]
   [clojure.string :as str]
   [cheshire.core :as json]
   [malli.core :as m]
   [malli.error :as me]
   [malli.transform :as mt]
   [malli.util :as mu]
   [co.poyo.clj-llm.protocol :as proto]
   [co.poyo.clj-llm.content :as content]
   [co.poyo.clj-llm.stream :as stream]))

;; ════════════════════════════════════════════════════════════════════
;; Option schemas
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
   [:provider-opts {:optional true} [:map-of :keyword :any]]
   [:on-text {:optional true} fn?]
   [:on-reasoning {:optional true} fn?]
   [:on-tool-calls {:optional true} fn?]
   [:on-tool-result {:optional true} fn?]])

(def ^:private agent-opts-schema
  "Schema for run-agent options (superset of opts-schema)."
  (mu/merge opts-schema
            [:map {:closed true}
             [:max-steps {:optional true} :int]
             [:stop-when {:optional true} fn?]]))

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
;; Event state machine
;; ════════════════════════════════════════════════════════════════════

(def ^:private init-state
  "Initial accumulator for the event stream consumer."
  {:chunks [] :reasoning-chunks [] :tool-calls [] :tool-call-positions {} :usage {} :finish-reason nil :error nil})

(defn- next-state
  "Pure state transition: state × event → state'.
   No side effects."
  [state event]
  (case (:type event)
    :content
    (update state :chunks conj (:content event))

    :reasoning
    (update state :reasoning-chunks conj (:content event))

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
    (let [err (:error event)
          msg (or (:message err) (pr-str err))]
      (assoc state :error (ex-info (str "LLM error: " msg)
                                   {:error-type :llm/server-error :error err})))

    :done state

    ;; Forward-compat: ignore event types we don't recognize yet
    state))

(defn- finalize-state
  "Turn accumulated state into a result map."
  [{:keys [chunks reasoning-chunks error usage finish-reason tool-calls]}]
  (if error
    (throw error)
    (cond-> {:text (apply str chunks)}
      (seq reasoning-chunks) (assoc :reasoning (apply str reasoning-chunks))
      (seq usage)      (assoc :usage (cond-> usage finish-reason (assoc :finish-reason finish-reason)))
      (seq tool-calls) (assoc :tool-calls tool-calls))))

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

(defn- mixed-content-vector?
  "Returns true if v is a vector containing content parts (images, text parts)
   mixed with strings — i.e. a multimodal user message, not a message history."
  [v]
  (and (vector? v)
       (some content/content-part? v)))

(defn- normalize-content-element
  "Coerce an element of a mixed content vector to a content part.
   Strings become text parts. Content parts pass through."
  [x]
  (cond
    (string? x) (content/text x)
    (content/content-part? x) x
    :else (throw (ex-info
                  (str "Invalid content element: expected string or content part, got " (type x))
                  {:error-type :llm/invalid-request :element x}))))

(defn- build-messages
  "Coerce input to a messages vector.
   Map     → auto-unwrap :text (result from previous generate/run-agent)
   String  → [{:role :user :content input}]
   Vector of content parts/strings → [{:role :user :content [...parts...]}]
   Vector of messages → used as-is (message history)
   nil     → []"
  [input]
  (cond
    (:structured input) [{:role :user :content (prn-str (:structured input))}]
    (:text input)        [{:role :user :content (:text input)}]
    (string? input) [{:role :user :content input}]
    (mixed-content-vector? input)
    [{:role :user :content (mapv normalize-content-element input)}]
    (vector? input) input
    (nil? input)    []
    :else (throw (ex-info
                  (str "Input must be a string, vector, or nil — got " (type input))
                  {:error-type :llm/invalid-request
                   :input input}))))

(defn- provider-request-events
  "Orchestrate request using LLMProvider protocol methods.
   Returns core.async channel of events."
  [provider request]
  (let [{:keys [model system-prompt messages schema tools tool-choice provider-opts]} request
        body-map (proto/build-body provider model system-prompt messages
                                   schema tools tool-choice provider-opts)
        url (proto/build-url provider model)
        headers (proto/build-headers provider)
        body (json/generate-string body-map)]
    (let [raw-ch (proto/stream-events provider url headers body)
          ch (a/chan 256 (mapcat #(proto/parse-chunk provider % schema tools)))]
      (a/pipe raw-ch ch)
      ch)))

;; ════════════════════════════════════════════════════════════════════
;; Core API
;; ════════════════════════════════════════════════════════════════════

(def ^:dynamic *stream-timeout-ms*
  "Maximum milliseconds to wait for a single event from the provider stream.
   Default 5 minutes. Bind to a smaller value for interactive use."
  (* 5 60 1000))

(defn- chan-reduce
  "Blocking reduce over a core.async channel.
   Returns the final accumulated value.  Closes ch on early termination.
   Throws on timeout (configurable via *stream-timeout-ms*)."
  [rf init ch]
  (loop [acc init]
    (let [timeout-ch (a/timeout *stream-timeout-ms*)
          [v port] (a/alts!! [ch timeout-ch])]
      (cond
        (= port timeout-ch)
        (do (a/close! ch)
            (throw (ex-info (str "LLM stream timed out after " *stream-timeout-ms* "ms")
                            {:error-type :llm/timeout})))
        (nil? v) acc
        (instance? Throwable v) (throw v)
        :else (let [acc' (rf acc v)]
                (if (reduced? acc')
                  (do (a/close! ch) @acc')
                  (recur acc')))))))

(defn events
  "Return a bounded core.async channel of provider events.
   Close the channel to signal cancellation. Note: the underlying HTTP body stream
   will drain until the server closes the connection — for immediate cancellation,
   set a short *stream-timeout-ms* binding before calling.

   Events are maps with :type — :content, :tool-call, :tool-call-delta,
   :usage, :finish, :error, :done."
  ([provider input] (events provider {} input))
  ([provider opts input]
   (let [{:keys [model system-prompt schema tools tool-choice provider-opts] :as parsed}
         (parse-opts (merge (:defaults provider) opts))
         _ (when-not model
             (throw (ex-info "No model specified"
                             {:error-type :llm/invalid-request :opts parsed})))]
     (provider-request-events provider
       {:model model :system-prompt system-prompt
        :messages (build-messages input)
        :schema schema :tools tools :tool-choice tool-choice
        :provider-opts (or provider-opts {})}))))

(defn- parse-tool-calls
  "Parse JSON argument strings in tool calls.
   Throws on malformed JSON so callers get a clear error."
  [raw-tool-calls]
  (when raw-tool-calls
    (mapv (fn [t]
            (let [raw (:arguments t)]
              {:id   (:id t)
               :name (:name t)
               :arguments
               (if (string? raw)
                 (try (json/parse-string raw true)
                      (catch Exception e
                        (throw (ex-info (str "Malformed tool call arguments for " (:name t) ": " (.getMessage e))
                                        {:error-type :llm/server-error
                                         :tool       (:name t)
                                         :arguments  raw}))))
                 raw)}) )
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
                "Tool missing Malli schema. Use {:malli/schema ...} and pass function as a #'var, mx/defn, or m/=>."
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
  (mapv (fn [tool]
          (let [tool-name (last (str/split (str tool) #"\/" ))
                tool-schema (resolve-tool-schema tool)
                input-schema (extract-input-schema tool-schema)]
            (cond-> input-schema
                (not (:name (m/properties input-schema)))
                (mu/update-properties assoc :name tool-name))))
        tools))

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

(defn- serialize-tool-result
  "Serialize a tool result to a string for the LLM.
   Strings pass through; anything else becomes JSON."
  [v]
  (if (string? v)
    v
    (json/generate-string v)))

(defn tool-result
  "Create a tool result message for feeding back into message history.

   (tool-result \"call_abc\" \"Sunny, 22°C\")
   ;; => {:role :tool :tool-call-id \"call_abc\" :content \"Sunny, 22°C\"}

   Non-string values are automatically JSON-encoded:

   (tool-result \"call_abc\" {:name \"Tokyo\" :latitude 35.69})
   ;; => {:role :tool :tool-call-id \"call_abc\" :content \"{\\\"name\\\":\\\"Tokyo\\\",...}\"}"
  [tool-call-id content]
  {:role :tool :tool-call-id tool-call-id :content (serialize-tool-result content)})

(defn generate
  "Blocking generation. Returns a result map:

   (generate ai \"hello\")
   ;; => {:text \"Hello!\" :usage {:prompt-tokens 5 :completion-tokens 10}}

   (generate ai {:schema person-schema} \"extract this\")
   ;; => {:text \"{...}\" :structured {:name \"Alice\" :age 30} :usage {...}}

   (generate ai {:tools [#'get-weather]} \"Weather in Tokyo?\")
   ;; => {:text nil
   ;;     :tool-calls [{:id \"call_1\" :name \"get_weather\" :arguments {:city \"Tokyo\"}}]
   ;;     :tool-results [\"Sunny, 22C in Tokyo\"]
   ;;     :usage {...}}

   Common options:
     :model           - model name string
     :system-prompt   - system message string
     :schema          - Malli schema for structured output
     :temperature     - float, e.g. 0.7
     :max-tokens      - int, max tokens to generate
     :top-p           - float, nucleus sampling
     :provider-opts   - map of additional provider-specific API params

   Callbacks (all optional):
     :on-text         - (fn [chunk] ...) called for each text chunk as it streams
     :on-tool-calls   - (fn [{:keys [tool-calls text]}] ...) called before tools execute
     :on-tool-result  - (fn [{:keys [tool-call result error]}] ...) called after each tool

   Input is last — string, message-history vector, or a result map from
   a previous call. Results auto-unwrap :text when chained:

   (->> \"raw text\"
        (llm/generate ai {:system-prompt \"Fix grammar\"})
        (llm/generate ai {:system-prompt \"Translate to French\"}))

   Results are plain maps — (:text result), (:usage result), etc."
  ([provider input]
   (generate provider {} input))
  ([provider opts input]
   (let [start-time     (System/currentTimeMillis)
         tools          (or (:tools opts) (:tools (:defaults provider)))
         schema         (or (:schema opts) (:schema (:defaults provider)))
         model          (or (:model opts) (:model (:defaults provider)))
         on-text        (:on-text opts)
         on-tool-calls  (:on-tool-calls opts)
         on-tool-result (:on-tool-result opts)
         on-reasoning   (:on-reasoning opts)
         input-schemas  (when tools (tools->input-schemas tools))
         api-opts       (cond-> (dissoc opts :on-text :on-tool-calls :on-tool-result :on-reasoning)
                          tools (-> (dissoc :tools) (assoc :tools input-schemas)))
         timings        (atom {})
         result         (finalize-state
                         (chan-reduce
                          (fn [state event]
                            (when (= :reasoning (:type event))
                              (swap! timings update :reasoning-start #(or % (System/currentTimeMillis)))
                              (when on-reasoning (on-reasoning (:content event))))
                            (when (= :content (:type event))
                              (swap! timings update :content-start #(or % (System/currentTimeMillis)))
                              (when on-text (on-text (:content event))))
                            (next-state state event))
                          init-state
                          (events provider api-opts input)))
         end-time       (System/currentTimeMillis)
         {:keys [reasoning-start content-start]} @timings
         base           (cond-> {:timings {:duration-ms (- end-time start-time)}}
                          reasoning-start (update :timings assoc
                                                  :reasoning {:start-ms (- reasoning-start start-time)
                                                              :duration-ms (- (or content-start end-time) reasoning-start)})
                          content-start (update :timings assoc
                                                :text {:start-ms (- content-start start-time)
                                                       :duration-ms (- end-time content-start)})
                          (not-empty (:text result))      (assoc :text (:text result))
                          (not-empty (:reasoning result)) (assoc :reasoning (:reasoning result))
                          (:usage result)                 (assoc :usage (assoc (:usage result) :model model)))]
     (cond
       tools
       (let [name->fn      (build-name->fn tools input-schemas)
             parsed-calls  (or (parse-tool-calls (:tool-calls result)) [])
             _             (when (and on-tool-calls (seq parsed-calls))
                             (on-tool-calls {:tool-calls parsed-calls
                                             :text (not-empty (:text result))}))
             tool-results  (mapv (fn [tc]
                                   (let [res (try
                                               {:result (execute-tool-call name->fn tc)}
                                               (catch Exception e
                                                 {:result (str "Error: " (.getMessage e))
                                                  :error e}))]
                                     (when on-tool-result
                                       (on-tool-result {:tool-call tc
                                                        :result (:result res)
                                                        :error (:error res)}))
                                     (:result res)))
                                 parsed-calls)]
         (merge base {:tool-calls   parsed-calls
                      :tool-results tool-results}))

       schema
       (merge base {:structured (parse-structured-output (:text result) schema)})

       :else base))))

(defn run-agent
  "Run an agentic tool-calling loop. Tools are plain functions with standard
   Malli function schemas attached via metadata.

   (run-agent ai {:tools [#'get-weather]} \"Weather in Tokyo?\")
   (run-agent ai {:tools [#'get-weather #'search] :max-steps 5} \"plan a trip\")

   tools: vector of tool vars or fns (each carries its Malli schema in metadata)

   opts (optional):
     :max-steps       - max iterations (default 10)
     :stop-when       - (fn [{:keys [tool-calls text step tool-results]}] ...) called
                        twice per step: pre-execution (no :tool-results) and post-execution
                        (with :tool-results). Return truthy to stop. Default: stop when
                        no tool calls.
     :on-tool-calls   - (fn [{:keys [step tool-calls text]}] ...) called when the
                        model returns tool calls, before stop-when evaluation.
     :on-tool-result  - (fn [{:keys [step tool-call result error]}] ...) called
                        after each individual tool finishes.
     :on-text         - (fn [text-chunk] ...) called for each text chunk as it
                        streams in. Use for live typing display.
     :model, :system-prompt, :temperature, :max-tokens, :top-p, :provider-opts

   Returns {:text ... :history ... :steps [...] :tool-calls ... :usage ...}"
  ([provider input]
   (run-agent provider {} input))
  ([provider opts input]
   (let [parsed (parse-opts opts agent-opts-schema)
         tools  (or (:tools parsed) (:tools (:defaults provider)))
         _      (when-not (and (sequential? tools) (seq tools))
                  (throw (ex-info "run-agent requires a non-empty :tools vector"
                                   {:error-type :llm/invalid-request
                                    :tools tools})))
         input-schemas (tools->input-schemas tools)
         name->fn   (build-name->fn tools input-schemas)
         max-steps  (or (:max-steps parsed) 10)
         stop-when  (or (:stop-when parsed)
                        (fn [{:keys [tool-calls]}] (empty? tool-calls)))
         on-tool-calls  (:on-tool-calls parsed)
         on-tool-result (:on-tool-result parsed)
         on-text        (:on-text parsed)
         on-reasoning   (:on-reasoning parsed)
         request-opts (-> (dissoc parsed :max-steps :stop-when :on-tool-calls :on-tool-result :on-text :on-reasoning :tools)
                          (assoc :tools input-schemas))]
     (loop [history (build-messages input)
            steps []
            step 0]
       (let [{:keys [text usage] :as result}
             (finalize-state
               (chan-reduce (fn [state event]
                              (when (and on-text (= :content (:type event)))
                                (on-text (:content event)))
                              (when (and on-reasoning (= :reasoning (:type event)))
                                (on-reasoning (:content event)))
                              (next-state state event))
                            init-state
                            (events provider request-opts history)))
             parsed-calls (or (parse-tool-calls (:tool-calls result)) [])
             _        (when (and on-tool-calls (seq parsed-calls))
                        (on-tool-calls {:step step :tool-calls parsed-calls :text (not-empty text)}))
             pre-stop? (stop-when {:tool-calls parsed-calls :text text :step step})]
         (if pre-stop?
           (cond-> {:text       text
                    :history    (conj history {:role :assistant :content (or text "")})
                    :steps      steps
                    :tool-calls (not-empty parsed-calls)}
             usage (assoc :usage usage))

           (let [
                 results  (when (seq parsed-calls)
                            (mapv (fn [tc]
                                    (let [tool-exec (try
                                                      {:call tc :result (execute-tool-call name->fn tc)}
                                                      (catch Exception e
                                                        {:call tc :result (str "Error: " (.getMessage e)) :error e}))]
                                      (when on-tool-result
                                        (on-tool-result {:step step
                                                         :tool-call tc
                                                         :result (:result tool-exec)
                                                         :error (:error tool-exec)}))
                                      tool-exec))
                                  parsed-calls))
                 tool-results (mapv :result results)
                 assistant-msg (if results
                                 (tool-calls->assistant-message parsed-calls text)
                                 {:role :assistant :content (or text "")})
                 tool-msgs    (if results
                                (mapv (fn [{:keys [call result]}]
                                        (tool-result (:id call) result))
                                      results)
                                [])
                 next-history (-> history
                                 (into [assistant-msg])
                                 (into tool-msgs))
                 next-steps   (if results
                                (conj steps {:tool-calls   (vec parsed-calls)
                                             :tool-results tool-results})
                                steps)
                 post-stop? (when results
                              (stop-when {:tool-calls parsed-calls :text text :step step :tool-results tool-results}))]
             (if (or post-stop? (>= (inc step) max-steps))
               (cond-> {:text text :history next-history :steps next-steps}
                 post-stop? (assoc :tool-calls (not-empty parsed-calls)
                                   :tool-results (not-empty tool-results))
                 (and (not post-stop?) (>= (inc step) max-steps)) (assoc :truncated true)
                 usage (assoc :usage usage))
               (recur next-history next-steps (inc step))))))))))
