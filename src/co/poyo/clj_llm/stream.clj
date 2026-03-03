(ns co.poyo.clj-llm.stream
  "HTTP SSE stream: opens a connection and returns a reducible of
   parsed JSON data maps. Wires together net (HTTP), sse (line parsing),
   and JSON decoding."
  (:require
   [camel-snake-kebab.core :as csk]
   [camel-snake-kebab.extras :as cske]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [co.poyo.clj-llm.net :as net]
   [co.poyo.clj-llm.sse :as sse])
  (:import (java.io BufferedReader)))

(def ^:private ->kebab-key (memoize csk/->kebab-case-keyword))

(defn ->ReduceStream
  "Create an IReduceInit from a reduce-fn.
   The reduce-fn must handle its own cleanup (e.g. finally)."
  [reduce-fn]
  (reify
    clojure.lang.IReduceInit (reduce [_ rf init] (reduce-fn rf init))))

(defn open-event-stream
  "POST to an SSE endpoint.
   Returns an IReduceInit of parsed, kebab-cased JSON data maps.
   Throws on setup/HTTP errors. Closes the connection when done."
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
    (let [^BufferedReader rdr (io/reader body)]
      (->ReduceStream
       (fn [rf init]
         (try
           (reduce rf init
                   (->> (line-seq rdr)
                        sse/parse-events
                        (remove #(= "[DONE]" (:data %)))
                        (map (fn [{:keys [data]}]
                               (cske/transform-keys ->kebab-key
                                                    (json/parse-string data))))))
           (finally
             (.close rdr))))))))
