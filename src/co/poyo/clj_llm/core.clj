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
   [:logit-bias        {:optional true} [:map-of int? int?]]
   [:on-complete       {:optional true} fn?]
   [:history           {:optional true} [:sequential [:map
                                                     [:role [:enum :user :assistant :system]]
                                                     [:content string?]]]]])

(def ^:private reserved-keys #{:on-complete})

(defn- validate-opts [impl model-id opts]
  (let [extra   (proto/-opts-schema impl model-id)
        schema  (mu/merge base-opts-schema extra)]
    (m/decode schema opts (mt/default-value-transformer))))

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
   [:logit-bias        {:optional true} [:map-of int? int?]]
   [:on-complete       {:optional true} fn?]
   [:history           {:optional true} [:sequential [:map
                                                     [:role [:enum :user :assistant :system]]
                                                     [:content string?]]]]])


(defn prompt
  "Execute a prompt against an LLM model and return a structured response."
  [model-id prompt-str & {:as opts}]
  (let [[backend-id model-name] (split-model-key model-id)
        impl (reg/get-backend backend-id)
        on-complete-fn (:on-complete opts)
        validated-opts (validate-opts impl model-id
                                     (apply dissoc opts (conj reserved-keys :on-complete)))
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
        tool-calls-delay (delay
                           (ensure-consumed)
                           (proto/-get-tool-calls impl model-name metadata))
        structured-output-delay (delay
                                  (ensure-consumed)
                                  (-> (proto/-get-tool-calls impl model-name metadata)
                                      first
                                      (get-in [:function :arguments])))
        ]
    (let [response-map {:chunks chunks-seq
                        :text text-deref
                        :usage usage-delay
                        :json json-delay
                        :tool-calls tool-calls-delay
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
   (let [f-meta (sch/f->meta f)
         f-name (or (-> f-meta :name str) "unnamed-function")
         full-f-schema (sch/get-schema-from-malli-function-registry f)
         f-input-schema (-> full-f-schema m/form second second)
         tool (sch/instrumented-function->tool-spec f)
         response (prompt model-id content (merge {:tools [tool], :tool-choice "auto"} llm-opts))
         tool-calls @(:tool-calls response)
         matching-call (first (filter #(= (get-in % [:function :name]) (str f-name)) tool-calls))]
     (when-not matching-call
       (throw (ex-info "LLM did not call expected function"
                       {:expected f-name, :calls-made (mapv #(get-in % [:function :name]) tool-calls)})))

     (let [args-map (get-in matching-call [:function :arguments])]
       (when validate?
         (let [validation-result (m/validate f-input-schema args-map)]
           (when-not validation-result
             (throw (ex-info "Function arguments did not match schema"
                             {:schema f-input-schema
                              :args args-map
                              :errors (m/explain f-input-schema args-map)})))))
       (f args-map)))))

(defn with-functions
  "Create an LLM interface with access to multiple functions.
   Returns a function that takes a prompt and executes the appropriate function."
  [& functions]
  (let [tools (mapv sch/instrumented-function->tool-spec functions)]
    (fn [content & {:keys [model-id] :or {model-id :openai/gpt-4.1-mini}}]
      (let [response (prompt model-id
                                content
                                {:tools tools
                                 :tool-choice "auto"})
            tool-calls @(:tool-calls response)]

        (when-not (seq tool-calls)
          (throw (ex-info "LLM did not make any tool calls"
                         {:response @(:text response)})))

        (let [call (first tool-calls)
              fn-name (get-in call [:function :name])
              args (get-in call [:function :arguments])
              matching-fn (first (filter #(= (str (-> % meta :name)) fn-name)
                                       functions))]

          (if matching-fn
            ((if (var? matching-fn) @matching-fn matching-fn) args)
            (throw (ex-info "No matching function found"
                           {:function-called fn-name
                            :available-functions (map #(-> % meta :name) functions)}))))))))
