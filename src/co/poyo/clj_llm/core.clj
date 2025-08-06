(ns co.poyo.clj-llm.core
  (:require
   [co.poyo.clj-llm.protocol :as proto]
   [co.poyo.clj-llm.errors :as errors]
   [clojure.core.async :as a :refer [go-loop <! >! <!! chan]]
   [malli.core :as m]
   [malli.error :as me]
   [malli.transform :as mt]
   [malli.generator :as mg]
   [cheshire.core :as json]
   [clojure.string :as str]))


;; ════════════════════════════════════════════════════════════════════
;; Malli Schemas for Configuration and Options
;; ════════════════════════════════════════════════════════════════════

(def BackendConfigSchema
  "Schema for backend configuration options"
  [:map {:closed true}
   [:api-key {:optional true} :string]
   [:api-key-env {:optional true :default "OPENAI_API_KEY"} :string]
   [:api-base {:optional true :default "https://api.openai.com/v1"} :string]
   [:default-model {:optional true :default "gpt-4o-mini"} :string]
   [:timeout-ms {:optional true :default 60000} [:int {:min 1000}]]])

(def CallOptionsSchema
  "Schema for LLM call options"
  [:map {:closed true}
   [:model {:optional true} :string]
   [:temperature {:optional true} [:double {:min 0.0 :max 2.0}]]
   [:max-tokens {:optional true} [:int {:min 1}]]
   [:top-p {:optional true} [:double {:min 0.0 :max 1.0}]]
   [:frequency-penalty {:optional true} [:double {:min -2.0 :max 2.0}]]
   [:presence-penalty {:optional true} [:double {:min -2.0 :max 2.0}]]
   [:system-prompt {:optional true} :string]
   [:schema {:optional true} :any]
   [:messages {:optional true} [:vector [:map [:role :keyword] [:content :string]]]]
   [:timeout {:optional true} [:int {:min 1000}]]
   [:seed {:optional true} :int]
   [:stop {:optional true} [:vector :string]]
   [:response-format {:optional true} [:map [:type :string]]]])

;; ════════════════════════════════════════════════════════════════════
;; Options Transformation and Validation
;; ════════════════════════════════════════════════════════════════════

(defn- underscore->kebab [k]
  "Convert underscore keyword to kebab-case"
  (if (keyword? k)
    (keyword (str/replace (name k) "_" "-"))
    k))

