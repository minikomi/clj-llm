(ns co.poyo.clj-llm.sse
  (:require [cheshire.core :as json]
            [clojure.string :as str])
  (:import (java.io BufferedReader InputStreamReader)))

(defn stream->events [in]
  (let [rdr (BufferedReader. (InputStreamReader. in))]
    (->> (line-seq rdr)
         (filter #(str/starts-with? % "data: "))
         (map #(subs % 6))
         (take-while #(not= "[DONE]" %))
         (map #(json/parse-string % true))
         )))
