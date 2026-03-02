(ns co.poyo.clj-llm.sse
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.core.async :refer [chan >!! close! thread]]
   [clojure.java.io :as io]
   [clojure.walk :as walk]
   [camel-snake-kebab.core :as csk]))

(def ^:private buf-size 1024)

(defn- kebab-keys [m]
  (walk/postwalk
   (fn [x] (if (map? x) (update-keys x (comp keyword csk/->kebab-case)) x))
   m))

(defn- parse-line [line]
  (when (str/starts-with? line "data:")
    (let [raw (str/trim (subs line 5))]
      (if (= raw "[DONE]")
        {::done true}
        (try {::data (-> raw json/parse-string kebab-keys)}
             (catch Exception _ nil))))))

(defn parse-sse
  "Parse an SSE InputStream into a channel.
   Events are {::data map}, {::done true}, or {::error ex}."
  [input-stream]
  (let [out (chan buf-size)]
    (thread
      (try
        (with-open [reader (io/reader input-stream)]
          (loop []
            (when-let [line (.readLine reader)]
              (if-let [event (parse-line line)]
                (do (>!! out event)
                    (when-not (::done event)
                      (recur)))
                (recur)))))
        (catch Exception e
          (>!! out {::error e}))
        (finally
          (close! out))))
    out))
