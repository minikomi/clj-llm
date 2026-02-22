(ns co.poyo.clj-llm.core
  (:require
   [co.poyo.clj-llm.protocol :as proto]
   [co.poyo.clj-llm.errors :as errors]
   [co.poyo.clj-llm.helpers :as helpers]
   [cheshire.core :as json]
   [clojure.core.async :as a :refer [go-loop <! <!! >! chan]]
   [malli.core :as m]
   [malli.error :as me]
   [malli.transform :as mt]))

;; ════════════════════════════════════════════════════════════════════
;; Known option keys (everything else is rejected)
;; ════════════════════════════════════════════════════════════════════

(def ^:private known-keys
  #{:model :system-prompt :schema :tools :tool-choice :temperature :max-tokens :top-p :provider-opts})

(def ^:private agent-keys
  "Additional keys accepted by run-agent."
  #{:tools :tool-choice :max-steps})

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
;; Provider defaults
;; ════════════════════════════════════════════════════════════════════
;; Provider defaults — just data on the :defaults key.
;;
;;   (def ai (assoc (openai/backend) :defaults {:model "gpt-4o-mini"}))
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
                {:error-type :llm/invalid-request
                 :schema schema
                 :value result
                 :errors (me/humanize (m/explain schema result))}))))
    (catch clojure.lang.ExceptionInfo e (throw e))
    (catch Exception e
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
        explicit (:provider-opts opts)
        ;; rename :max-tokens -> :max_tokens for the API
        promoted (if (contains? promoted :max-tokens)
                   (-> promoted
                       (assoc :max_tokens (:max-tokens promoted))
                       (dissoc :max-tokens))
                   promoted)]
    (merge promoted explicit)))

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
          (>! events-chan event)
          (case (:type event)
            :content
            (do (>! text-chunks-chan (:content event))
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
                (finalize! (errors/error "LLM request failed" {:error-type :llm/server-error
                                                               :event event}) nil))

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
         {:keys [model system-prompt schema tools tool-choice]} merged

         _ (when-not model
             (throw (errors/error "No model specified"
                                  {:error-type :llm/invalid-request :opts merged})))

         provider-opts (extract-provider-opts merged)
         messages      (build-messages input)
         source-chan    (proto/request-stream provider model system-prompt messages
                                             schema tools tool-choice
                                             (or provider-opts {}))
         text-chunks   (chan 1024)
         events        (chan 1024)
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
    (not (empty? text)) (assoc :content text)))

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

   For tool calling, use `run-agent`. For streaming/usage, use `request`."
  ([provider input]
   (generate provider {} input))
  ([provider opts input]
   (let [merged (helpers/deep-merge (:defaults provider) opts)]
     (when (:tools merged)
       (throw (errors/error
               (str "generate does not support :tools — use run-agent for tool calling")
               {:error-type :llm/invalid-request :opts opts})))
     (let [response (request provider opts input)
           text     @(:text response)
           _        (when (instance? Exception text) (throw text))]
       (if (:schema merged)
         (let [s @(:structured response)]
           (if (instance? Exception s) (throw s) s))
         text)))))



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
        ;; :-> is a flat proxy for :=> — deref to normalize
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

