(ns co.poyo.clj-llm.sse
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [promesa.core :as p])
  (:import (java.io BufferedReader InputStreamReader)))

(defn stream->events [in]
  (let [out-ch (p/chan)
        rdr (BufferedReader. (InputStreamReader. in))]
    (p/do!
     (try
       (loop []
         (if-let [line (.readLine rdr)]
           (let [line (str/trim line)]
             (cond
               (str/blank? line) (recur) ; Skip empty lines after trim

               (str/starts-with? line "data: ")
               (let [data-str (subs line 6)]
                 (if (= data-str "[DONE]")
                   (p/close! out-ch)
                   (let [parsed-event (json/parse-string data-str true)]
                     (p/put! out-ch parsed-event)
                     (recur))))

               :else (recur))) ; Ignore lines not starting with "data: "
           (p/close! out-ch))) ; EOF
       (catch Exception e
         (println "Error processing SSE stream:" e) ; Or use a proper logger
         (p/close! out-ch)))
     out-ch)))
         )))
