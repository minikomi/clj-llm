(ns co.poyo.clj-llm.core
  (:require
   [co.poyo.clj-llm.registry :as reg]
   [co.poyo.clj-llm.protocol :as proto]
   [co.poyo.clj-llm.schema :as sch]
   [co.poyo.clj-llm.sse :as sse]
   [cheshire.core :as json]
   [clojure.core.async :as async :refer [<!! close!]]
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

(defn- collect-events-from-channel
  "Consume all events from channel and return vector"
  [channel]
  (loop [events []]
    (if-let [event (<!! channel)]
      (recur (conj events event))
      events)))

(defn- collect-content-from-channel
  "Consume channel and concatenate content chunks"
  [channel]
  (loop [acc []]
    (if-let [event (<!! channel)]
      (if-let [content (get-in event [:choices 0 :delta :content])]
        (recur (conj acc content))
        (recur acc))
      (apply str acc))))


(defn- collect-tool-calls
  "Return vector [{:index i :id … :name … :args-edn {...}} …] once stream done."
  [events]
  (->> events
       (mapcat #(get-in % [:choices 0 :delta :tool_calls]))
       (reduce
         (fn [m {:keys [index id function]}]
           (update m index
                   (fn [{:keys [id name json-str]}]
                     {:id       (or id id)
                      :name     (or name (:name function))
                      :json-str (str (or json-str "") (:arguments function))})))
         {})
       (mapv (fn [[i {:keys [id name json-str]}]]
               {:index i
                :id id
                :name name
                :args-edn (cheshire.core/parse-string json-str true)}))))

;; ──────────────────────────────────────────────────────────────
;; Public API – always-streaming
;; ──────────────────────────────────────────────────────────────

(defn prompt
  ([model prompt-str] (prompt model prompt-str {}))
  ([model prompt-str opts]
   (let [[bk-key model-id] (split-model-key model)
         backend (or (reg/fetch-backend bk-key) (throw (ex-info "backend not registered" {:backend bk-key})))
         {:keys [channel metadata]} (proto/-raw-stream backend model-id prompt-str opts)

         ;; Create shared state for collected events
         events-atom (atom nil)
         realize-fn (fn []
                     (when (nil? @events-atom)
                       (reset! events-atom (collect-events-from-channel channel)))
                     @events-atom)]

     {:chunks channel ; Return channel directly for streaming

      :text (delay
             (let [events (realize-fn)]
               (apply str (keep #(get-in % [:choices 0 :delta :content]) events))))

      :usage (delay
              (let [events (realize-fn)]
                (some :usage (reverse events))))

      :json (delay
             (vec (realize-fn)))

      :tool-calls (delay
                   (collect-tool-calls (realize-fn)))

      :structured-output
      (delay
        (let [events (realize-fn)]
          (if-let [sch (:schema opts)]
            (-> (collect-tool-calls events) first :arguments)
            (throw (ex-info "No :schema supplied" {})))))})))

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