(defn tool-result
  "Create a tool result message for feeding back into message history.

   (tool-result \"call_abc\" \"Sunny, 22°C\")
   ;; => {:role :tool :tool-call-id \"call_abc\" :content \"Sunny, 22°C\"}"
  [tool-call-id content]
  {:role :tool :tool-call-id tool-call-id :content (str content)})

(defn run-agent
  "Run an agentic tool-calling loop. Tools are plain functions with standard
   Malli function schemas attached via metadata. Define tools with:

   ;; defn + :malli/schema (recommended)
   (defn get-weather
     {:malli/schema [:=> [:cat [:map {:name \"get_weather\"
                                      :description \"Get weather for a city\"}
                                [:city :string]]]
                        :string]}
     [{:keys [city]}]
     (str \"Sunny in \" city))

   ;; mx/defn (malli.experimental)
   (mx/defn get-weather :- :string
     [args :- [:map {:name \"get_weather\" :description \"Get weather\"}
                [:city :string]]]
     (str \"Sunny in \" (:city args)))

   ;; m/=> annotation (Malli global registry)
   (defn get-weather [{:keys [city]}] (str \"Sunny in \" city))
   (m/=> get-weather [:=> [:cat [:map {:name \"get_weather\"}
                                  [:city :string]]] :string])

   The input :map schema carries :name and :description for the LLM.
   Pass tool vars to run-agent:

   (run-agent ai [#'get-weather] \"Weather in Tokyo?\")

   tools: vector of tool vars or fns (each carries its schema in metadata)

   opts (optional):
     :max-steps      - max iterations (default 10)
     :temperature     - float, e.g. 0.7
     :max-tokens      - int, max tokens to generate
     :top-p           - float, nucleus sampling
     :provider-opts   - map of additional provider-specific API params
     + any request opts (:model, :system-prompt, etc.)

   Returns {:text ... :history ... :steps [...]}
     :text    - final text response (string)
     :history - full message history (reusable — feed into generate for structured extraction)
     :steps   - vec of {:tool-calls [...] :tool-results [...]} per iteration

   For structured output after tool use, compose with generate:

     (let [{:keys [history]} (run-agent ai tools \"find user 123\")]
       (generate ai {:schema result-schema} history))

   (run-agent ai [#'get-weather] \"Weather in Tokyo?\")
   (run-agent ai [#'get-weather #'search] {:max-steps 5} \"Weather in Tokyo?\")"
  ([provider tools input]
   (run-agent provider tools {} input))
  ([provider tools opts input]
   (let [_          (let [unknown (remove (into known-keys agent-keys) (keys opts))]
                      (when (seq unknown)
                        (throw (errors/error
                                (str "Unknown options: " (pr-str (vec unknown)))
                                {:error-type   :llm/invalid-request
                                 :unknown-keys (vec unknown)}))))
         _          (when-not (and (sequential? tools) (seq tools))
                      (throw (errors/error "run-agent requires a non-empty tools vector"
                                          {:error-type :llm/invalid-request
                                           :tools tools})))
         fn-schemas (mapv resolve-tool-schema tools)
         input-schemas (mapv extract-input-schema fn-schemas)
         name->fn   (into {} (map (fn [t s] [(extract-tool-name s) t]) tools input-schemas))
         max-steps  (or (:max-steps opts) 10)
         base-opts  (-> opts
                        (dissoc :max-steps)
                        (assoc :tools input-schemas))]
     (loop [history (build-messages input)
            steps []
            n 0]
       (let [response   (request provider base-opts history)
             text       @(:text response)
             _          (when (instance? Exception text) (throw text))
             tc         (parse-tool-calls @(:tool-calls response))]
         (if (seq tc)
           ;; Tool calls - look up and execute
           (let [msg (tool-calls->assistant-message tc text)]
             (if (>= (inc n) max-steps)
               {:text "" :history history :steps steps :truncated true}
               (let [results    (mapv (fn [t]
                                        (let [f (or (get name->fn (:name t))
                                                    (throw (errors/error
                                                            (str "Unknown tool: " (:name t))
                                                            {:error-type :llm/invalid-request
                                                             :name (:name t)
                                                             :available (keys name->fn)})))]
                                          {:call t :result (f (:arguments t))}))
                                      tc)
                     result-msgs (mapv (fn [{:keys [call result]}]
                                        (tool-result (:id call) (str result)))
                                      results)
                     new-history (into (conj history msg) result-msgs)]
                 (recur new-history
                        (conj steps {:tool-calls (vec tc)
                                     :tool-results (mapv :result results)})
                        (inc n)))))
           ;; No tool calls — done
           {:text    text
            :history (conj history {:role :assistant :content text})
            :steps   steps}))))))



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
