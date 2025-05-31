(ns co.poyo.clj-llm.sse
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.core.async :as async :refer [>!! close! chan]])
  (:import (java.io BufferedReader InputStreamReader)))

(defn process-sse-stream
  "Process SSE input stream and put parsed events onto channel.
   Returns channel immediately, processes stream in background.
   Optional config: {:transform-fn fn :on-error fn :on-done fn}"
  ([^java.io.InputStream input-stream]
   (process-sse-stream input-stream {}))
  ([^java.io.InputStream input-stream config]
   (let [channel (chan)
         {:keys [transform-fn on-error on-done]} config
         rdr (BufferedReader. (InputStreamReader. input-stream))]
     (future
       (try
         (loop []
           (when-let [line (.readLine rdr)]
             (let [trimmed (str/trim line)]
               (cond
                 ;; Skip blank lines or non-data lines
                 (or (str/blank? trimmed)
                     (not (str/starts-with? trimmed "data: ")))
                 (recur)

                 ;; "[DONE]" indicates end of stream
                 (= (subs trimmed 6) "[DONE]")
                 (do
                   (when on-done (on-done))
                   (close! channel))

                 ;; Process data event
                 :else
                 (let [payload (subs trimmed 6)
                       parsed (json/parse-string payload true)
                       event (if transform-fn (transform-fn parsed) parsed)]
                   (>!! channel event)
                   (recur))))))
         (catch Exception e
           (when on-error (on-error (.getMessage e)))
           (close! channel))
         (finally
           (try (.close rdr) (catch Exception _))
           (try (.close input-stream) (catch Exception _)))))
     channel)))
