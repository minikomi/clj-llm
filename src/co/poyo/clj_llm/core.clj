(ns co.poyo.clj-llm.core
  (:require
   [co.poyo.clj-llm.registry :as reg]
   [co.poyo.clj-llm.protocol :as proto]
   [co.poyo.clj-llm.schema :as sch]
   [co.poyo.clj-llm.sse :as sse]
   [cheshire.core :as json]
   [promesa.core :as p]
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

(defn- collect-tool-calls [evs]
  (->> evs
       (mapcat #(get-in % [:choices 0 :delta :tool_calls]))
       (reduce (fn [m {:keys [index id function]}]
                 (update m index
                         (fn [{:keys [id name args]}]
                           {:id   (or id id)
                            :name (or name (:name function))
                            :args (str (or args "") (:arguments function))})))
               {})
       (mapv (fn [[i {:keys [id name args]}]]
               {:index i
                :id id
                :name name
                :arguments (json/parse-string args true)}))))

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
         backend (or (reg/fetch-backend bk-key)
                     (throw (ex-info "backend not registered" {:backend bk-key})))
         {:keys [stream _ metadata]}
         (proto/-raw-stream backend model-id prompt-str (dissoc opts :on-complete))) ; on-complete handled separately
         events-channel (sse/stream->events stream)
         all-events-p   (p/into [] events-channel)
         on-complete-fn (:on-complete opts)]

     (let [text-p (p/let [all-events all-events-p]
                    (apply str (keep #(get-in % [:choices 0 :delta :content]) all-events)))
           usage-p (p/let [all-events all-events-p]
                     (some :usage (reverse all-events)))
           tool-calls-p (p/let [all-events all-events-p]
                          (collect-tool-calls all-events))
           structured-output-p (p/let [tc tool-calls-p
                                       s (:schema opts)]
                                 (if s
                                   (-> tc first :arguments)
                                   ;; Allow nil if no schema, consistent with potential absence of tool calls
                                   nil))]

       (when on-complete-fn
         (p/let [text text-p
                 usage usage-p
                 json all-events-p ; all-events-p is already the promise for all events
                 tool-calls tool-calls-p
                 structured-output structured-output-p]
           (on-complete-fn {:chunks events-channel ; Pass the channel itself
                            :text text
                            :usage usage
                            :json json
                            :tool-calls tool-calls
                            :structured-output structured-output})))

       {:chunks events-channel
        :text   text-p
        :usage  usage-p
        :json   all-events-p
        :tool-calls tool-calls-p
        :structured-output structured-output-p}))))

;; ──────────────────────────────────────────────────────────────
;; Conversational helper
;; ──────────────────────────────────────────────────────────────

(defn conversation [model]
  (let [history (atom [])
        on-complete-fn (fn [resp]
                         (let [assistant-message (cond-> {:role :assistant}
                                                   (:text resp) (assoc :content (:text resp))
                                                   (:tool-calls resp) (assoc :tool-calls (:tool-calls resp)))]
                           (when (or (:content assistant-message) (:tool-calls assistant-message))
                             (swap! history conj assistant-message))))]
    {:prompt (fn [content & [opts]]
               (let [user-message {:role :user :content content}
                     history-for-api @history ; Capture history *before* adding the new user message
                     _ (swap! history conj user-message) ; Add user message to local history
                     ;; Prepare options for the prompt call
                     prompt-opts (merge opts
                                        {:history history-for-api ; Pass history *before* current user message for the API call
                                         :on-complete (fn [resp]
                                                        ;; on-complete now receives a map of resolved values
                                                        (on-complete-fn resp) ; Updates history with assistant's response
                                                        (when-let [prev-on-complete (:on-complete opts)]
                                                          (prev-on-complete resp)))})]
                 (prompt model content prompt-opts)))
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
         response (prompt model-id content (merge {:schema schema} llm-opts))]
     ;; We need to dereference the promise for structured-output
     (p/let [structured-output (:structured-output response)]
       (if validate?
         (let [validation-result (m/validate f-input-schema structured-output)]
           (if validation-result
             (f structured-output)
             (throw (ex-info "Function arguments did not match schema"
                             {:schema f-input-schema
                              :args structured-output
                              :errors (m/explain f-input-schema structured-output)}))))
         (f structured-output))))))
