(ns co.poyo.clj-llm.backends.openai
  (:require [co.poyo.clj-llm.protocol :as proto]
            [co.poyo.clj-llm.stream :refer [process-sse extract-sse-data]]
            [co.poyo.clj-llm.registry :as reg]
            [clojure.core.async :as async :refer [chan go <! >! close!]]
            [clojure.string :as str]
            [org.httpkit.client :as http]
            [cheshire.core :as json]
            [malli.core :as m]
            [malli.registry :as mr]
            [malli.instrument :as mi]
            [malli.util :as mu]
            )
  )


;; ──────────────────────────────────────────────────────────────
;; OpenAI specific option schema
;; ──────────────────────────────────────────────────────────────
(def openai-opts-schema
  [:map
   [:response-format   {:optional true} [:enum "text" "json"]]
   [:tools             {:optional true} [:sequential map?]]
   [:tool-choice       {:optional true} [:or keyword? map?]]
   [:functions         {:optional true} [:sequential map?]]
   [:function-call     {:optional true} [:or string? map?]]
   [:attachments       {:optional true} [:sequential [:map
                                                      [:type keyword?]
                                                      [:url {:optional true} string?]
                                                      [:path {:optional true} string?]
                                                      [:data {:optional true} any?]]]]
   [:stream-options    {:optional true} [:map
                                         [:include_usage {:optional true} boolean?]]]])

;; ──────────────────────────────────────────────────────────────
;; SSE Stream Processing
;; ──────────────────────────────────────────────────────────────

