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
   [camel-snake-kebab.core :as csk]
   [camel-snake-kebab.extras :as cske]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [co.poyo.clj-llm.net :as net]
   [co.poyo.clj-llm.sse :as sse])
  (:import (java.io BufferedReader InputStream)))

(def ^:private ->kebab-key (memoize csk/->kebab-case-keyword))

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
  "Simple transducer: lines -> SSE data payload strings.
   Uses keep to include only meaningful payloads."
  []
  (comp
   (sse/parse-data-lines)
   (keep (fn [data]
           (when-not (= "[DONE]" data)
             data)))))

(defn json->kebab-xf
  "JSON string -> kebab-cased map, skipping non-JSON payloads."
  []
  (keep (fn [data]
          (try
            (cske/transform-keys ->kebab-key
                                 (json/parse-string data))
            (catch Exception _
              nil)))))

(defn open-event-stream
  "POST to an SSE endpoint and return a reducible of decoded event maps.
   Throws on request/setup errors.

   Returned value is reducible and transducer-friendly via eduction."
  [url headers req-body]
  (let [{:keys [error status body]} (net/post-stream url headers req-body)]
    (when error
      (throw (ex-info "SSE request failed"
                      {:type :sse/request-failed}
                      ^Throwable error)))
    (when (not= 200 status)
      (throw (ex-info "SSE HTTP error"
                      {:type :sse/http-error
                       :status status})))
    (eduction (comp (sse-data-xf)
                    (json->kebab-xf))
              (line-reducible body))))
