(ns co.poyo.clj-llm.core
  (:require
   [co.poyo.clj-llm.registry :as reg]
   [co.poyo.clj-llm.protocol :as proto]
   [co.poyo.clj-llm.schema :as sch]
   [clojure.core.async :as a :refer [<!! chan go-loop <! >! close!]]
   [malli.core :as m]
   [malli.util :as mu]
   [malli.error :as me]
   [malli.transform :as mt]))

;; ──────────────────────────────────────────────────────────────
;; Option-schema plumbing
;; ──────────────────────────────────────────────────────────────

(def base-opts-schema
  [:map
   [:api-key           {:optional true} string?]
   [:temperature       {:optional true} [:double {:min 0 :max 2}]]
   [:top-p             {:optional true} [:double {:min 0 :max 1}]]
   [:max-tokens        {:optional true} pos-int?]
   [:stop              {:optional true} string?]
   [:frequency-penalty {:optional true} [:double {:min -2 :max 2}]]
   [:presence-penalty  {:optional true} [:double {:min -2 :max 2}]]
   [:seed              {:optional true} int?]
   [:output-schema     {:optional true} :any]
   [:on-complete       {:optional true} fn?]
   [:history           {:optional true} [:sequential [:map
                                                     [:role [:enum :user :assistant :system]]
                                                     [:content string?]]]]])

(def ^:private reserved-keys #{:on-complete})

(defn- validate-opts [impl model-id opts]
  (let [extra   (proto/-opts-schema impl model-id)
        schema  (mu/merge base-opts-schema extra)]
    (try
      (m/assert schema opts)
      (assoc (m/decode schema opts (mt/default-value-transformer))
             :schema
             (when (:schema opts)
               (m/schema (:schema opts))))
      (catch Exception e
        (-> e ex-data :data :explain me/humanize)))))

;; ──────────────────────────────────────────────────────────────
;; Internal helpers
;; ──────────────────────────────────────────────────────────────

(defn- split-model-key [kw]
  (when-not (keyword? kw)
    (throw (ex-info "Model id must be a qualified keyword" {:value kw})))
  [(keyword (namespace kw)) (name kw)])

;; ──────────────────────────────────────────────────────────────
;; Public API – always-streaming
;; ──────────────────────────────────────────────────────────────

(defn prompt
  "Execute a prompt against an LLM model and return a structured response."
  [model-id prompt-str & {:as opts}]
  (println "initial" opts)
  (let [[backend-id model-name] (split-model-key model-id)
        impl (reg/get-backend backend-id)
        on-complete-fn (:on-complete opts)
        validated-opts (validate-opts impl model-id
                                     (apply dissoc opts (conj reserved-keys :on-complete)))
        _ (println "validated" validated-opts)
        {:keys [channel metadata]} (proto/-stream impl model-name prompt-str validated-opts)
        text-promise (promise)
        consumed? (atom false)
        collected-chunks (atom [])

        out-channel (chan 100)

        _ (go-loop []
            (if-let [chunk (<! channel)]
              (do
                (swap! collected-chunks conj chunk)
                (>! out-channel chunk)
                (recur))
              (do
                (let [full-text (apply str @collected-chunks)]
                  (deliver text-promise full-text)
                  (reset! consumed? true))
                (close! out-channel))))

        chunks-fn (fn chunks-fn []
                    (if-let [chunk (<!! out-channel)]
                      (do

                        (cons chunk (lazy-seq (chunks-fn))))
                      nil))

        chunks-seq (lazy-seq (chunks-fn))

        ensure-consumed (fn []
                          (when (compare-and-set! consumed? false true)
                            ;; Consume the remaining chunks if any
                            (let [remaining-chunks (doall (take-while identity chunks-seq))
                                  ;; Add the remaining chunks to our collection
                                  _ (swap! collected-chunks into remaining-chunks)
                                  ;; Deliver the full text
                                  full-text (apply str @collected-chunks)]
                              (deliver text-promise full-text)))
                          @text-promise)

        ;; Rest of the code remains the same
        text-deref (reify
                     clojure.lang.IDeref
                     (deref [_] (ensure-consumed)))
        usage-delay (delay
                      (ensure-consumed)
                      (proto/-get-usage impl model-name metadata))
        json-delay (delay
                     (ensure-consumed)
                     (proto/-get-raw-json impl model-name metadata))
        structured-output-delay (delay
                                  (ensure-consumed)
                                  (proto/-get-structured-output impl model-name metadata))]

    (let [response-map {:chunks chunks-seq
                        :text text-deref
                        :usage usage-delay
                        :json json-delay
                        :structured-output structured-output-delay
                        :consumed? consumed?}]
      (when on-complete-fn
        (add-watch consumed? :on-complete
          (fn [_ _ _ new-state]
            (when new-state
              (future (on-complete-fn response-map))
              (remove-watch consumed? :on-complete)))))
      response-map)))

;; ──────────────────────────────────────────────────────────────
;; Conversational helper
;; ──────────────────────────────────────────────────────────────

(defn conversation [model]
  (let [history (atom [])
        on-complete-fn (fn [resp]
                         (swap! history conj
                                {:role :assistant
                                 :content @(:text resp)}))]
    {:prompt (fn [content & [opts]]
               (let [prev-on-complete (:on-complete opts)
                     internal-opts {:history @history
                                    :on-complete (do
                                                   (swap! history conj {:role :user :content content})
                                                   on-complete-fn
                                                   (when prev-on-complete
                                                     (fn [resp]
                                                       (prev-on-complete resp))))}
                     resp (prompt model content
                                 (merge opts internal-opts))]
                 resp))
     :history history
     :clear (fn [] (reset! history []))}))

;; ──────────────────────────────────────────────────────────────
;; Function Helpers
;; ──────────────────────────────────────────────────────────────

(defn call-function-with-llm
  ([f model-id content]
   (call-function-with-llm f model-id content {}))
  ([f model-id content {:keys [validate? llm-opts]
                        :or {validate? true llm-opts {}}}]
   (let [full-f-schema (sch/get-schema-from-malli-function-registry f)
         f-input-schema (-> full-f-schema m/form second second)
         schema (sch/instrumented-function->malli-schema f)
         response (prompt model-id content (merge {:schema schema} llm-opts))
         structured-output @(:structured-output response)]
     (when validate?
       (let [validation-result (m/validate f-input-schema structured-output)]
         (when-not validation-result
           (throw (ex-info "Function arguments did not match schema"
                           {:schema f-input-schema
                            :args structured-output
                            :errors (m/explain f-input-schema structured-output)})))))
     (f structured-output))))
