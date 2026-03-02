(ns co.poyo.clj-llm.sse
  "SSE streaming: blocking reducible over parsed server-sent events."
  (:require
   [camel-snake-kebab.core :as csk]
   [camel-snake-kebab.extras :as cske]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [co.poyo.clj-llm.net :as net]))

(def ^:private ->kebab-key (memoize csk/->kebab-case-keyword))

(defn- parse-sse-line
  "Parse one SSE data line into a kebab-cased map, or nil."
  [line]
  (when (str/starts-with? line "data:")
    (try
      (let [raw (str/trim (subs line 5))]
        (cske/transform-keys ->kebab-key (json/parse-string raw)))
      (catch Exception _ nil))))

(def ^:private xf-sse
  "Transducer: raw SSE lines → parsed data maps. Stops at [DONE]."
  (comp
    (remove #(str/ends-with? % "[DONE]"))
    (keep parse-sse-line)))

(defn- read-error-body
  "Read response body, attempt JSON parse."
  [{:keys [body]}]
  (try
    (let [raw (if (string? body) body (slurp body))]
      (try (json/parse-string raw true)
           (catch Exception _ raw)))
    (catch Exception _ nil)))

(defn event-stream
  "Returns a blocking reducible of parsed SSE data maps.
   Manages the HTTP connection lifecycle during reduce.
   Error responses are delivered as a single {:type :error ...} element."
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
              (transduce xf-sse f init (line-seq reader)))

            :else
            (f init {:type :error
                     :status status
                     :body (read-error-body response)})))
        (catch Exception e
          (f init {:type :error :error (str e)}))))))
