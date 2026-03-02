(ns co.poyo.clj-llm.sse
  "SSE line parsing and event stream construction."
  (:require
   [camel-snake-kebab.core :as csk]
   [camel-snake-kebab.extras :as cske]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [co.poyo.clj-llm.net :as net])
  (:import (java.io Closeable BufferedReader)))

(def ^:private ->kebab-key (memoize csk/->kebab-case-keyword))

(defn- parse-data
  "Given one SSE line, return:
   - ::done for \"data: [DONE]\"
   - event map for JSON \"data:\" lines
   - nil otherwise"
  [^String line]
  (when (str/starts-with? line "data:")
    (let [raw (str/trim (subs line 5))]
      (cond
        (= raw "[DONE]") ::done
        :else
        (try
          (cske/transform-keys ->kebab-key (json/parse-string raw))
          (catch Exception _ nil))))))

(defn ->ReduceStream
  "Create an IReduceInit from a reduce-fn.
   The reduce-fn must handle its own cleanup (e.g. finally)."
  [reduce-fn]
  (reify
    clojure.lang.IReduceInit (reduce [_ rf init] (reduce-fn rf init))))

(defn open-event-stream
  "POST to an SSE endpoint.
   Returns a Closeable+IReduceInit of parsed event maps.
   Throws on setup/HTTP errors. Always closes when reduced."
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
    (let [^BufferedReader r (io/reader body)]
      (->ReduceStream
       (fn [rf init]
         (try
           (loop [acc init]
             (cond
               (reduced? acc) @acc
               :else
               (if-let [line (.readLine r)]
                 (let [x (parse-data line)]
                   (cond
                     (nil? x) (recur acc)
                     (= x ::done) acc
                     :else (recur (rf acc x))))
                 acc)))
           (finally
             (.close r))))))))
