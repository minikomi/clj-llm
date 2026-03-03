(ns co.poyo.clj-llm.stream
  "HTTP/SSE wiring.

   Responsibilities:
   - net: issue HTTP request
   - stream: expose body as a bounded core.async channel of decoded events
   - sse: parse SSE data lines (pure)
   - json: decode event payloads (pure)"
  (:require
   [clojure.core.async :as a]
   [clojure.java.io :as io]
   [co.poyo.clj-llm.net :as net]
   [co.poyo.clj-llm.sse :as sse])
  (:import (java.io BufferedReader InputStream)))

(defn- line-reducible
  "Wrap an InputStream as an IReduceInit of text lines.
   Always closes the reader when reduction finishes or fails."
  [^InputStream input-stream]
  (reify
    clojure.lang.IReduceInit
    (reduce [_ rf init]
      (with-open [^BufferedReader rdr (io/reader input-stream)]
        (reduce rf init (line-seq rdr))))))

(defn- reducible->chan
  "Pump a blocking reducible into a bounded channel.
   Spins up a thread that:
   - puts each element via >!! (blocks when full → backpressure)
   - stops when the channel is closed (cancellation)
   - closing the channel causes the InputStream to throw on read,
     terminating the reducible's with-open block and freeing HTTP resources"
  [reducible ch]
  (a/thread
    (try
      (reduce (fn [_ x]
                (when-not (a/>!! ch x)
                  (reduced nil)))
              nil reducible)
      (catch Exception e
        ;; Channel may already be closed (consumer cancelled) — that's fine
        (a/>!! ch (ex-info "Stream error" {:error-type :llm/stream-error} e)))
      (finally
        (a/close! ch))))
  ch)

(def ^:private default-capacity 256)

(defn open-event-stream
  "POST to an SSE endpoint and return a bounded core.async channel of
   decoded event maps.

   Options:
     :capacity  - channel buffer size (default 256)
     :xform     - transducer to apply at the channel boundary

   Closing the returned channel cancels the HTTP request and cleans up.
   Throws on request/setup errors (before events start flowing)."
  ([url headers req-body]
   (open-event-stream url headers req-body nil))
  ([url headers req-body {:keys [capacity xform]}]
   (let [{:keys [error status body]} (net/post-stream url headers req-body)]
     (cond
       error
       (throw (ex-info "SSE request failed"
                       {:type :sse/request-failed}
                       ^Throwable error))

       (not= 200 status)
       (throw (ex-info "SSE HTTP error"
                       {:type :sse/http-error
                        :status status}))

       :else
       (let [cap       (or capacity default-capacity)
             reducible (eduction (keep sse/parse-data-line)
                                (line-reducible body))
             ch        (if xform
                         (a/chan cap xform)
                         (a/chan cap))]
         (reducible->chan reducible ch))))))
