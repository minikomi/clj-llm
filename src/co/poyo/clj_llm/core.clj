(ns co.poyo.clj-llm.core
  (:require
   [co.poyo.clj-llm.protocol :as proto]
   [co.poyo.clj-llm.errors :as errors]
   [co.poyo.clj-llm.helpers :as helpers]

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
   [:api-base {:optional true :default "https://api.openai.com/v1"} :string]])

(def CallOptionsSchema
  "Schema for Library Specific Call Options"
  [:map {:closed true}
   [:model {:optional true :default "gpt-5-mini"} [:or :string :keyword]]
   [:system-prompt {:optional true} :string]
   [:schema {:optional true} :any]
   [:message-history {:optional true} [:vector [:map [:role :keyword] [:content :string]]]]])

(defn- extract-call-options [opts]
  (-> opts
      (m/decode (keys CallOptionsSchema) mt/strip-extra-keys-transformer)
      (m/validate CallOptionsSchema)))

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

(defn- build-opts [provider opts]
  (merge (:default-opts provider)
         (apply dissoc opts (keys CallOptionsSchema))))

(defn- build-messages
  "Build messages array from prompt and options"
  [prompt system-prompt]
  (cond-> []
    system-prompt (conj)
    prompt (conj {:role "user" :content prompt})))

;; ════════════════════════════════════════════════════════════════════
;; Main prompt fn
;; ════════════════════════════════════════════════════════════════════

(defn prompt
  ([provider prompt-input]
   (prompt provider prompt-input nil))
  ([provider prompt-input opts]
   (let [;; input setup
         {:keys [schema model system-prompt message-history]} (extract-call-options opts)
         api-opts (apply dissoc opts (keys CallOptionsSchema))
         ;; optional history
         message-history (cond
                           message-history message-history
                           system-prompt [{:role "system" :content system-prompt}]
                           :else [])
         ;; add this time's input
         messages (conj message-history (if (string? prompt-input)
                                          {:role "user" :content prompt-input}
                                          prompt-input))

         ;; chan setup
         source-chan (proto/request-stream provider model messages opts)
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
