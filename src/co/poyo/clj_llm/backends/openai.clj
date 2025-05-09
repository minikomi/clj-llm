(ns co.poyo.clj-llm.backends.openai
  (:require [co.poyo.clj-llm.protocol :as proto]
            [co.poyo.clj-llm.stream :refer [process-sse]]
            [co.poyo.clj-llm.registry :as reg]
            [co.poyo.clj-llm.schema :as sch]
            [clojure.core.async :as async :refer [chan go <! >! <!! >!! close!]]
            [clojure.string :as str]
            [cheshire.core :as json]
            [malli.core :as m]
            )
  (:import [java.io InputStream InputStreamReader BufferedReader]
           [java.nio.charset StandardCharsets]))

;; ──────────────────────────────────────────────────────────────
;; OpenAI specific option schema
;; ──────────────────────────────────────────────────────────────
(def openai-opts-schema
  [:map
   [:response-format    {:optional true} [:enum "text" "json"]]
   [:attachments        {:optional true} [:sequential [:map
                                                     [:type keyword?]
                                                     [:url {:optional true} string?]
                                                     [:path {:optional true} string?]
                                                     [:data {:optional true} any?]]]]
   ])

;; ──────────────────────────────────────────────────────────────
;; SSE Stream Processing Helpers
;; ──────────────────────────────────────────────────────────────