(defn normalize-tool-calls
  "Convert raw tool call events into a normalized EDN format"
  [events]
  (when (seq events)
    (let [;; Extract all tool call deltas and group by index
          by-index (reduce
                    (fn [acc event]
                      (let [tool-calls (get-in event [:choices 0 :delta :tool_calls] [])]
                        (if (seq tool-calls)
                          (reduce
                           (fn [a tool-call]
                             (let [idx (or (:index tool-call) 0)]
                               (update a idx (fnil conj []) tool-call)))
                           acc
                           tool-calls)
                          acc)))
                    {}
                    events)

          ;; Merge fragments for each tool call
          merged-tool-calls (map
                             (fn [[idx fragments]]
                               (reduce
                                (fn [acc fragment]
                                  (cond-> acc
                                    (:id fragment) (assoc :id (:id fragment))
                                    (:type fragment) (assoc :type (:type fragment))
                                    (:index fragment) (assoc :index (:index fragment))
                                    (get-in fragment [:function :name])
                                    (assoc-in [:function :name] (get-in fragment [:function :name]))
                                    (get-in fragment [:function :arguments])
                                    (update-in [:function :arguments] str (get-in fragment [:function :arguments]))))
                                {}
                                fragments))
                             by-index)]

      ;; Parse arguments JSON strings to EDN
      (mapv (fn [tool-call]
              (if-let [args-str (get-in tool-call [:function :arguments])]
                (try
                  ;; Parse the JSON arguments string
                  (let [cleaned-args (-> args-str
                                         (str/replace #"^(\{\"lo)" "{\"lo")
                                         (str/replace #"(\"}$)" "\"}"))]
                    (update-in tool-call [:function :arguments]
                               (fn [_] (json/parse-string cleaned-args true))))
                  (catch Exception _
                    ;; If parsing fails, keep the original string
                    tool-call))
                tool-call))
            merged-tool-calls))))

(def openai-transformer
  "Transform OpenAI SSE events"
  (fn [event]
    (when event
      (cond
        (:done event) {:type :done}
        (:error event) {:type :error, :message (:error event)}
        :else
        (let [choices (get event :choices [])
              first-choice (first choices)
              delta (get first-choice :delta {})
              content (get delta :content "")
              tool-calls (get delta :tool_calls)
              finish-reason (get first-choice :finish-reason)
              usage (get event :usage)]
          (cond
            ;; Tool calls take priority
            tool-calls
            {:type :tool-call, :tool-calls tool-calls, :raw-event event}

            (not (str/blank? content))
            {:type :content, :content content}

            finish-reason
            {:type :finish, :reason finish-reason}

            usage
            {:type :usage, :usage usage}

            :else
            {:type :other, :data event}))))))

(defn process-openai-stream
  "Process an OpenAI SSE stream with a channel for chunks and a metadata atom"
  [input-stream metadata-atom]
  (let [chunks-chan (chan)
        tool-calls-by-index (atom {})]

    ;; Process the stream
    (process-sse input-stream
                 {:extract-fn extract-sse-data
                  :transform-fn openai-transformer
                  :on-content (fn [result]
                                (case (:type result)
                                  :content
                                  (async/>!! chunks-chan (:content result))

                                  :tool-call
                                  (let [raw-event (:raw-event result)
                                        tool-calls (get-in raw-event [:choices 0 :delta :tool_calls])]

                                    ;; Store the raw event for metadata
                                    (swap! metadata-atom update :tool-calls-events
                                           (fnil conj []) raw-event)

                                    ;; Update the tool-calls-by-index atom for each tool call fragment
                                    (doseq [tool-call tool-calls]
                                      (let [idx (or (:index tool-call) 0)]
                                        (swap! tool-calls-by-index update idx
                                               (fnil conj []) tool-call))))

                                  :usage
                                  (swap! metadata-atom assoc :usage (:usage result))

                                  :finish
                                  (swap! metadata-atom assoc :finish-reason (:reason result))

                                  :other
                                  (swap! metadata-atom update :raw-events conj (:data result))

                                  nil))

                  :on-error (fn [error-msg]
                              (swap! metadata-atom assoc :error error-msg)
                              (close! chunks-chan))

                  :on-done (fn []
                             ;; Now that we're done, add the consolidated tool calls to the text
                             (let [normalized-tool-calls (normalize-tool-calls
                                                          (:tool-calls-events @metadata-atom))]

                               ;; Add the tool calls as JSON to the text channel
                               (when (seq normalized-tool-calls)
                                 (let [tool-calls-json (json/generate-string
                                                        {:tool_calls normalized-tool-calls}
                                                        {:pretty true})]
                                   (async/>!! chunks-chan tool-calls-json))))

                             ;; Close the channel when done
                             (close! chunks-chan))})
    chunks-chan))



;; ──────────────────────────────────────────────────────────────
;; OpenAI API Requests
;; ──────────────────────────────────────────────────────────────


(defn- file-to-data-url
  "Convert file content to a data URL"
  [file-path]
  (let [file (clojure.java.io/file file-path)
        content-type (cond
                       (str/ends-with? file-path ".png") "image/png"
                       (str/ends-with? file-path ".jpg") "image/jpeg"
                       (str/ends-with? file-path ".jpeg") "image/jpeg"
                       (str/ends-with? file-path ".gif") "image/gif"
                       (str/ends-with? file-path ".webp") "image/webp"
                       :else "application/octet-stream")
        bytes (clojure.java.io/input-stream file)
        encoded (java.util.Base64/getEncoder)
        base64-data (.encodeToString encoded (byte-array bytes))]
    (str "data:" content-type ";base64," base64-data)))

(defn- process-attachment
  "Process a single attachment, converting file paths to data URLs if needed"
  [attachment]
  (cond
    ;; image
    (= :image (:type attachment))
    {:type :image_url
     :image_url {:url (or (:url attachment)
                     (file-to-data-url (:path attachment)))}}

    :else
    (throw (ex-info "Unsupported attachment type" {:attachment attachment}))
    ))

(defn- make-messages
  "Convert prompt string to messages format"
  [prompt-str attachments]
  (let [base-content [{:type "text" :text prompt-str}]
        full-content
        (reduce
         (fn [acc attachment]
              (let [processed-attachment (process-attachment attachment)]
                (conj acc processed-attachment)))
         base-content
         attachments)]
    [{:role "user"
      :content full-content}]))

(defn- get-api-key [opts]
  (or (:api-key opts)
      (System/getenv "OPENAI_API_KEY")
      (throw (ex-info "No OpenAI API key provided" {:missing-key :api-key}))))

(defn- build-request-body
  "Build OpenAI chat completion request body"
  [model-id prompt-str opts]
  (let [messages (make-messages prompt-str (:attachments opts))
        stream-options (get opts :stream-options {:include_usage true})
        ]
    (println messages)
    (cond-> {:model model-id
             :messages messages
             :stream true
             :stream_options stream-options}

      (:temperature opts)       (assoc :temperature (:temperature opts))
      (:top-p opts)             (assoc :top_p (:top-p opts))
      (:max-tokens opts)        (assoc :max_tokens (:max-tokens opts))
      (:frequency-penalty opts) (assoc :frequency_penalty (:frequency-penalty opts))
      (:presence-penalty opts)  (assoc :presence_penalty (:presence-penalty opts))
      (:response-format opts)   (assoc :response_format (:response-format opts))
      (:seed opts)              (assoc :seed (:seed opts))
      (:tools opts)             (assoc :tools (:tools opts))
      (:tool-choice opts)       (assoc :tool_choice (:tool-choice opts))
      (:functions opts)         (assoc :functions (:functions opts))
      (:function-call opts)     (assoc :function_call (:function-call opts))
      (:logit-bias opts)        (assoc :logit_bias (:logit-bias opts))
      (:stop opts)              (assoc :stop [(:stop opts)]))))

(require '[clojure.pprint :refer [pprint]])

(defn- make-openai-request
  "Make a streaming request to OpenAI API"
  [model-id prompt-str opts]
  (let [api-key (get-api-key opts)
        request-body (build-request-body model-id prompt-str opts)
        metadata-atom (atom {:raw-events []})
        response @(http/request
                   {:method :post
                    :url "https://api.openai.com/v1/chat/completions"
                    :headers {"Content-Type" "application/json"
                              "Authorization" (str "Bearer " api-key)}
                    :body (json/generate-string request-body)
                    :as :stream})
        {:keys [status body error]} response]

    (when (or error (not= status 200))
      (throw (ex-info "OpenAI API request failed"
                      {:status status :error error :model-id model-id :response response})))

    {:channel (process-openai-stream body metadata-atom)
     :metadata metadata-atom}))

;; ──────────────────────────────────────────────────────────────
;; OpenAI Backend Implementation
;; ──────────────────────────────────────────────────────────────
(defrecord OpenAIBackend []
  proto/LLMBackend

  (-prompt [this model-id prompt-str opts]
    (let [{:keys [channel metadata]} (proto/-stream this model-id prompt-str opts)]
      (loop [result ""]
        (if-let [chunk (async/<!! channel)]
          (recur (str result chunk))
          result))))

  (-stream [this model-id prompt-str opts]
    (make-openai-request model-id prompt-str opts))

  (-opts-schema [this model-id]
    openai-opts-schema)

  (-get-usage [this model-id metadata-atom]
    (:usage @metadata-atom))

  (-get-tool-calls [this model-id metadata-atom]
    (let [{:keys [tool-calls-events]} @metadata-atom]
      (normalize-tool-calls tool-calls-events)))

  (-get-raw-json [this model-id metadata-atom]
    (let [{:keys [raw-events usage finish-reason tool-calls-events error]} @metadata-atom
          tool-calls (normalize-tool-calls tool-calls-events)]
      (cond-> {}
        (seq raw-events) (assoc :events raw-events)
        usage (assoc :usage usage)
        finish-reason (assoc :finish_reason finish-reason)
        (seq tool-calls) (assoc :tool_calls tool-calls)
        error (assoc :error error)))))

;; ──────────────────────────────────────────────────────────────
;; Factory function
;; ──────────────────────────────────────────────────────────────
(defn create-backend []
  (->OpenAIBackend))

(defn register-backend! []
  (reg/register-backend! :openai (->OpenAIBackend)))
