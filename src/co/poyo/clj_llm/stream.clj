(ns co.poyo.clj-llm.stream
  "HTTP/SSE wiring.

   Responsibilities:
   - net: issue HTTP request
   - stream: expose body as an auto-closing reducible of lines
   - sse: parse SSE frames (pure)
   - json: decode event payloads (pure)

   Keeps I/O at the edge and returns reducibles so callers can compose
   transducers without callback APIs."
  (:require
   [clojure.java.io :as io]
   [co.poyo.clj-llm.net :as net]
   [co.poyo.clj-llm.sse :as sse])
  (:import (java.io BufferedReader InputStream)))

(defn line-reducible
  "Wrap an InputStream as an IReduceInit of text lines.
   Always closes the reader when reduction finishes or fails."
  [^InputStream input-stream]
  (reify
    clojure.lang.IReduceInit
    (reduce [_ rf init]
      (with-open [^BufferedReader rdr (io/reader input-stream)]
        (reduce rf init (line-seq rdr))))))

(defn sse-data-xf
  "Simple transducer: lines -> decoded SSE data maps."
  []
  (sse/parse-data-lines))

(defn open-event-stream
  "POST to an SSE endpoint and return a reducible of decoded event maps.
   Throws on request/setup errors."
  [url headers req-body]
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
      (eduction (sse-data-xf)
                (line-reducible body)))))
