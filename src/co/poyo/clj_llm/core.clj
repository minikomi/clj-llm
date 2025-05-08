(ns co.poyo.clj-llm.core
  (:require
   [co.poyo.clj-llm.registry :as reg]
   [co.poyo.clj-llm.protocol :as proto]
   [clojure.core.async :as a :refer [<!!]]
   [malli.core :as m]
   [malli.util :as mu]
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
   [:logit-bias        {:optional true} [:map-of int? int?]]])

(def ^:private reserved-keys #{:schema :raw? :history})

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

(defn prompt
  "Execute a prompt against an LLM model and return a structured response.

   Returns a map:
   {:chunks    <lazy-seq of raw strings>
    :text      <deref-able — delivers full string after chunks consumed>
    :usage     <delay — blocks until text delivered, returns usage map>
    :json      <delay — blocks until text delivered, returns raw JSON>
    :tool-calls <delay — blocks until text delivered, returns tool calls in EDN>}

   Realising `:text`, `:usage`, `:json`, or `:tool-calls` forces consumption
   of the stream only *once*; `:chunks` can be consumed incrementally for live streaming."
  [model-id prompt-str & {:as opts}]
  (let [[backend-id model-name] (split-model-key model-id)
        impl (reg/get-backend backend-id)

        ;; Validate options against schema
        validated-opts (validate-opts impl model-id
                                      (apply dissoc opts reserved-keys))

        ;; Get the streaming response
        {:keys [channel metadata]} (proto/-stream impl model-name prompt-str validated-opts)

        ;; Create a promise for the full text
        text-promise (promise)

        ;; Create an atom to track whether we've consumed the stream
        consumed? (atom false)

        ;; Function to ensure we only consume the stream once
        ensure-consumed (fn []
                          (when (compare-and-set! consumed? false true)
                            (let [full-text (loop [result ""]
                                              (if-let [chunk (<!! channel)]
                                                (recur (str result chunk))
                                                result))]
                              (deliver text-promise full-text)))
                          @text-promise)

        ;; Create a lazy sequence of chunks using a named function
        chunks-fn (fn chunks-fn []
                    (when-not @consumed?
                      (if-let [chunk (<!! channel)]
                        (cons chunk (chunks-fn))
                        (do
                          (deliver text-promise "")
                          (reset! consumed? true)
                          nil))))
        chunks-seq (lazy-seq (chunks-fn))

        ;; Create a deref-able text value that forces consumption
        text-deref (reify
                     clojure.lang.IDeref
                     (deref [_] (ensure-consumed)))

        ;; Create delays for usage, raw JSON, and tool calls
        usage-delay (delay
                      (ensure-consumed)
                      (proto/-get-usage impl model-name metadata))

        json-delay (delay
                     (ensure-consumed)
                     (proto/-get-raw-json impl model-name metadata))

        tool-calls-delay (delay
                           (ensure-consumed)
                           (proto/-get-tool-calls impl model-name metadata))]

    ;; Return the structured response
    {:chunks chunks-seq
     :text text-deref
     :usage usage-delay
     :json json-delay
     :tool-calls tool-calls-delay}))

;; ──────────────────────────────────────────────────────────────
;; Conversational helper
;; ──────────────────────────────────────────────────────────────

(defn conversation [model]
  (let [history (atom [])]
    {:prompt (fn [q & [opts]]
               (swap! history conj {:role :user :content q})
               (let [resp (prompt model q (assoc opts :history @history))]
                 (future (swap! history conj
                                {:role :assistant
                                 :content (deref (:text resp))}))
                 resp))
     :history history}))