(def options-transformer
  "Transformer to convert underscore keywords to kebab-case"
  (mt/transformer
    {:name :options
     :decoders {'keyword? underscore->kebab}}))

;; ════════════════════════════════════════════════════════════════════
;; REPL Exploration Functions
;; ════════════════════════════════════════════════════════════════════

(defn options
  "Get human-readable documentation for all available options.
   Returns a map of option names to their properties."
  []
  (into {}
    (for [[k props schema] (m/children CallOptionsSchema)]
      (let [schema-type (m/type schema)
            properties (when (vector? schema) (m/properties schema))]
        [k (merge
            {:optional? (:optional props true)
             :type schema-type}
            (when properties
              {:constraints properties}))]))))

(defn describe-options
  "Print a formatted description of all available options"
  []
  (println "\n═══ Available LLM Call Options ═══\n")
  (doseq [[k info] (options)]
    (println (format "  %-20s %s%s"
                     (str k)
                     (name (:type info))
                     (if-let [constraints (:constraints info)]
                       (format " %s" (pr-str constraints))
                       ""))))
  (println "\n═══ Backend Configuration Options ═══\n")
  (doseq [[k props schema] (m/children BackendConfigSchema)]
    (println (format "  %-20s %s%s"
                     (str k)
                     (if (keyword? schema) (name schema) "custom")
                     (if-let [default (:default props)]
                       (format " (default: %s)" default)
                       ""))))
  nil)

(defn valid?
  "Check if options are valid according to the schema.
   Returns true if valid, false otherwise."
  [opts]
  (nil? (m/explain CallOptionsSchema opts)))

(defn explain
  "Explain why options are invalid. Returns nil if valid,
   otherwise returns human-readable error messages."
  [opts]
  (when-let [explanation (m/explain CallOptionsSchema opts)]
    (me/humanize explanation)))

(defn coerce
  "Coerce options to valid format. Fixes underscores to kebab-case
   and attempts type coercion."
  [opts]
  (m/decode CallOptionsSchema opts options-transformer))

(defn validate-options
  "Validate options and throw helpful exception if invalid"
  [opts]
  (let [coerced (coerce opts)]
    (if-let [explanation (m/explain CallOptionsSchema coerced)]
      (let [errors (me/humanize explanation)
            invalid-keys (keys errors)
            valid-keys (map first (m/children CallOptionsSchema))]
        (throw (ex-info "Invalid options"
                        {:errors errors
                         :invalid-keys invalid-keys
                         :valid-options valid-keys
                         :hint (format "Valid options: %s" (pr-str valid-keys))
                         :see-also "(describe-options) for full documentation"})))
      coerced)))

;; ════════════════════════════════════════════════════════════════════
;; Response Record
;; ════════════════════════════════════════════════════════════════════

(defrecord Response [chunks events text usage structured]
  clojure.lang.IDeref
  (deref [_] @text))


(defn- parse-structured-output
  "Parse the response as JSON when schema is provided"
  [text schema]
  (try
    (let [parsed (json/parse-string text true)]
      (if schema
        (let [result (m/decode schema parsed mt/json-transformer)]
          (if (m/validate schema result)
            result
            (throw (errors/error
                    "Schema validation failed"
                    {:schema schema
                     :value result
                     :errors (me/humanize (m/explain schema result))}))))
        parsed))
    (catch clojure.lang.ExceptionInfo e
      ;; Re-throw our own errors
      (throw e))
    (catch Exception e
      (throw (errors/error
              "Failed to parse structured output"
              {:input text})))))

(defn- build-messages
  "Build messages array from prompt and options"
  [prompt {:keys [system-prompt messages schema] :as opts}]
  (if messages messages
      (let [effective-prompt (if (and prompt schema)
                               (str prompt "\n\nRespond with valid JSON.")
                               prompt)]
        (cond-> []
          system-prompt (conj {:role "system" :content system-prompt})
          effective-prompt (conj {:role "user" :content effective-prompt})))))


(defn prompt
  ([provider prompt-str]
   (prompt provider prompt-str nil))
  ([provider prompt-str opts]
   (let [;; Validate and coerce options
         validated-opts (when opts (validate-options opts))
         opts (merge (:default-opts provider) validated-opts)
         model (or (:model opts) (:default-model provider))
         messages (build-messages prompt-str opts)
         source-chan (proto/request-stream provider model messages opts)
         req-start (System/currentTimeMillis)

         ;; Channels with cleanup
         text-chunks-chan (chan (a/dropping-buffer 1024))
         events-chan (chan (a/dropping-buffer 1024))

         ;; Promises
         text-promise (promise)
         usage-promise (promise)
         structured-promise (promise)

         ;; Consumer loop
         _ (go-loop [chunks []]
             (if-let [event (<! source-chan)]
               ;; Process event
                 (do
                   (a/offer! events-chan event)
                   (case (:type event)
                     :content (do
                               (a/offer! text-chunks-chan (:content event))
                               (recur (conj chunks (:content event))))
                     :usage (do
                             (deliver usage-promise
                                      (assoc event
                                             :clj-llm/model model
                                             :clj-llm/req-start req-start
                                             :clj-llm/req-end (System/currentTimeMillis)
                                             :clj-llm/duration (- (System/currentTimeMillis) req-start)))
                             (recur chunks))
                     :error (do
                             (deliver text-promise (errors/error
                                                   "LLM request failed" 
                                                   {:event event
                                                    :request {:model model
                                                             :messages messages
                                                             :started-at req-start
                                                             :provider provider}}))
                             (when-not (realized? usage-promise)
                               (deliver usage-promise nil))
                             (when-not (realized? structured-promise)
                               (deliver structured-promise (Exception. (:error event))))
                             (a/close! text-chunks-chan)
                             (a/close! events-chan))
                     :done (do
                            (deliver text-promise (apply str chunks))
                            (when-not (realized? usage-promise)
                              (deliver usage-promise nil))
                            (a/close! text-chunks-chan)
                            (a/close! events-chan))
                     (recur chunks)))
               ;; Source closed
               (do
                 (deliver text-promise (apply str chunks))
                 (when-not (realized? usage-promise)
                   (deliver usage-promise nil))
                 (a/close! text-chunks-chan)
                 (a/close! events-chan))))

         ;; Structured output processing
         _ (when (:schema opts)
             (future
               (try
                 (let [text @text-promise]
                   (deliver structured-promise
                            (if (instance? Exception text)
                              text
                              (parse-structured-output text (:schema opts)))))
                 (catch Exception e
                   (deliver structured-promise e)))))]

     (->Response text-chunks-chan
                 events-chan
                 text-promise
                 usage-promise
                 structured-promise))))


(defn generate
  "Generate response from the LLM.

     Args:
         provider - LLM provider instance
         prompt   - String prompt or nil if messages provided in opts
         opts     - Options map (see `describe-options` for available options)

     Returns:
         Text response or, when schema present, structured data.

     Example:
         (generate openai \"Tell me a joke\") 
         (generate openai \"Write a poem\" {:temperature 0.9 :max-tokens 100})"
  ([provider prompt-str]
   (generate provider prompt-str nil))
  ([provider prompt-str opts]
   (let [response (prompt provider prompt-str opts)
         timeout-ms (or (:timeout opts) 
                       (:timeout (:default-opts provider)) 
                       30000)]
     
     (let [result-promise (if (or (:schema opts) (:schema (:default-opts provider)))
                             (:structured response)
                             (:text response))
             result (deref result-promise timeout-ms ::timeout)]
         (cond
           (= result ::timeout)
           (throw (errors/error 
                   (str "LLM request timed out after " timeout-ms "ms")
                   {:timeout-ms timeout-ms
                    :provider provider 
                    :prompt prompt-str 
                    :opts opts}))
           
           (instance? Exception result)
           (throw result)
           
           :else
           result)))))

(defn events
  "Get raw events channel for monitoring LLM interactions.

   Args:
     provider - LLM provider instance
     prompt   - String prompt or nil if messages provided in opts
     opts     - Options map (see `prompt` for details)

   Returns:
     Channel of raw events with type :content, :usage, :error, etc.

   Example:
     (let [events (events openai \"Hello\")]
       (go-loop []
         (when-let [event (<! events)]
           (handle-event event)
           (recur))))"
  ([provider prompt-str]
   (events provider prompt-str nil))
  ([provider prompt-str opts]
   (:events (prompt provider prompt-str opts))))

(defn stream
    "Stream text chunks from the LLM.

     Args:
         provider - LLM provider instance
         prompt   - String prompt or nil if messages provided in opts
         opts     - Options map (see `prompt` for details)

     Returns:
         Channel of text chunks as they are generated.

     Example:
         (let [chunks (stream openai \"Tell me a story\")]
         (go-loop []
             (when-let [chunk (<! chunks)]
             (process-chunk chunk)
             (recur))))"
    ([provider prompt-str]
     (stream provider prompt-str nil))
    ([provider prompt-str opts]
     (:chunks (prompt provider prompt-str opts))))

