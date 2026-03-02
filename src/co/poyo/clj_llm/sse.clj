(ns co.poyo.clj-llm.sse
  "SSE stream parsing."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.core.async :as a :refer [chan >!! close! thread]]
   [clojure.java.io :as io]
   [clojure.walk :as walk]
   [camel-snake-kebab.core :as csk]))

(defn- parse-json-kebab
  "Parse JSON string, keywordize with kebab-case."
  [s]
  (->> (json/parse-string s)
       (walk/postwalk (fn [x]
                        (if (map? x)
                          (update-keys x (comp keyword csk/->kebab-case))
                          x)))))

(defn- parse-sse-line
  "Parse a single SSE line. Returns a map or nil.
   Data lines → {::data parsed}, done → {::done true}, others → nil."
  [line]
  (cond
    (str/starts-with? line "event:") nil
    (str/starts-with? line "data:")
    (let [raw (str/trim (subs line 5))]
      (if (= raw "[DONE]")
        {::done true}
        (try {::data (parse-json-kebab raw)}
             (catch Exception _ nil))))
    :else nil))

(defn parse-sse
  "Parse an SSE InputStream into a channel of event maps.
   Returns channel of {::data ...}, {::done true}, or {::error ...}."
  ([input-stream] (parse-sse input-stream {}))
  ([input-stream {:keys [buffer-size parser-fn]
                  :or {buffer-size 1024
                       parser-fn parse-sse-line}}]
   (let [out (chan buffer-size)]
     (thread
       (try
         (with-open [reader (io/reader input-stream)]
           (loop []
             (when-let [line (.readLine reader)]
               (if-let [event (parser-fn line)]
                 (do (>!! out event)
                     (when-not (::done event)
                       (recur)))
                 (recur)))))
         (catch Exception e
           (>!! out {::error e}))
         (finally
           (close! out))))
     out)))
