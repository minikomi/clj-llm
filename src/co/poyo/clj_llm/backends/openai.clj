(ns co.poyo.clj-llm.backends.openai
  (:require [cheshire.core           :as json]
            [clojure.core.async      :as async :refer [chan pipeline close!]]
            [co.poyo.clj-llm.net     :as net]
            [co.poyo.clj-llm.sse     :as sse]
            [co.poyo.clj-llm.protocol :as proto]
            [co.poyo.clj-llm.schema   :as sch]
            [co.poyo.clj-llm.registry :as reg]
            [malli.core :as m]
            [clojure.string :as str])
  (:import (java.util Base64)))

(def ^:private ^:const api-url "https://api.openai.com/v1/chat/completions")

;; ────────── schema opts ──────────

(def openai-opts-schema
  [:map
   [:response-format {:optional true} [:enum "text" "json"]]
   [:attachments {:optional true}
    [:sequential
     [:map
      [:type keyword?]
      [:url  {:optional true} string?]
      [:path {:optional true} string?]
      ]]]])

;; ────────── message processing ──────────

;; Simplified MIME type lookup
(def ^:private file-extensions->mime
  {".png"  "image/png"
   ".jpg"  "image/jpeg"
   ".jpeg" "image/jpeg"
   ".gif"  "image/gif"
   ".webp" "image/webp"})

(defn- file->data-url [path]
  (let [ext  (some #(when (str/ends-with? path %) %) (keys file-extensions->mime))
        mime (get file-extensions->mime ext "application/octet-stream")
        bytes (java.nio.file.Files/readAllBytes (java.nio.file.Paths/get path (make-array String 0)))]
    (str "data:" mime ";base64," (.encodeToString (Base64/getEncoder) bytes))))

(defn- data->data-url [{:keys [bytes format width height]}]
    (let [mime (or (#{"image/png" "image/jpeg" "image/gif" "image/webp"} format)
                     "application/octet-stream")
            base64-bytes (.encodeToString (Base64/getEncoder) bytes)]
        (str "data:" mime ";base64," base64-bytes)))

(defn- process-attachment [{:keys [type url path data]}]
  (case type
    :image {:type "image_url"
            :image_url {:url (cond
                                    url url
                                    path (file->data-url path)
                                    data (data->data-url data))}}
    (throw (ex-info "Unsupported attachment type" {:type type}))))

(defn- make-messages [prompt attachments system-prompt]
  (let [content
        (if (seq attachments)
          ;; hybrid array: first text, then images
          (into [{:type "text" :text prompt}]
                (map process-attachment attachments))
          ;; simple string when no images
          prompt)]
    (cond-> []
      system-prompt (conj {:role "system" :content system-prompt})
      true          (conj {:role "user"   :content content}))))

(defn- schema->tool-spec [schema]
  (let [{:keys [name description]} (m/properties schema)]
    {:type "function"
     :function {:name (or name "function")
                :description (or description "")
                :parameters (sch/malli->json-schema schema)}}))

;; Simplified body building with cleaner parameter mapping
(defn- build-body [model prompt {:keys [attachments history schema system-prompt] :as opts}]
  (let [messages (make-messages prompt attachments system-prompt)
        tools (when schema {:tools [(schema->tool-spec schema)]
                            :tool_choice "required"})
        ;; Parameter mapping - cleaner than multiple cond-> branches
        param-map {:temperature     :temperature
                   :top-p           :top_p
                   :max-tokens      :max_tokens
                   :frequency-penalty :frequency_penalty
                   :presence-penalty  :presence_penalty
                   :response-format   :response_format
                   :seed              :seed}]
    (cond-> {:model model
             :stream true
             :stream_options {:include_usage true}
             :messages (into (or history []) messages)}
      tools (merge tools)
      (:stop opts) (assoc :stop (let [s (:stop opts)]
                                  (if (string? s) [s] s)))
      ;; Apply parameter mappings
      true (merge (into {} (for [[k v] param-map
                                 :when (contains? opts k)]
                             [v (get opts k)]))))))

;; ───────── SSE Processing - Combined transducer ──────────

(def ^:private openai-events-xf
  (comp
   ;; Filter for relevant data
   (filter #(or (:choices %) (:usage %)))
   ;; Transform to canonical events
   (mapcat
    (fn [m]
      (if-let [u (:usage m)]
        ;; Usage event
        [{:type :usage
          :prompt     (:prompt_tokens u)
          :completion (:completion_tokens u)
          :total      (:total_tokens u)}]
        ;; Content/tool call events
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
                  (:tool_calls delta)))))))))

;; ────────── Backend Implementation ──────────

(defrecord OpenAIBackend []
  proto/LLMBackend

  (-raw-stream [_ model prompt opts]
    (let [api-key (or (:api-key opts)
                      (System/getenv "OPENAI_API_KEY")
                      (throw (ex-info "OpenAI API key not found"
                                     {:env-var "OPENAI_API_KEY"})))
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
                     (comp sse/sse->json-xf openai-events-xf)
                     (sse/input-stream->line-chan body))
           (throw (ex-info "OpenAI API error" {:status status})))))
      {:channel out-ch}))

  (-opts-schema [_ _]
    openai-opts-schema))

;; ——— Registration ——————————————
(defn register-backend! []
  (reg/register-backend! :openai (->OpenAIBackend)))
