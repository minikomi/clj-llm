(ns co.poyo.clj-llm.core
  (:require
   [co.poyo.clj-llm.registry :as reg]
   [co.poyo.clj-llm.protocol :as proto]
   [co.poyo.clj-llm.schema :as sch]
   [cheshire.core :as json]
   [clojure.core.async :as a :refer [<!! mult tap chan go-loop <!]]
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
   [:validate-output?  {:optional true} boolean?]
   [:history           {:optional true} [:sequential [:map
                                                      [:role [:enum :user :assistant :system]]
                                                      [:content string?]]]]])

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

;; Event processing helpers - consolidated and reused
(defn- concat-content [events]
  (apply str (keep #(when (= :content (:type %)) (:content %)) events)))

(defn- last-usage [events]
  (some #(when (= :usage (:type %)) %) (reverse events)))

(defn- collect-tool-calls [events]
  (->> events
       (filter #(= :tool-call-delta (:type %)))
       (group-by :index)
       (mapv (fn [[idx chunks]]
               (let [{:keys [id name]} (first chunks)
                     arg-str (apply str (map :arguments chunks))]
                 {:index idx
                  :id    id
                  :name  name
                  :args-edn (json/parse-string arg-str true)})))))

;; ───────── public API ─────────
(defn prompt
  ([model prompt-str] (prompt model prompt-str {}))
  ([model prompt-str {:keys [schema validate-output?] :as opts}]
   ;; ---------- backend stream ----------
   (let [[bk mid] (split-model-key model)
         backend  (reg/fetch-backend bk)
         {:keys [channel]} (proto/-raw-stream backend mid prompt-str opts)]

     (when (not backend)
         (throw (ex-info "No backend found for model" {:model model})))

     ;; ---------- wiring ----------
     (let [m         (mult channel)
      chunks    (chan (a/dropping-buffer 256))
      coll      (chan 256)
      events*   (atom [])
      done!     (promise)]

       (tap m chunks)
       (tap m coll)
       ;; background processor
       (a/thread
         (loop []
           (if-some [ev (a/<!! coll)]
             (do (swap! events* conj ev) (recur))
             (deliver done! true))))

       ;; ---------- helper fns ----------
       (defn full-events [] @done! @events*)           ; wait till EOF
       (defn concat-text [evs] (apply str (keep #(when (= :content (:type %)) (:content %)) evs)))

       ;; ---------- return map ----------
       {:chunks chunks

 ;; non-blocking preview
 :text-live (delay (concat-content @events*))

 ;; blocking, waits for EOF
 :json   (delay (full-events))
 :text   (delay (concat-content (full-events)))   ; <— was @events*
 :usage  (delay (last-usage    (full-events)))
 :tool-calls (delay (collect-tool-calls (full-events)))
 :structured-output
 (delay
   (when-not schema
     (throw (ex-info "Structured output requested but :schema not supplied" {})))
   (let [tc   (collect-tool-calls (full-events))
         args (some-> tc first :args-edn)]
     (when (nil? args)
       (throw (ex-info "Model produced no tool call" {})))
     (if validate-output?
       (do (m/validate schema args) args)
       args)))}))))

;; ──────────────────────────────────────────────────────────────
;; Conversational helper - simplified callback handling
;; ──────────────────────────────────────────────────────────────

(defn conversation [model]
  (let [history (atom [])]
    {:prompt (fn [content & [opts]]
               (swap! history conj {:role :user :content content})
               (let [resp (prompt model content (merge opts {:history @history}))
                     prev-on-complete (:on-complete opts)]
                 ;; Handle completion callback
                 (when-let [on-complete-fn (or prev-on-complete identity)]
                   (future
                     (let [assistant-msg {:role :assistant :content @(:text resp)}]
                       (swap! history conj assistant-msg)
                       (when prev-on-complete (on-complete-fn resp)))))
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
