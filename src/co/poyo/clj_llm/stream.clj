(ns co.poyo.clj-llm.stream
  "HTTP → bounded core.async channel of decoded SSE events."
  (:require
   [clojure.core.async :as a]
   [clojure.java.io :as io]
   [co.poyo.clj-llm.net :as net]
   [co.poyo.clj-llm.sse :as sse])
  (:import (java.io BufferedReader InputStream)))

(defn open-event-stream
  "POST to an SSE endpoint, return a bounded core.async channel of
   decoded SSE data maps.  Closing the channel cancels the HTTP request.

   Throws on connection errors (directly from java.net.http) and
   non-200 responses (ex-info with :status and :body from the API)."
  [url headers body]
  (let [{:keys [status body]} (net/post-stream url headers body)]
    (when (not= 200 status)
      (let [response-body (try (slurp ^InputStream body) (catch Exception _ nil))]
        (throw (ex-info (str "HTTP " status (when response-body (str ": " response-body)))
                        (cond-> {:status status}
                          response-body (assoc :body response-body))))))
    (let [ch (a/chan 256)]
      (a/thread
        (try
          (with-open [^BufferedReader rdr (io/reader ^InputStream body)]
            (loop []
              (when-let [line (.readLine rdr)]
                (if-let [evt (sse/parse-data-line line)]
                  (when (a/>!! ch evt)
                    (recur))
                  (recur)))))
          (catch Exception e
            (when-not (.isInterrupted (Thread/currentThread))
              (a/>!! ch e)))
          (finally
            (a/close! ch))))
      ch)))
