(ns co.poyo.clj-llm.core
  (:require
   [co.poyo.clj-llm.protocol :as proto]
   [co.poyo.clj-llm.errors :as errors]
   [co.poyo.clj-llm.helpers :as helpers]

   [cheshire.core :as json]
   [clojure.core.async :as a :refer [go-loop <! >! <!! chan]]
   [clojure.string :as str]
   [malli.core :as m]
   [malli.error :as me]
   [malli.transform :as mt]))

;; ════════════════════════════════════════════════════════════════════
;; Configuration and Options Schemas
;; ════════════════════════════════════════════════════════════════════

(def ContentSchema
  [:or
   :string
   [:vector
    [:map
     [:type [:enum "text" "image_url"]]
     [:text {:optional true} :string]
     [:image_url {:optional true}
      [:map [:url :string]]]]]])

(def MessageHistory
  [:vector
   [:map
    [:role [:enum :system :user :assistant :tool]]
    [:content {:optional true} ContentSchema]
    [:name {:optional true} :string]
    [:tool_calls {:optional true}
     [:vector
      [:map
       [:id :string]
       [:type [:enum "function"]]
       [:function [:map
                   [:name :string]
                   [:arguments :string]]]]]]
    [:tool_call_id {:optional true} :string]]])

(def PromptOpts
  "Schema for combined options passed to prompt function"
  [:map {:closed true
         :map-schema [:vector]}
   [::system-prompt
    {:optional true
     :description "System prompt for the AI"}
    :string]
   [::schema
    {:optional true
     :description "Schema for structured responses"}
    :any]
   [::tools
    {:optional true
     :description "Vector of tool schemas for multi-tool calling"}
    [:vector :any]]
   [::tool-choice
    {:optional true
     :description "Tool choice strategy: 'auto', 'required', 'none', or specific tool"}
    :any]
   [::model
    {:optional true
     :description "Model name"}
    :string]
   [::timeout-ms
    {:optional true
     :description "Request timeout in milliseconds"}
    pos-int?]
   [::message-history
    {:optional true
     :description "Conversation history"}
    MessageHistory]
   [::provider-opts
    {:optional true
     :default {}
     :description "Provider-specific options (passthrough)"}
    :map]])

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
;; Provider update helpers
;; ════════════════════════════════════════════════════════════════════

