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
    {:optional true :description "System prompt for the AI"}
    :string]
   [::schema
    {:optional true :description "Schema for structured responses"}
    :any]
   [::model
    {:optional true :description "Model name"}
    :string]
   [::timeout-ms
    {:optional true :description "Request timeout in milliseconds"}
    pos-int?]
   [::message-history
    {:optional true :description "Conversation history"}
    MessageHistory]
   [::provider-opts {:optional true
                     :description "Provider-specific options (passthrough)"}
    :map]])

;; ════════════════════════════════════════════════════════════════════
;; Response record
;; ════════════════════════════════════════════════════════════════════

(defrecord Response [chunks events text usage structured]
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
  (assoc-in provider [:defaults ::provider-opts :model] model))

(defn with-schema [provider schema]
  (assoc-in provider [:defaults ::schema] schema))

(defn with-system-prompt [provider system-prompt]
  (assoc-in provider [:defaults ::system-prompt] system-prompt))

(defn with-timeout [provider timeout-ms]
  (assoc-in provider [:defaults ::timeout-ms] timeout-ms))

(defn with-provider-opts [provider opts]
  (assoc-in provider [:defaults ::provider-opts] opts))

(defn merge-provider-opts [provider opts]
  (update-in provider [:defaults ::provider-opts] #(merge % opts)))

;; ════════════════════════════════════════════════════════════════════
;; Input/Output Helpers
;; ════════════════════════════════════════════════════════════════════

(defn- extract-prompt-opts [opts]
  (if (m/validate PromptOpts opts)
    (m/coerce PromptOpts opts)
    (throw (errors/error
            "Invalid library options"
            {:errors (me/humanize (m/explain PromptOpts opts))
             :options opts}))))

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
  "Build messages array from prompt and options"
  [prompt system-prompt message-history]
  (let [base-messages (or message-history [])
        messages-with-system (if system-prompt
                               (if (and (seq base-messages)
                                        (= :system (:role (first base-messages))))
                                 ;; Replace existing system message
                                 (cons {:role :system :content system-prompt}
                                       (rest base-messages))
                                 ;; Add system message at start
                                 (cons {:role :system :content system-prompt}
                                       base-messages))
                               base-messages)]
    (if prompt
      (conj (vec messages-with-system) {:role :user :content prompt})
      (vec messages-with-system))))

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
         {::keys [system-prompt schema model message-history provider-opts]} (extract-prompt-opts merged-opts)

         ;; Merge model into provider-opts (provider-opts :model takes precedence)
         final-provider-opts (if model
                               (merge {:model model} provider-opts)
                               provider-opts)

         ;; Validate model is set
         _ (when-not (:model final-provider-opts)
             (throw (errors/error
                     "No model specified"
                     {:provider provider
                      :opts opts})))

         ;; Build final messages array
         messages (build-messages prompt-input system-prompt message-history)

         ;; chan setup
         source-chan (proto/request-stream provider messages schema final-provider-opts)
         req-start (System/currentTimeMillis)

         ;; chan with cleanup
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
                                            :clj-llm/provider-opts final-provider-opts
                                            :clj-llm/req-start req-start
                                            :clj-llm/req-end (System/currentTimeMillis)
                                            :clj-llm/duration (- (System/currentTimeMillis) req-start)))
                            (recur chunks))
                   :error (do
                            (deliver text-promise (errors/error
                                                   "LLM request failed"
                                                   {:event event
                                                    :request {:messages messages
                                                              :provider-opts final-provider-opts
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
                 structured-promise))))
