(ns co.poyo.clj-llm.sse
  "SSE parsing and streaming: parse lines, read streams, return reducibles of events."
  (:require
   [camel-snake-kebab.core :as csk]
   [camel-snake-kebab.extras :as cske]
   [cheshire.core :as json]
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

(def xf-parse
  "Transducer: raw SSE lines → parsed data maps. Stops at [DONE]."
  (comp
    (keep parse-line)
    (halt-when :done)
    (map :data)))

(defn event-stream
  "Returns a blocking reducible of parsed SSE data maps.
   Manages the HTTP connection lifecycle during reduce.
   Error responses are delivered as a single {:type :error ...} element.

   Usage:
     (reduce (fn [_ data] (prn data)) nil
             (event-stream url headers body \"openai\"))"
  [url headers body provider-name]
  (reify clojure.lang.IReduceInit
    (reduce [_ f init]
      (try
        (let [{:keys [error status body] :as response} (net/post-stream url headers body)]
          (cond
            error
            (f init {:type :error :error (.getMessage ^Exception error)})

            (= 200 status)
            (with-open [reader (io/reader body)]
              (transduce xf-parse f init (line-seq reader)))

            :else
            (f init (error-event provider-name response))))
        (catch Exception e
          (f init {:type :error :error (str e)}))))))
