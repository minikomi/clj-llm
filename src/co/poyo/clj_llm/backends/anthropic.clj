(ns co.poyo.clj-llm.backends.anthropic
  (:require [co.poyo.clj-llm.protocol :as proto]
            [co.poyo.clj-llm.stream :refer [process-sse]]
            [co.poyo.clj-llm.registry :as reg]
            [clojure.core.async :as async :refer [chan go <! >! <!! >!! close!]]
            [clojure.string :as str]
            [cheshire.core :as json])
  (:import [java.io InputStream InputStreamReader BufferedReader]
           [java.nio.charset StandardCharsets]))

;; ──────────────────────────────────────────────────────────────
;; Anthropic specific option schema
;; ──────────────────────────────────────────────────────────────
(def anthropic-opts-schema
  [:map
   [:max-tokens        {:optional true} pos-int?]
   [:temperature       {:optional true} [:and number? [:>= 0] [:<= 1]]]
   [:top-k             {:optional true} pos-int?]
   [:top-p             {:optional true} [:and number? [:>= 0] [:<= 1]]]
   [:stop-sequences    {:optional true} [:sequential string?]]
   [:system            {:optional true} string?]
   [:tools              {:optional true} [:sequential map?]]
   [:tool-choice        {:optional true} [:or keyword? map?]]
   [:response-format    {:optional true} [:enum "text" "json"]]
   [:attachments        {:optional true} [:sequential [:map
                                                     [:type keyword?]
                                                     [:url {:optional true} string?]
                                                     [:path {:optional true} string?]
                                                     [:data {:optional true} any?]]]]
   [:stream-options     {:optional true} [:map
                                          [:include_usage {:optional true} boolean?]]]])

;; ──────────────────────────────────────────────────────────────
;; SSE Stream Processing Helpers
;; ──────────────────────────────────────────────────────────────

