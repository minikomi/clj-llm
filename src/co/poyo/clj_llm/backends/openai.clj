(ns co.poyo.clj-llm.backends.openai
  (:require [org.httpkit.client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [malli.core :as m]
            [co.poyo.clj-llm.protocol :as proto]
            [co.poyo.clj-llm.schema   :as sch]
            [co.poyo.clj-llm.registry :as reg])
  (:import (java.util Base64)
           (java.io BufferedReader InputStreamReader)
           ))

;; ────────── helpers ──────────
(def openai-opts-schema
  [:map
   [:response-format {:optional true} [:enum "text" "json"]]
   [:attachments {:optional true}
    [:sequential
     [:map [:type keyword?]
      [:url  {:optional true} string?]
      [:path {:optional true} string?]
      [:data {:optional true} any?]]]]])

(defn get-api-key [opts]
  (or (:api-key opts)
      (System/getenv "OPENAI_API_KEY")
      (throw (ex-info "OPENAI_API_KEY missing" {}))))

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
;; ────────── backend ──────────
(defrecord OpenAIBackend []
  proto/LLMBackend
  (-raw-stream [_ model prompt opts]
    (let [api  (get-api-key opts)
          body (json/encode (build-body model prompt opts))
          {:keys [status body error] :as resp}
          @(http/request {:method :post
                          :url "https://api.openai.com/v1/chat/completions"
                          :headers {"Authorization" (str "Bearer " api)
                                    "Content-Type"  "application/json"
                                    "Accept"        "text/event-stream"}
                          :body body
                          :as :stream})
          _ (when (not= 200 status)
              (throw (ex-info "HTTP error" {:status status
                                            :body (apply str
                                                         (line-seq (BufferedReader.
                                                                    (InputStreamReader. body)))
                                                         )

                                            })))
          meta* (atom {})]
      {:stream body :close #(.close body) :metadata meta*}))
  (-opts-schema [_ _]
    openai-opts-schema))

(defn register-backend! []
  (reg/register-backend! :openai (->OpenAIBackend)))
