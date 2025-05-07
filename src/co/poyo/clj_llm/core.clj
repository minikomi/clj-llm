(ns co.poyo.clj-llm.core
  (:require [co.poyo.clj-llm.registry :as reg]
            [co.poyo.clj-llm.protocol :as proto]
            [co.poyo.clj-llm.stream :as stream]
            [clojure.core.async :as async :refer [chan go go-loop <! >! close! <!]]
            [malli.core :as m]
            [malli.util :as mu]
            [malli.transform :as mt]
            [cheshire.core :as json]))


(def ^:private reserved-keys #{:schema :raw? :history})

(def json->clj (mt/transformer))

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
   [:logit-bias        {:optional true} [:map-of int? int?]]])

(defn- split-model-key [kw]
  (when-not (keyword? kw)
    (throw (ex-info "Model id must be a qualified keyword" {:value kw})))
  [(keyword (namespace kw)) (name kw)])

(defn- coerce-json [schema txt raw?]
  (if (and schema (not raw?))
    (m/decode schema (json/parse-string txt true) json->clj)
    txt))

(defn- validate-extra-opts [impl model-id opts]
  (let [extra (proto/-opts-schema impl model-id)
        schema (mu/merge base-opts-schema extra)]
    (m/decode schema opts (mt/default-value-transformer))))

(defn prompt
  "Lazily execute and return a delay whose deref yields the coerced result.
  Accepts standard options plus backend‑specific ones validated via Malli."
  [model prompt-str & [opts]]
  (let [[backend-key model-id] (split-model-key model)
        impl (or (reg/backend-impl backend-key)
                 (throw (ex-info "Unknown backend" {:backend backend-key})))
        {:keys [schema raw?] :as opts*} opts
        backend-opts (validate-extra-opts impl model-id (apply dissoc opts* reserved-keys))]
    (delay (coerce-json schema (proto/-prompt impl model-id prompt-str backend-opts) raw?))))

(defn prompt>
  "Streaming version returning a core.async channel that emits coerced chunks."
  [model prompt-str & [opts]]
  (let [[backend-key model-id] (split-model-key model)
        impl (or (reg/backend-impl backend-key)
                 (throw (ex-info "Unknown backend" {:backend backend-key})))
        {:keys [schema raw?] :as opts*} opts
        backend-opts (validate-extra-opts impl model-id (apply dissoc opts* reserved-keys))
        src (proto/-stream impl model-id prompt-str backend-opts)
        out (chan)]

    ;; Process the source channel and coerce each chunk
    (go-loop []
      (if-let [chunk (<! src)]
        (do
          (>! out (coerce-json schema chunk raw?))
          (recur))
        (close! out)))

    out))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Conversations                                                            ;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn conversation [model]
  (let [history (atom [])]
    {:prompt (fn [q & [opts]]
               (swap! history conj {:role :user :content q})
               (let [stream (prompt> model q (assoc opts :history @history))
                     outside-stream (chan)
                     response-content (atom "")]

                 ;; Process the stream to capture the full response
                 (go-loop []
                   (if-let [chunk (<! stream)]
                     (do
                       (swap! response-content str chunk)
                       (>! outside-stream chunk)
                       (recur))
                     ;; When stream is done, update the history
                     (do
                       (swap! history conj
                             {:role :assistant
                              :content @response-content})
                       (close! outside-stream))))

                 ;; Return the stream for the caller to consume
                 outside-stream))
     :history history}))

(defn collect-stream
  "Utility function to collect all values from a stream into a single string."
  [stream]
  (stream/collect-channel stream))
