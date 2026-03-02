(ns co.poyo.clj-llm.sse
  "SSE line parsing and event stream construction."
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

(defn event-stream
  "POST to an SSE endpoint, return a lazy seq of parsed event maps.
   Reader closes automatically when the stream is exhausted.

   On HTTP error or exception, returns a single-element seq
   with {:type :error ...}.

   Usage in backends:
     (keep data->event (sse/event-stream url headers body))"
  [url headers body]
  (try
    (let [{:keys [error status body]} (net/post-stream url headers body)]
      (cond
        error [{:type :error :error (.getMessage ^Exception error)}]
        (= 200 status)
        (let [reader (io/reader body)]
          ((fn step []
             (lazy-seq
               (if-let [line (.readLine reader)]
                 (if (str/ends-with? line "[DONE]")
                   (do (.close reader) nil)
                   (if-let [parsed (parse-sse-line line)]
                     (cons parsed (step))
                     (step)))
                 (do (.close reader) nil))))
           ))
        :else [{:type :error :status status}]))
    (catch Exception e
      [{:type :error :error (str e)}])))
