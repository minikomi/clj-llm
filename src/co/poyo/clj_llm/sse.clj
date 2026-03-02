(ns co.poyo.clj-llm.sse
  "SSE parsing and streaming: parse lines, read streams, return channels of events."
  (:require
   [camel-snake-kebab.core :as csk]
   [camel-snake-kebab.extras :as cske]
   [cheshire.core :as json]
   [clojure.core.async :as a :refer [chan close!]]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [co.poyo.clj-llm.net :as net]))

;; ════════════════════════════════════════════════════════════════════
;; Line parsing
;; ════════════════════════════════════════════════════════════════════

;; SSE streams repeat the same small set of keys on every chunk — memoize
;; to avoid repeated string manipulation.
(def ^:private ->kebab-key (memoize csk/->kebab-case-keyword))

(defn parse-line
  "Parse one SSE line. Returns {:data map}, {:done true}, or nil."
  [line]
  (cond
    (not (str/starts-with? line "data:")) nil
    (str/ends-with? line "[DONE]")        {:done true}
    :else (try
            (let [raw (str/trim (subs line 5))
                  parsed (json/parse-string raw)]
              {:data (cske/transform-keys ->kebab-key parsed)})
            (catch Exception _ nil))))

;; ════════════════════════════════════════════════════════════════════
;; HTTP error handling (private)
;; ════════════════════════════════════════════════════════════════════

(defn- error-type
  "Classify HTTP status into an error category for programmatic handling."
  [status]
  (cond
    (#{401 403} status) :llm/invalid-key
    (= 429 status)      :llm/rate-limit
    (<= 400 status 499) :llm/invalid-request
    (<= 500 status 599) :llm/server-error
    :else               :llm/unknown))

(defn- read-body
  "Read response body as string, attempt JSON parse."
  [response]
  (try
    (let [raw (cond
                (string? (:body response)) (:body response)
                (instance? java.io.InputStream (:body response)) (slurp (:body response))
                :else (str (:body response)))]
      (try (json/parse-string raw true)
           (catch Exception _ raw)))
    (catch Exception _ nil)))

(defn- error-message
  "Extract a human-readable message from a provider error body."
  [body]
  (or (get-in body [:error :message])
      (when (string? body) body)
      ""))

(defn- error-event
  "Build an :error event from an HTTP error response."
  [provider-name response]
  (let [status (:status response)
        body   (read-body response)
        msg    (error-message body)]
    {:type      :error
     :error     (str provider-name ": " msg " (HTTP " status ")")
     :status    status
     :exception (ex-info (str provider-name ": " msg)
                         {:error-type  (error-type status)
                          :status      status
                          :body        body})}))

;; ════════════════════════════════════════════════════════════════════
;; Stream reading
;; ════════════════════════════════════════════════════════════════════

(defn- stream-sse
  "Read SSE lines from input-stream, convert and write to out channel."
  [input-stream convert-fn out]
  (with-open [reader (io/reader input-stream)]
    (loop []
      (when-let [line (.readLine reader)]
        (if-let [{:keys [data done]} (parse-line line)]
          (if done
            (a/>!! out {:type :done})
            (let [evts (seq (convert-fn data))]
              (doseq [e evts] (a/>!! out e))
              (when-not (some #(= :done (:type %)) evts)
                (recur))))
          (recur))))))

(defn create-event-stream
  "POST to a streaming API and return a channel of internal events.
   convert-fn: (data-map -> seq-of-event-maps | nil)"
  [url headers body convert-fn provider-name]
  (let [out (chan 1024)]
    (future
      (try
        (let [{:keys [error status body] :as response} (net/post-stream url headers body)]
          (cond
            error
            (a/>!! out {:type :error :error (.getMessage error) :exception error})

            (= 200 status)
            (stream-sse body convert-fn out)

            :else
            (a/>!! out (error-event provider-name response))))
        (catch Exception e
          (a/>!! out {:type :error :error e}))
        (finally
          (close! out))))
    out))
