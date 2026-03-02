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

(def xf
  "Transducer: raw SSE lines \u2192 parsed data maps. Skips [DONE] and blanks."
  (comp
    (remove #(str/ends-with? % "[DONE]"))
    (keep parse-sse-line)))

(defn lines
  "POST to an SSE endpoint, return an IReduceInit of raw lines.
   Manages HTTP connection lifecycle — opens on reduce, closes when done.

   Compose with sse/xf and your domain transducer:

   (eduction (comp sse/xf (keep #(data->event % schema tools)))
             (sse/lines url headers body))"
  [url headers body]
  (reify clojure.lang.IReduceInit
    (reduce [_ f init]
      (try
        (let [{:keys [error status body]} (net/post-stream url headers body)]
          (cond
            error (f init {:type :error :error (.getMessage ^Exception error)})
            (= 200 status)
            (with-open [reader (io/reader body)]
              (reduce f init (line-seq reader)))
            :else (f init {:type :error :status status})))
        (catch Exception e
          (f init {:type :error :error (str e)}))))))
