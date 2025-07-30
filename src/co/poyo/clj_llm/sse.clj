(ns co.poyo.clj-llm.sse
  "Simple SSE parsing for OpenAI streaming responses"
  (:require [clojure.string :as str]
            [clojure.core.async :as a :refer [chan >!! close! thread]]
            [clojure.java.io :as io]))

(defn parse-sse
  "Parse SSE stream from an InputStream into a channel of events.
   Only handles 'data:' lines which is all OpenAI uses.
   
   Returns channel of maps like {\"data\" \"...json...\"}"
  [input-stream]
  (let [out (chan 1024)]
    (thread
      (try
        (with-open [reader (io/reader input-stream)]
          (loop []
            (when-let [line (.readLine reader)]
              (when (str/starts-with? line "data: ")
                (let [data (subs line 6)]
                  (when-not (= data "[DONE]")
                    (>!! out {"data" data}))))
              (recur))))
        (catch Exception e
          (>!! out {"error" (.getMessage e)}))
        (finally
          (close! out))))
    out))