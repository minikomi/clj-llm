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
  (when (str/starts-with? line "data:")
    (let [raw (str/trim (subs line 5))]
      (if (= raw "[DONE]")
        {:done true}
        (try {:data (cske/transform-keys ->kebab-key (json/parse-string raw))}
             (catch Exception _ nil))))))

;; ════════════════════════════════════════════════════════════════════
;; HTTP error handling (private)
;; ════════════════════════════════════════════════════════════════════

(def ^:private status-errors
  {401 {:type :llm/invalid-key    :msg "Invalid API key"}
   403 {:type :llm/invalid-key    :msg "Invalid API key"}
   404 {:type :llm/invalid-request :msg "Resource not found"}
   429 {:type :llm/rate-limit     :msg "Rate limit exceeded"}
   400 {:type :llm/invalid-request :msg "Invalid request"}
   422 {:type :llm/invalid-request :msg "Invalid request"}
   500 {:type :llm/server-error   :msg "Server error"}
   502 {:type :llm/server-error   :msg "Server error"}
   503 {:type :llm/server-error   :msg "Server error"}
   504 {:type :llm/server-error   :msg "Server error"}})

(defn- parse-http-error
  "Convert HTTP response to exception with proper :error-type."
  [provider status body]
  (let [{:keys [type msg]} (get status-errors (int status)
                                {:type :llm/unknown :msg (str "HTTP " status)})]
    (ex-info (str provider ": " msg)
             {:error-type  type
              :status      status
              :body        body
              :retry-after (get-in body [:error :retry_after])})))

(defn- parse-error-body [response]
  (let [body-str (cond
                   (string? (:body response)) (:body response)
                   (instance? java.io.InputStream (:body response)) (slurp (:body response))
                   :else (str (:body response)))]
    (try (json/parse-string body-str true)
         (catch Exception _ body-str))))

(defn- error-event [provider-name response]
  (try
    (let [body (parse-error-body response)
          ex (parse-http-error provider-name (:status response) body)]
      {:type :error :error (.getMessage ex) :status (:status response)
       :provider-error body :exception ex})
    (catch Exception e
      {:type :error
       :error (str "HTTP " (:status response) ": " (:body response))
       :status (:status response)
       :exception (ex-info (str "Failed to parse error: " (.getMessage e))
                           {:response response})})))

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
