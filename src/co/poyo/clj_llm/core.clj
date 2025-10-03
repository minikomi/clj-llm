(ns co.poyo.clj-llm.core
  (:require
   [co.poyo.clj-llm.protocol :as proto]
   [co.poyo.clj-llm.errors :as errors]
   [co.poyo.clj-llm.helpers :as helpers]

   [clojure.core.async :as a :refer [go-loop <! >! <!! chan]]

   [malli.core :as m]
   [malli.error :as me]
   [malli.transform :as mt]

   [cheshire.core :as json]
   [clojure.string :as str]))

;; ════════════════════════════════════════════════════════════════════
;; Utilities
;; ════════════════════════════════════════════════════════════════════

(defn deep-merge
  "Deep merge maps, recursively merging nested maps"
  [& maps]
  (apply merge-with
         (fn [v1 v2]
           (if (and (map? v1) (map? v2))
             (deep-merge v1 v2)
             v2))
         maps))

;; ════════════════════════════════════════════════════════════════════
;; Malli Schemas for Configuration and Options
;; ════════════════════════════════════════════════════════════════════

(def LibraryOpts
  "Schema for library-level options (what clj-llm controls)"
  [:map
   [:llm/system-prompt {:optional true
                        :description "System prompt for the AI"}
    :string]
   [:llm/schema {:optional true
                 :description "Schema for structured responses"}
    :map]
   [:llm/timeout-ms {:optional true
                     :description "Request timeout in milliseconds"}
    pos-int?]
   [:llm/message-history {:optional true
                          :description "Conversation history"}
    [:vector [:map
              [:role [:enum :user :assistant :system]]
              [:content :string]]]]])

(def PromptOpts
  "Schema for combined options passed to prompt function"
  [:map
   [:llm/system-prompt {:optional true} :string]
   [:llm/schema {:optional true} :map]  
   [:llm/message-history {:optional true} [:vector :map]]
   [:provider/opts {:optional true
                    :description "Provider-specific options (passthrough)"}
    :map]])

(defn- extract-library-options [opts]
  (let [lib-keys (->> (m/children LibraryOpts)
                      (map first))]
    (-> opts
        (select-keys lib-keys)
        (as-> lib-opts
          (when (seq lib-opts)
            (m/validate LibraryOpts lib-opts)
            lib-opts)))))

;; ════════════════════════════════════════════════════════════════════
;; Response record
;; ════════════════════════════════════════════════════════════════════

(defrecord Response [chunks events text usage structured]
  clojure.lang.IDeref
  (deref [_] @text))

;; ════════════════════════════════════════════════════════════════════
;; Helpers
;; ════════════════════════════════════════════════════════════════════

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
    (catch clojure.lang.ExceptionInfo e (throw e))
    (catch Exception e
      (throw (errors/error
              "Failed to parse structured output"
              {:input text})))))

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
   (prompt provider prompt-input nil))
  ([provider prompt-input opts]
   (let [;; Merge defaults from provider with runtime options
         final-opts (deep-merge (:defaults provider) opts)
         
         ;; Validate and extract library options
         lib-opts (extract-library-options final-opts)
         {:llm/keys [system-prompt schema message-history]} lib-opts
         
         ;; Provider options pass through unchanged, with schema added for providers that need it
         provider-opts (assoc (:provider/opts final-opts) :__schema schema)
         
         ;; Build final messages array
         messages (build-messages prompt-input system-prompt message-history)

         ;; chan setup
         source-chan (proto/request-stream provider messages provider-opts)
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
                                            :clj-llm/provider-opts provider-opts
                                            :clj-llm/req-start req-start
                                            :clj-llm/req-end (System/currentTimeMillis)
                                            :clj-llm/duration (- (System/currentTimeMillis) req-start)))
                            (recur chunks))
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
