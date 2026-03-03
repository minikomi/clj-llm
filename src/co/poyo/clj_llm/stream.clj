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
   decoded event maps.  Closing the channel cancels the HTTP request.

   opts (optional map):
     :capacity  - buffer size (default 256)
     :xform     - transducer applied at the channel"
  ([url headers body] (open-event-stream url headers body nil))
  ([url headers body {:keys [capacity xform]}]
   (let [{:keys [error status body]} (net/post-stream url headers body)]
     (cond
       error
       (throw (ex-info (str "SSE request failed: " (.getMessage ^Throwable error))
                       {:error-type :llm/network-error} ^Throwable error))

       (not= 200 status)
       (let [response-body (try (slurp ^InputStream body) (catch Exception _ nil))]
         (throw (ex-info (str "HTTP " status (when response-body (str ": " response-body)))
                         (cond-> {:error-type (case (int status)
                                                401 :llm/invalid-key
                                                403 :llm/invalid-key
                                                429 :llm/rate-limit
                                                (400 404 422) :llm/invalid-request
                                                :llm/server-error)
                                  :status status}
                           response-body (assoc :body response-body)))))

       :else
       (let [ch (a/chan (or capacity 256) xform)]
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
                 (a/>!! ch (ex-info (str "Stream interrupted: " (.getMessage e))
                                    {:error-type :llm/stream-error} e))))
             (finally
               (a/close! ch))))
         ch)))))