(defn with-defaults [provider defaults]
  (update provider :defaults #(helpers/deep-merge % defaults)))

(defn with-model [provider model]
  (assoc-in provider [:defaults ::model] model))

(defn with-schema [provider schema]
  (assoc-in provider [:defaults ::schema] schema))

(defn with-system-prompt [provider system-prompt]
  (assoc-in provider [:defaults ::system-prompt] system-prompt))

(defn with-timeout [provider timeout-ms]
  (assoc-in provider [:defaults ::timeout-ms] timeout-ms))

(defn with-message-history [provider message-history]
  (assoc-in provider [:defaults ::message-history] message-history))

(defn with-provider-opts [provider opts]
  (assoc-in provider [:defaults ::provider-opts] opts))

(defn merge-provider-opts [provider opts]
  (update-in provider [:defaults ::provider-opts] #(merge % opts)))

(defn with-tools [provider tools]
  (assoc-in provider [:defaults ::tools] tools))

(defn with-tool-choice [provider tool-choice]
  (assoc-in provider [:defaults ::tool-choice] tool-choice))

;; ════════════════════════════════════════════════════════════════════
;; Input/Output Helpers
;; ════════════════════════════════════════════════════════════════════

(defn- extract-prompt-opts [opts]
  (let [decoded (m/decode PromptOpts opts (mt/default-value-transformer))]
    (if-let [explanation (m/explain PromptOpts decoded)]
      (throw (errors/error
              "Invalid prompt options"
              {:errors (me/humanize explanation)}))
      decoded)))

(defn- parse-structured-output
  "Parse the response as JSON when schema is provided"
  [text schema]
  (try
    (let [parsed (json/parse-string text true)]
      (let [result (m/decode schema parsed mt/json-transformer)]
        (if (m/validate schema result)
          result
          (throw (errors/error
                  "Schema validation failed"
                  {:schema schema
                   :value result
                   :errors (me/humanize (m/explain schema result))})))))
    (catch clojure.lang.ExceptionInfo e (throw e))
    (catch Exception e
      (throw (errors/error
              "Failed to parse structured output"
              {:schema schema
               :input text})))))

(defn- build-messages
  "Build messages array from prompt and message history (excludes system prompt)"
  [prompt message-history]
  (let [base-messages (or message-history [])]
    (if prompt
      (conj (vec base-messages) {:role :user :content prompt})
      (vec base-messages))))

;; ════════════════════════════════════════════════════════════════════
;; Main prompt fn
;; ════════════════════════════════════════════════════════════════════

(defn prompt
  ([provider prompt-input]
   (prompt provider prompt-input {}))
  ([provider prompt-input opts]
   (let [;; Merge provider defaults with user opts
         merged-opts (helpers/deep-merge (:defaults provider) opts)

         ;; Validate and extract opts
         {::keys [system-prompt schema tools tool-choice model message-history provider-opts]} (extract-prompt-opts merged-opts)

         ;; Validate model is set
         _ (when-not model
             (throw (errors/error
                     "No model specified"
                     {:provider provider
                      :opts opts})))

         ;; Build final messages array
         messages (build-messages prompt-input message-history)

         ;; chan setup
         source-chan (proto/request-stream provider model system-prompt messages schema tools tool-choice provider-opts)
         req-start (System/currentTimeMillis)

         ;; chan with cleanup
         text-chunks-chan (chan (a/dropping-buffer 1024))
         events-chan (chan (a/dropping-buffer 1024))

         ;; Promises
         text-promise (promise)
         usage-promise (promise)
         structured-promise (promise)
         tool-calls-promise (promise)

         ;; Consumer loop
         _ (go-loop [chunks []
                    tool-calls []
                    tool-calls-by-index {}]  ;; Track tool calls by index for delta accumulation
             (if-let [event (<! source-chan)]

               ;; Process event
               (do
                 (a/offer! events-chan event)
                 (case (:type event)
                   :content (do
                              (a/offer! text-chunks-chan (:content event))
                              (recur (conj chunks (:content event)) tool-calls tool-calls-by-index))
                   :tool-call
                   (let [idx (or (:index event) (count tool-calls))
                         new-call (assoc event :arguments (or (:arguments event) ""))]
                     (recur chunks
                            (conj tool-calls new-call)
                            (assoc tool-calls-by-index idx (count tool-calls))))
                   :tool-call-delta
                   (let [idx (:index event)
                         tc-idx (get tool-calls-by-index idx)
                         updated-calls (if tc-idx
                                        (update-in tool-calls [tc-idx :arguments] str (:arguments event))
                                        tool-calls)]
                     (recur chunks updated-calls tool-calls-by-index))
                   :usage (do
                            (deliver usage-promise
                                     (assoc event
                                            :clj-llm/provider-opts provider-opts
                                            :clj-llm/req-start req-start
                                            :clj-llm/req-end (System/currentTimeMillis)
                                            :clj-llm/duration (- (System/currentTimeMillis) req-start)))
                            (recur chunks tool-calls tool-calls-by-index))
                   :error (do
                            (deliver text-promise (errors/error
                                                   "LLM request failed"
                                                   {:event event
                                                    :request {:messages messages
                                                              :provider-opts provider-opts
                                                              :started-at req-start
                                                              :provider provider}}))
                            (when-not (realized? usage-promise)
                              (deliver usage-promise nil))
                            (when-not (realized? structured-promise)
                              (deliver structured-promise (Exception. (:error event))))
                            (when-not (realized? tool-calls-promise)
                              (deliver tool-calls-promise nil))
                            (a/close! text-chunks-chan)
                            (a/close! events-chan))
                   :done (do
                           (deliver text-promise (apply str chunks))
                           (deliver tool-calls-promise (if (seq tool-calls) tool-calls nil))
                           (when-not (realized? usage-promise)
                             (deliver usage-promise nil))
                           (a/close! text-chunks-chan)
                           (a/close! events-chan))
                   (recur chunks tool-calls tool-calls-by-index)))
               ;; Source closed
               (do
                 (deliver text-promise (apply str chunks))
                 (deliver tool-calls-promise (if (seq tool-calls) tool-calls nil))
                 (when-not (realized? usage-promise)
                   (deliver usage-promise nil))
                 (a/close! text-chunks-chan)
                 (a/close! events-chan))))

         ;; Structured output processing
         _ (when schema
             (future
               (try
                 (let [text @text-promise]
                   (deliver structured-promise
                            (if (instance? Exception text)
                              text
                              (parse-structured-output text schema))))
                 (catch Exception e
                   (deliver structured-promise e)))))]

     (->Response text-chunks-chan
                 events-chan
                 text-promise
                 usage-promise
                 structured-promise
                 tool-calls-promise))))
