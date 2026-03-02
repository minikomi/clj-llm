(ns co.poyo.clj-llm.sse
  (:require
   [camel-snake-kebab.core :as csk]
   [camel-snake-kebab.extras :as cske]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [co.poyo.clj-llm.net :as net])
  (:import (java.io Closeable)))

(def ^:private ->kebab-key (memoize csk/->kebab-case-keyword))

(defn- parse-sse-line
  [line]
  (when (str/starts-with? line "data:")
    (let [raw (str/trim (subs line 5))]
      (cond
        (= raw "[DONE]")
        {:type :done}

        :else
        (try
          {:type :event
           :data (cske/transform-keys
                  ->kebab-key
                  (json/parse-string raw))}
          (catch Exception _
            nil))))))

(deftype SseStream [^java.io.BufferedReader r closed?]
  Closeable
  (close [_]
    (when (compare-and-set! closed? false true)
      (.close r)))

  clojure.lang.IReduceInit
  (reduce [this rf init]
    (try
      (loop [acc init]
        (if (reduced? acc)
          @acc
          (if-let [line (.readLine r)]
            (let [parsed (parse-sse-line line)]
              (cond
                (= (:type parsed) :done)
                acc

                (= (:type parsed) :event)
                (recur (rf acc (:data parsed)))

                :else
                (recur acc)))
            acc)))
      (finally
        (.close ^Closeable this)))))

(deftype ErrorStream [err]
  Closeable
  (close [_] nil)

  clojure.lang.IReduceInit
  (reduce [_ rf init]
    (rf init err)))

(deftype XformedStream [xf ^Closeable stream]
  Closeable
  (close [_] (.close stream))

  clojure.lang.IReduceInit
  (reduce [_ rf init]
    (.reduce ^clojure.lang.IReduceInit stream (xf rf) init)))

(defn xform
  "Wrap a Closeable+IReduceInit stream with a transducer.
   Returns a new Closeable+IReduceInit."
  [xf stream]
  (->XformedStream xf stream))

(defn open-event-stream
  "Returns a Closeable + Reducible stream of event maps.
   Always closes when reduced."
  [url headers req-body]
  (try
    (let [{:keys [error status body]} (net/post-stream url headers req-body)]
      (cond
        error
        (->ErrorStream
         {:type :error :error (.getMessage ^Exception error)})

        (not= 200 status)
        (->ErrorStream
         {:type :error :status status})

        :else
        (->SseStream
         (io/reader body)
         (atom false))))
    (catch Exception e
      (->ErrorStream
       {:type :error :error (.getMessage e)}))))
