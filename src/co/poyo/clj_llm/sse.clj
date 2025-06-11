(ns co.poyo.clj-llm.sse
  (:require [cheshire.core :as json]
            [clojure.string  :as str]
            [clojure.java.io :as io]
            [clojure.core.async :as a :refer [chan go >! close!]]))

;; ——— line-level reader ———————————————————————————
(defn input-stream->line-chan
  "Emit CR/LF terminated lines from `is` into an async channel.
   Buffer = 64 to keep latency low."
  ^clojure.core.async.impl.channels.ManyToManyChannel
  [^java.io.InputStream is]
  (let [out (chan 64)
        rdr (io/reader is)]
    (go (loop []
          (if-let [l (.readLine rdr)]
            (do (>! out l) (recur))
            (do (.close rdr) (close! out)))))
    out))

(defn sse-done? [^String line] (= line "data: [DONE]"))

;; ——— reusable transducer ————————————————————————
(def remove-non-data (filter #(str/starts-with? % "data: ")))
(def stop-at-done    (take-while #(not (sse-done? %))))
(def parse-json      (map #(json/parse-string (subs % 6) true)))

(def sse->json-xf (comp remove-non-data stop-at-done parse-json))
