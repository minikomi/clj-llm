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

(defn- error-event
  "Build an :error event from an HTTP error response."
  [provider-name response]
  {:type   :error
   :status (:status response)
   :body   (read-body response)})

;; ════════════════════════════════════════════════════════════════════
;; Stream reading
;; ════════════════════════════════════════════════════════════════════

(defn- stream-sse
  "Read SSE lines from input-stream, convert and write to out channel."
  [input-stream convert-fn out]
  (with-open [reader (io/reader input-stream)]
    (reduce (fn [_ line]
              (when-let [{:keys [data done]} (parse-line line)]
                (if done
                  (do (a/>!! out {:type :done}) (reduced nil))
                  (let [evts (seq (convert-fn data))]
                    (run! #(a/>!! out %) evts)
                    (when (some #(= :done (:type %)) evts)
                      (reduced nil))))))
            nil
            (line-seq reader))))

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