(defn- parse-tool-arguments
  "Parses JSON string arguments within a tool call, applying specific cleaning."
  [tool-call]
  (if-let [args-str (get-in tool-call [:input])]
    (try
      (let [cleaned-args (-> args-str
                             (str/replace #"^(\{\"lo)" "{\"lo")
                             (str/replace #"(\"}$)" "\"}"))]
        (update-in tool-call [:input]
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
                      (let [tool-calls (get-in sse-event [:delta :tool_calls] [])]
                        (if (seq tool-calls)
                          (reduce
                           (fn [a delta]
                             (let [idx (or (:index delta) 0)]
                               (update a idx (fnil conj []) delta)))
                           acc
                           tool-calls)
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
                                    (:name fragment) (assoc :name (:name fragment))
                                    (:input fragment) (update :input str (:input fragment))))
                                {}
                                fragments))
                             (sort-by key by-index))]
      (mapv parse-tool-arguments merged-tool-calls))))

(defn- anthropic-transformer
  "Transform Anthropic event data into a structured format."
  [event-data]
  (when event-data
    (cond
      (:done event-data) {:type :done}
      (:error event-data) {:type :error, :message (:error event-data)}
      :else
      (let [delta (get event-data :delta {})
            content-block (get delta :text)
            tool-calls (get delta :tool_calls)
            stop-reason (get event-data :stop_reason)
            usage (get event-data :usage)]
        (cond
          tool-calls
          {:type :tool-call, :tool-calls tool-calls, :raw-event event-data}

          (contains? delta :text)
          {:type :content, :content (or content-block "")}

          stop-reason
          {:type :finish, :reason stop-reason}

          usage
          {:type :usage, :usage usage}

          :else
          {:type :other, :data event-data})))))

(defn create-anthropic-stream-config
  "Creates a configuration for Anthropic stream processing."
  [chunks-chan metadata-atom]
  {:transform-fn anthropic-transformer
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
;; Anthropic API Requests
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
    {:type "image"
     :source {:type "base64"
              :media_type (cond
                            (str/ends-with? (:path attachment) ".png") "image/png"
                            (str/ends-with? (:path attachment) ".jpg") "image/jpeg"
                            (str/ends-with? (:path attachment) ".jpeg") "image/jpeg"
                            (str/ends-with? (:path attachment) ".gif") "image/gif"
                            (str/ends-with? (:path attachment) ".webp") "image/webp"
                            :else "image/jpeg")
              :data (-> (or (:url attachment)
                            (file-to-data-url (:path attachment)))
                        (str/replace #"^data:image/[^;]+;base64," ""))}}
    :else
    (throw (ex-info "Unsupported attachment type" {:attachment attachment}))))

(defn- make-anthropic-messages
  "Convert prompt string and history to Anthropic messages format"
  [prompt-str history attachments]
  (let [user-content (if (seq attachments)
                       (into [{:type "text" :text prompt-str}]
                             (map process-attachment attachments))
                       prompt-str)
        last-message {:role "user"
                      :content user-content}]
    (if (seq history)
      (into history [last-message])
      [last-message])))

(defn- get-env
  [key]
  (System/getenv key))

(defn- get-api-key
  [opts]
  (or (:api-key opts)
      (get-env "ANTHROPIC_API_KEY")
      (throw (ex-info "No Anthropic API key provided" {:missing-key :api-key}))))

(def default-max-tokens 1024)

(defn- build-request-body
  "Build Anthropic chat completion request body"
  [model-id prompt-str opts]
  (let [messages (make-anthropic-messages prompt-str (:history opts) (:attachments opts))
        system (get opts :system)
        stop-sequences (get opts :stop-sequences)]
    (cond-> {:model model-id
             :messages messages
             :max_tokens (or (:max-tokens opts) default-max-tokens)
             :stream true}
      system              (assoc :system system)
      (:temperature opts) (assoc :temperature (:temperature opts))
      (:top-k opts)       (assoc :top_k (:top-k opts))
      (:top-p opts)       (assoc :top_p (:top-p opts))
      stop-sequences      (assoc :stop_sequences stop-sequences)
      (:tools opts)       (assoc :tools (:tools opts))
      (:tool-choice opts) (assoc :tool_choice (:tool-choice opts))
      (:response-format opts) (assoc :response_format {:type (:response-format opts)}))))

(defn make-anthropic-request
  "Make a streaming request to Anthropic API with immediate processing"
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
              request (-> (java.net.http.HttpRequest/newBuilder)
                          (.uri (java.net.URI/create (or (:api-endpoint opts) "https://api.anthropic.com/v1/messages")))
                          (.timeout (java.time.Duration/ofSeconds (or (:request-timeout opts) 30)))
                          (.header "Content-Type" "application/json")
                          (.header "x-api-key" api-key)
                          (.header "anthropic-version" "2023-06-01")
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
              (throw (ex-info (str "Anthropic API request failed. Status: " (.statusCode response))
                              {:status (.statusCode response)
                               :model-id model-id
                               :response-body error-content})))
            ;; Let process-sse handle closing the input-stream
            (let [input-stream (.body response)]
              (process-sse input-stream (create-anthropic-stream-config chunks-chan metadata-atom)))))
        (catch Exception e
          (close! chunks-chan)
          (println e)
          (throw e))))
    {:channel chunks-chan
     :metadata metadata-atom}))

;; ──────────────────────────────────────────────────────────────
;; Anthropic Backend Implementation
;; ──────────────────────────────────────────────────────────────
(defrecord AnthropicBackend []
  proto/LLMBackend

  (-prompt [_this model-id prompt-str opts]
    (let [{:keys [channel]} (proto/-stream _this model-id prompt-str opts)]
      (loop [acc []]
        (if-let [chunk (<!! channel)]
          (recur (conj acc chunk))
          (str/join acc)))))

  (-stream [_this model-id prompt-str opts]
    (let [actual-model-id (if (keyword? model-id) (name model-id) (str model-id))]
      (make-anthropic-request actual-model-id prompt-str opts)))

  (-opts-schema [_this _model-id]
    anthropic-opts-schema)

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
        (:finish-reason md) (assoc :stop_reason (:finish-reason md))
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
  (->AnthropicBackend))

(defn register-backend! []
  (reg/register-backend! :anthropic (->AnthropicBackend)))
