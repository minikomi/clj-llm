(ns co.poyo.clj-llm.backends.openai
  (:require [cheshire.core           :as json]
            [clojure.core.async      :as async :refer [chan pipeline close!]]
            [co.poyo.clj-llm.net     :as net]          ;; ← new wrapper
            [co.poyo.clj-llm.sse     :as sse]
            [co.poyo.clj-llm.protocol :as proto]
            [co.poyo.clj-llm.schema   :as sch]
            [co.poyo.clj-llm.registry :as reg]
            [malli.core :as m]
            [clojure.string :as str])
  (:import (java.util Base64)))

(def ^:private ^:const api-url "https://api.openai.com/v1/chat/completions")
(def ^:private ^:const env-api-key-name "OPENAI_API_KEY")

;; ─────────── API Key Handling ──────────

(defn get-api-key [opts]
  (or (:api-key opts)
      (System/getenv env-api-key-name)
      (throw (ex-info "API key not provided" {:opts opts}))))

;; ────────── schema opts ──────────

(def openai-opts-schema
  [:map
   [:response-format {:optional true} [:enum "text" "json"]]
   [:attachments {:optional true}
    [:sequential
     [:map [:type keyword?]
      [:url  {:optional true} string?]
      [:path {:optional true} string?]
      [:data {:optional true} any?]]]]])


;; ────────── message processing ──────────

(defn file->data-url [p]
  (let [mime (cond
               (str/ends-with? p ".png")  "image/png"
               (str/ends-with? p ".jpg")  "image/jpeg"
               (str/ends-with? p ".jpeg") "image/jpeg"
               (str/ends-with? p ".gif")  "image/gif"
               (str/ends-with? p ".webp") "image/webp"
               :else "application/octet-stream")
        bytes (slurp p :encoding nil)]
    (str "data:" mime ";base64," (.encodeToString (Base64/getEncoder) bytes))))

(defn process-attachment [{:keys [type url path]}]
  (case type
    :image {:type "image_url"
            :image_url {:url (or url (file->data-url path))}}
    (throw (ex-info "unsupported attachment" {}))))

(defn make-messages [prompt atts]
  [{:role "user"
    :content (into [{:type "text" :text prompt}]
                   (map process-attachment atts))}])

(defn schema->tool-spec [schema]
  (let [{:keys [name description]} (m/properties schema)]
    {:type "function"
     :function {:name (or name "function")
                :description (or description "")
                :parameters (sch/malli->json-schema schema)}}))

(defn build-body [model prompt {:keys [attachments history schema] :as opts}]
  (let [msg (make-messages prompt attachments)
        tools (when schema
                (let [spec (schema->tool-spec schema)]
                  {:tools [spec] :tool_choice "required"}))]
    (cond-> {:model model
             :stream true
             :stream_options {:include_usage true}
             :messages (into (or history []) msg)}
      (:temperature opts)        (assoc :temperature (:temperature opts))
      (:top-p opts)              (assoc :top_p (:top-p opts))
      (:max-tokens opts)         (assoc :max_tokens (:max-tokens opts))
      (:frequency-penalty opts)  (assoc :frequency_penalty (:frequency-penalty opts))
      (:presence-penalty opts)   (assoc :presence_penalty (:presence-penalty opts))
      (:response-format opts)    (assoc :response_format (:response-format opts))
      (:seed opts)               (assoc :seed (:seed opts))
      tools                      (merge tools)
      (:stop opts)               (assoc :stop
                                        (let [s (:stop opts)]
                                          (if (string? s) [s] s))))))

;; ───────── SSE Processing ──────────

(defn ^:private filter-choices-or-usage           ;; keep both deltas and usage
  []
  (filter #(or (:choices %) (:usage %))))

(defn ^:private openai-json->events []            ;; canonical event maps
  (mapcat
   (fn [m]
     (if-let [u (:usage m)]
       [{:type :usage
         :prompt     (:prompt_tokens u)
         :completion (:completion_tokens u)
         :total      (:total_tokens u)}]

       (let [delta (get-in m [:choices 0 :delta])]
         (cond-> []
           (:content delta)
           (conj {:type :content :content (:content delta)})

           (:tool_calls delta)
           (into (map (fn [{:keys [index id function]}]
                        {:type :tool-call-delta
                         :index index
                         :id    id
                         :name  (:name function)
                         :arguments (:arguments function)}))
                 (:tool_calls delta))))))))

;; ────────── Backend Registration ──────────

;; Usage


(defrecord OpenAIBackend []
  proto/LLMBackend
  (-raw-stream [_ model prompt opts]
    (let [api-key (or (:api-key opts) (System/getenv "OPENAI_API_KEY"))
          headers {"Authorization" (str "Bearer " api-key)
                   "Content-Type"  "application/json"
                   "Accept"        "text/event-stream"}
          body-str (json/encode (build-body model prompt opts))
          out-ch   (chan 64)]
      (net/post-stream
       api-url headers body-str
       (fn [{:keys [status body error]}]
         (when error (throw error))
         (if (= 200 status)
           (pipeline 32
                     out-ch
                     (comp sse/sse->json-xf
                           (filter-choices-or-usage)
                           (openai-json->events))
                     (sse/input-stream->line-chan body))
           (throw (ex-info "HTTP error" {:status status})))))
      {:channel out-ch})))

;; ——— register for user code ——————————————
(defn register []
  (reg/register-backend! :openai (->OpenAIBackend)))