(defn- parse-tool-arguments
  "Parses JSON string arguments within a tool call, applying specific cleaning."
  [tool-call]
  (if-let [args-str (get-in tool-call [:function :arguments])]
    (try
      (let [cleaned-args (-> args-str
                             (str/replace #"^(\{\"lo)" "{\"lo")
                             (str/replace #"(\"}$)" "\"}"))]
        (update-in tool-call [:function :arguments]
                   (fn [_] (json/parse-string cleaned-args true))))
      (catch Exception e
        (binding [*out* *err*]
          (println (str "WARNING: Failed to parse tool call arguments: " args-str ", Error: " (.getMessage e))))
        tool-call))
    tool-call))

(defn normalize-tool-calls
  "Convert raw SSE tool call delta events into a normalized EDN format."
  [raw-sse-events]
  (when (seq raw-sse-events)
    (let [by-index (reduce
                    (fn [acc sse-event]
                      (let [tool-call-deltas (get-in sse-event [:choices 0 :delta :tool_calls] [])]
                        (if (seq tool-call-deltas)
                          (reduce
                           (fn [a delta]
                             (let [idx (or (:index delta) 0)]
                               (update a idx (fnil conj []) delta)))
                           acc
                           tool-call-deltas)
                          acc)))
                    {}
                    raw-sse-events)
          merged-tool-calls (map
                             (fn [[_idx fragments]]
                               (reduce
                                (fn [acc fragment]
                                  (cond-> acc
                                    (:id fragment) (assoc :id (:id fragment))
                                    (:type fragment) (assoc :type (:type fragment))
                                    (get-in fragment [:function :name])
                                    (assoc-in [:function :name] (get-in fragment [:function :name]))
                                    (get-in fragment [:function :arguments])
                                    (update-in [:function :arguments] str (get-in fragment [:function :arguments]))))
                                {}
                                fragments))
                             (sort-by key by-index))]
      (mapv parse-tool-arguments merged-tool-calls))))

(defn- openai-transformer
  "Transform OpenAI event data into a structured format."
  [event-data]
  (when event-data
    (cond
      (:done event-data) {:type :done}
      (:error event-data) {:type :error, :message (:error event-data)}
      :else
      (let [choices (get event-data :choices [])
            first-choice (first choices)
            delta (get first-choice :delta {})
            content (get delta :content)
            tool-calls (get delta :tool_calls)
            finish-reason (get first-choice :finish_reason)
            usage (get event-data :usage)]
        (cond
          tool-calls
          {:type :tool-call, :tool-calls tool-calls, :raw-event event-data}

          (contains? delta :content)
          {:type :content, :content (or content "")}

          finish-reason
          {:type :finish, :reason finish-reason}

          usage
          {:type :usage, :usage usage}

          :else
          {:type :other, :data event-data})))))

(defn create-openai-stream-config
  "Creates a configuration for OpenAI stream processing."
  [chunks-chan metadata-atom]
  {:transform-fn openai-transformer
   :on-content (fn [event]
                 (case (:type event)
                   :content
                   (async/>!! chunks-chan (:content event))

                   :tool-call
                   (swap! metadata-atom update :tool-calls-events (fnil conj []) (:raw-event event))

                   :usage
                   (swap! metadata-atom assoc :usage (:usage event))

                   :finish
                   (swap! metadata-atom assoc :finish-reason (:reason event))

                   :other
                   (swap! metadata-atom update :raw-events (fnil conj []) (:data event))
                   nil))
   :on-error (fn [error-msg]
               (swap! metadata-atom assoc :error error-msg)
               (close! chunks-chan))
   :on-done (fn []
              (let [raw-tool-events (:tool-calls-events @metadata-atom)]
                (when (seq raw-tool-events)
                  (let [normalized (normalize-tool-calls raw-tool-events)]
                    (swap! metadata-atom assoc :normalized-tool-calls normalized))))
              (close! chunks-chan))})

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
        bytes (with-open [is (clojure.java.io/input-stream file)]
                (let [buf (byte-array (.length file))]
                  (.read is buf)
                  buf))
        encoded (java.util.Base64/getEncoder)
        base64-data (.encodeToString encoded bytes)]
    (str "data:" content-type ";base64," base64-data)))

(defn- process-attachment
  "Process a single attachment, converting file paths to data URLs if needed"
  [attachment]
  (cond
    (= :image (:type attachment))
    {:type "image_url"
     :image_url {:url (or (:url attachment)
                          (file-to-data-url (:path attachment)))}}
    :else
    (throw (ex-info "Unsupported attachment type" {:attachment attachment}))))

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

(defn- get-env
  [key]
  (System/getenv key))

(defn- get-api-key
  [opts]
  (or (:api-key opts)
      (get-env "OPENAI_API_KEY")
      (throw (ex-info "No OpenAI API key provided" {:missing-key :api-key}))))

(defn function-schema->openai-tool-spec
  [schema]
  (let [properties (m/properties schema)
        name (or (:name properties) "function")
        description (or (:description properties) "")]
    (println "PROP" schema (m/properties schema))
   {:type "function"
    :function {:name name
               :description description
               :parameters (sch/malli->json-schema schema)}}))

(defn- build-request-body
  "Build OpenAI chat completion request body"
  [model-id prompt-str opts]
  (println opts)
  (let [messages (into (or (:history opts) []) (make-messages prompt-str (:attachments opts)))
        stream-options (get opts :stream-options {:include_usage true})
        stop-val (:stop opts)
        tool-calls-values (when-let [schema (:schema opts)]
                              {:tools [(function-schema->openai-tool-spec (:schema opts))]
                               :tool_choice "auto"})]
    (println "TOOL CALLS" tool-calls-values)
    (cond-> {:model model-id
             :messages messages
             :stream true
             :stream_options stream-options}
      (:temperature opts)        (assoc :temperature (:temperature opts))
      (:top-p opts)              (assoc :top_p (:top-p opts))
      (:max-tokens opts)         (assoc :max_tokens (:max-tokens opts))
      (:frequency-penalty opts)  (assoc :frequency_penalty (:frequency-penalty opts))
      (:presence-penalty opts)   (assoc :presence_penalty (:presence-penalty opts))
      (:response-format opts)    (assoc :response_format (:response-format opts))
      (:seed opts)               (assoc :seed (:seed opts))
      tool-calls-values          (merge tool-calls-values)
      stop-val                   (assoc :stop (if (string? stop-val) [stop-val] stop-val)))))

(defn make-openai-request
  "Make a streaming request to OpenAI API with immediate processing"
  [model-id prompt-str opts]
  (let [api-key (get-api-key opts)
        request-body (build-request-body model-id prompt-str opts)
        metadata-atom (atom {:raw-events []
                             :tool-calls-events []
                             :normalized-tool-calls nil
                             :usage nil
                             :finish-reason nil
                             :error nil})
        chunks-chan (chan)]
    (future
      (try
        (let [http-client (-> (java.net.http.HttpClient/newBuilder)
                              (.version java.net.http.HttpClient$Version/HTTP_2)
                              (.connectTimeout (java.time.Duration/ofSeconds (or (:connect-timeout opts) 10)))
                              (.build))
              json-body (json/generate-string request-body)
              _ (println "JSON" json-body)
              request (-> (java.net.http.HttpRequest/newBuilder)
                          (.uri (java.net.URI/create (or (:api-endpoint opts) "https://api.openai.com/v1/chat/completions")))
                          (.timeout (java.time.Duration/ofSeconds (or (:request-timeout opts) 30)))
                          (.header "Content-Type" "application/json")
                          (.header "Authorization" (str "Bearer " api-key))
                          (.POST (java.net.http.HttpRequest$BodyPublishers/ofString json-body))
                          (.build))
              response (.send http-client request (java.net.http.HttpResponse$BodyHandlers/ofInputStream))]

          (if (not= 200 (.statusCode response))
            (let [error-body-is (.body response) ; This IS should be closed after reading
                  error-content (try
                                  (with-open [rdr (InputStreamReader. error-body-is StandardCharsets/UTF_8)]
                                    (slurp rdr))
                                  (catch Exception e
                                    (str "Failed to read error body: " (.getMessage e))))]
              (swap! metadata-atom assoc :error
                     (ex-info (str "OpenAI API request failed. Status: " (.statusCode response))
                              {:status (.statusCode response)
                               :model-id model-id
                               :response-body error-content}))
              (close! chunks-chan))
            ;; REVERTED CHANGE: Removed with-open here.
            ;; process-sse is now responsible for closing the input-stream it receives.
            (let [input-stream (.body response)]
              (process-sse input-stream (create-openai-stream-config chunks-chan metadata-atom)))))
        (catch Exception e
          (swap! metadata-atom assoc :error e)
          (close! chunks-chan))))
    {:channel chunks-chan
     :metadata metadata-atom}))

;; ──────────────────────────────────────────────────────────────
;; OpenAI Backend Implementation
;; ──────────────────────────────────────────────────────────────
(defrecord OpenAIBackend []
  proto/LLMBackend

  (-prompt [_this model-id prompt-str opts]
    (let [{:keys [channel]} (proto/-stream _this model-id prompt-str opts)]
      (loop [acc []]
        (if-let [chunk (<!! channel)]
          (recur (conj acc chunk))
          (str/join acc)))))

  (-stream [_this model-id prompt-str opts]
    (let [actual-model-id (if (keyword? model-id) (name model-id) (str model-id))]
      (make-openai-request actual-model-id prompt-str opts)))

  (-opts-schema [_this _model-id]
    openai-opts-schema)

  (-get-usage [_this _model-id metadata-atom]
    (:usage @metadata-atom))

  (-get-tool-calls [_this _model-id metadata-atom]
    (or (:normalized-tool-calls @metadata-atom)
        (normalize-tool-calls (:tool-calls-events @metadata-atom))))

  (-get-raw-json [_this _model-id metadata-atom]
    (let [md @metadata-atom
          tool-calls (or (:normalized-tool-calls md)
                         (normalize-tool-calls (:tool-calls-events md)))]
      (cond-> {}
        (seq (:raw-events md)) (assoc :events (:raw-events md))
        (:usage md) (assoc :usage (:usage md))
        (:finish-reason md) (assoc :finish_reason (:finish-reason md))
        (seq tool-calls) (assoc :tool_calls tool-calls)
        (:error md) (assoc :error (if (instance? Throwable (:error md))
                                     {:type (str (type (:error md)))
                                      :message (ex-message (:error md))
                                      :data (ex-data (:error md))}
                                     (:error md)))))))

;; ──────────────────────────────────────────────────────────────
;; Factory function
;; ──────────────────────────────────────────────────────────────
(defn create-backend []
  (->OpenAIBackend))

(defn register-backend! []
  (reg/register-backend! :openai (->OpenAIBackend)))
