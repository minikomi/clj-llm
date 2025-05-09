(ns co.poyo.clj-llm.stream
  "Functional stream processing for Server-Sent Events (SSE)"
  (:require [clojure.core.async :as async :refer [chan go <! >! close!]]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import [java.io InputStream]))

;; ---- Pure Functions for Processing SSE ----

(defn extract-sse-data
  "Extract data portion from SSE event string"
  [event-str]
  (when-not (str/blank? event-str)
    (when-let [data-match (re-find #"data: (.*)" event-str)]
      (second data-match))))

(defn parse-json-data
  "Parse JSON string to Clojure data structure"
  [data-str]
  (when-not (or (nil? data-str) (str/blank? data-str))
    (if (= data-str "[DONE]")
      {:done true}
      (try
        (json/parse-string data-str true)
        (catch Exception e
          {:error (.getMessage e)})))))

;; ---- Configurable Stream Processing ----

(defn create-stream-processor
  "Create a stream processor with configurable extraction functions"
  [& {:keys [extract-fn transform-fn]
      :or {extract-fn identity
           transform-fn identity}}]
  (fn [event-str]
    (when event-str
      (if (instance? Exception event-str)
        {:type :error, :message (.getMessage event-str)}
        (->> event-str
             extract-fn
             parse-json-data
             transform-fn)))))

;; ---- Minimal Async Functions ----

(defn read-input-stream
  "Read an InputStream to a channel of strings with minimal chunking"
  [^InputStream input-stream buffer-size]
  (let [out (chan)]
    (future
      (try
        (with-open [in input-stream]
          (let [buffer (byte-array buffer-size)
                sb (StringBuilder.)]
            (loop []
              (let [bytes-read (.read in buffer 0 buffer-size)]
                (if (pos? bytes-read)
                  (do
                    (.append sb (String. buffer 0 bytes-read "UTF-8"))
                    (let [content (.toString sb)
                          idx (str/last-index-of content "\n\n")]
                      (when (and
                             (not (nil? idx))
                             (pos? idx))
                        (let [events-part (subs content 0 (+ idx 2))
                              remainder (subs content (+ idx 2))]
                          (doseq [event (str/split events-part #"(\r\n|\n){2}")]
                            (when-not (str/blank? event)
                              (async/>!! out event)))
                          (.setLength sb 0)
                          (.append sb remainder))))
                    (recur))
                  (do
                    (let [remaining (.toString sb)]
                      (when-not (str/blank? remaining)
                        (async/>!! out remaining)))
                    (close! out)))))))
        (catch Exception e
          (async/>!! out e)
          (close! out))))
    out))

;; ---- Main Processing Function ----

(defn process-sse
  "Process SSE stream with configurable processors and handlers"
  [^InputStream input-stream
   {:keys [buffer-size
           extract-fn
           transform-fn
           on-content
           on-error
           on-done]
    :or {buffer-size 16384
         extract-fn extract-sse-data
         transform-fn identity
         on-content println
         on-error #(println "Error:" %)
         on-done #(println "Done")}}]

  (let [events-chan (read-input-stream input-stream buffer-size)
        processor (create-stream-processor
                    :extract-fn extract-fn
                    :transform-fn transform-fn)]

    ;; Single go-loop to process events
    (go
      (loop []
        (if-let [event-str (<! events-chan)]
          (let [result (processor event-str)]
            (cond
              (instance? Exception event-str)
              (on-error (.getMessage event-str))

              (:done result)
              (on-done)

              (:error result)
              (on-error (:message result))

              :else
              (when result (on-content result)))
            (recur))
          (on-done))))

    ;; Return the events channel for optional additional processing
    events-chan))
