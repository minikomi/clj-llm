(ns co.poyo.clj-llm.backends.openai
  "Platform-agnostic OpenAI backend implementation for clj-llm.
   Works in both JVM Clojure and Babashka environments.

   Usage from client code:
       (require '[co.poyo.clj-llm.backends.openai :refer [register-openai]])
       (register-openai)                                  ; one‑time call
       @(llm/prompt :openai/gpt-3.5-turbo \"Hello\")

   No API key is needed at registration time – it is looked up lazily when the
   backend actually hits the network."
  (:require [co.poyo.clj-llm.protocol :as proto]
            [co.poyo.clj-llm.registry :as reg]
            [co.poyo.clj-llm.stream :as stream]
            [co.poyo.clj-llm.http :refer [request]]
            [clojure.core.async :as async :refer [chan go-loop >! <! close!]]
            [malli.core :as m]
            [cheshire.core :as json]
            [clojure.string :as str]
            ))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private helpers                                                          ;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- env-key []
  (System/getenv "OPENAI_API_KEY"))

(defn- ensure-key
  "Gets an API key from options or environment.
   Prioritizes the :api-key in options if provided."
  [opts]
  (or (:api-key opts)
      (env-key)
      (throw (ex-info "OpenAI API key missing. Either set OPENAI_API_KEY environment variable or include :api-key in options" {}))))

(defn- extract-content
  "Extracts content from OpenAI API response chunk."
  [data]
  (if-let [f (get-in data [:choices 0 :finish_reason])]
    [:finish-reason f]
    [:content (get-in data [:choices 0 :delta :content])]))

(defn- build-messages
  "Builds the messages array for the OpenAI chat API.
   If there's a history, use it. Otherwise create a user message from the prompt."
  [prompt opts]
  (if-let [history (:history opts)]
    history
    [{:role "user" :content prompt}]))

(defn- build-request
  "Builds the request body for OpenAI API."
  [model-id prompt opts]
  (let [messages (build-messages prompt opts)
        base-request {:model model-id
                      :messages messages
                      :stream true}
        optional-keys [:temperature :top_p :max_tokens :frequency_penalty
                       :presence_penalty :stop :seed :logit_bias]]

    ;; Add any optional parameters that exist in the options
    (reduce (fn [req key]
              (if-let [val (get opts (keyword (str/replace (name key) "_" "-")))]
                (assoc req key val)
                req))
            base-request
            optional-keys)))

(defn- make-streaming-request
  "Makes a streaming request to the OpenAI API."
  [model-id prompt opts]
  (let [api-key (ensure-key opts)
        request-body (build-request model-id prompt opts)
        response (request
                  {:method :post
                   :url "https://api.openai.com/v1/chat/completions"
                   :headers {"Authorization" (str "Bearer " api-key)
                             "Content-Type" "application/json"}
                   :body (json/generate-string request-body)
                   :as :stream})]
    (stream/process-sse (:body response) extract-content)))

(defn- make-non-streaming-request
  "Makes a regular (non-streaming) request to the OpenAI API."
  [model-id prompt opts]
  (let [api-key (ensure-key opts)
        request-body (-> (build-request model-id prompt opts)
                         (dissoc :stream))
        response (request
                  {:method :post
                   :url "https://api.openai.com/v1/chat/completions"
                   :headers {"Authorization" (str "Bearer " api-key)
                             "Content-Type" "application/json"}
                   :body (json/generate-string request-body)})
        body (json/parse-string (:body response) true)]
    (get-in body [:choices 0 :message :content])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Option schema                                                            ;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def extra-opts-schema
  [:map
   [:json-object      {:optional true} boolean?]
   [:reasoning-effort {:optional true} [:enum :low :medium :high]]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Implementation record                                                    ;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord OpenAIBackend []
  proto/Backend
  (-opts-schema [_ _] extra-opts-schema)

  (-attachment-types [_ _] #{"image/png" "image/jpeg" "image/webp"})

  (-prompt [_ model-id prompt opts]
    (make-non-streaming-request model-id prompt opts))

  (-stream [_ model-id prompt opts]
    (make-streaming-request model-id prompt opts)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public registration helper                                               ;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn register-openai
  "Register :openai backend with llm.registry. Safe to call multiple times."
  []
  (reg/register-backend! :openai (->OpenAIBackend)))

;; Example usage in a comment block
(comment
    (require '[co.poyo.clj-llm.core :as llm])
  ;; Register the OpenAI backend
  (register-openai)

  ;; Create a synchronous request (API key from environment)
  @(llm/prompt :openai/gpt-4.1-mini "Tell me a joke about programming")

  ;; Create a streaming request
  (let [stream (llm/prompt> :openai/gpt-4.1-nano "Tell me a joke about programming")]
    (go-loop []
      (when-let [chunk (<! stream)]
        (print chunk)
        (flush)
        (recur))))

  ;; Create a streaming request with explicit API key
  (let [conv (llm/conversation :openai/gpt-4.1-nano)
        response-stream ((:prompt conv) "Tell me a joke about programming and farts")]
    ;; await loop
    (let [p (promise)]
      ;; Print each chunk as it arrives
     (go-loop []
       (if-let [chunk (<! response-stream)]
         (do (print chunk)
             (flush)
             (recur))
         (deliver p :done)))
     @p)
    (:history conv)
    )

  ;; Create a conversation
  (def conv (llm/conversation :openai/gpt-4.1-nano))
  )
