(ns co.poyo.clj-llm.stream
  "Stream processing utilities for Server-Sent Events (SSE) streams.
   These utilities are LLM provider-agnostic and can be used with
   any API that returns SSE streams in a similar format."
  (:require [clojure.core.async :as async :refer [chan go-loop <! >! close!]]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import [java.io InputStream])) 

;; Pattern to match SSE events in the format "data: {...}\n\n"
(def ^:private sse-pattern #"data: (.*?)(\r\n|\n){2}")

(defn read-input-stream
  "Reads an InputStream into a channel of byte arrays."
  [^InputStream input-stream buffer-size]
  (let [out (chan)
        buffer (byte-array buffer-size)]
    (future
      (try
        (loop []
          (let [bytes-read (.read input-stream buffer 0 buffer-size)]
            (cond
              (pos? bytes-read)
              (let [bytes-chunk (byte-array bytes-read)]
                (System/arraycopy buffer 0 bytes-chunk 0 bytes-read)
                (async/>!! out bytes-chunk)
                (recur))

              :else
              (close! out))))
        (catch Exception e
          (async/>!! out e))
        (finally
          (.close input-stream))))
    out))

(defn process-byte-chunks
  "Processes byte chunks into string data with tracking for incomplete chunks."
  [byte-stream]
  (let [out (chan)]
    (go-loop [remainder ""]
      (if-let [chunk (<! byte-stream)]
        (if (instance? Exception chunk)
          (do (>! out chunk)
              (close! out))
          (let [new-data (str remainder (String. ^bytes chunk "UTF-8"))
                last-complete-idx (or (str/last-index-of new-data "\n\n") 0)
                complete-part (subs new-data 0 last-complete-idx)
                remaining-part (subs new-data last-complete-idx)]
            (>! out [complete-part remaining-part])
            (recur remaining-part)))
        (close! out)))
    out))

(defn extract-events
  "Extracts events from string data using a regex pattern."
  [string-stream]
  (let [out (chan)]
    (go-loop []
      (let [data (<! string-stream)]
        (if data (if (instance? Exception data)
                   (do (>! out data) (close! out))
                   (let [[text _] data
                         events (re-seq sse-pattern text)]
                     (when events (>! out events))
                     (recur)))
            (close! out))))
    out))

(defn extract-event-data
  "Extracts the JSON data from each event."
  [events-stream]
  (let [out (chan)]
    (go-loop []
      (if-let [events (<! events-stream)]
        (if (instance? Exception events)
          (do (>! out events)
              (close! out))
          (do
            (doseq [event events]
              (when event
                (>! out (nth event 1 nil))))
            (recur)))
        (close! out)))
    out))

(defn parse-json
  "Parses JSON strings and applies a transform function."
  [events-stream transform-fn done-marker]
  (let [out (chan)]
    (go-loop []
      (if-let [event-data (<! events-stream)]

         (cond
           (instance? Exception event-data)
           (do (>! out event-data)
               (close! out))

           (= event-data done-marker)
           (do (>! out ::done)
               (recur))

           (nil? event-data)
           (do (>! out ::no-content)
               (recur))

           :else
           (let [result
                 (try
                   (let [parsed-data (json/parse-string event-data true)
                         [ev-type ev-data] (transform-fn parsed-data)]
                     (cond
                       (= ev-type :content) ev-data
                       (= ev-type :error) (Exception. ev-data)
                       (= ev-type :finish-reason) ::done
                       :else nil))
                   (catch Exception e e))]
             (>! out result)
             (recur)))
        (close! out)))
    out))

(defn filter-valid-content
  "Filters out nil values, done markers, and other non-content items."
  [content-stream]
  (let [out (chan)]
    (go-loop []
      (if-let [content (<! content-stream)]
        (do
          (when (and (not (instance? Exception content))
                     (not= content ::done)
                     (not= content ::no-content)
                     (some? content))
            (>! out content))
          (recur))
        (close! out)))
    out))

(defn handle-stream-errors
  "Converts exceptions to error messages in the stream."
  [content-stream out]
  (go-loop []
    (if-let [content (<! content-stream)]
      (do
        (if (instance? Exception content)
          (>! out (str "Error: " (.getMessage content)))
          (>! out content))
        (recur))
      (close! out))))

(defn process-sse
  "Processes an SSE stream from an InputStream, extracting and transforming the content.

   Parameters:
   - input-stream: The source InputStream
   - transform-fn: Function applied to each parsed JSON object. Must return either [:content content], [:error error], [:finish finish-reason], or nil.
   - done-marker: String that marks the end of the stream (default \"[DONE]\")
   - buffer-size: Size of buffer for reading (default 16KB)"
  ([^InputStream input-stream transform-fn]
   (process-sse input-stream transform-fn "[DONE]" 16384))
  ([^InputStream input-stream transform-fn done-marker buffer-size]
   (let [result-chan (chan)

         ;; Create the processing pipeline
         bytes-chan (read-input-stream input-stream buffer-size)
         strings-chan (process-byte-chunks bytes-chan)
         events-chan (extract-events strings-chan)
         data-chan (extract-event-data events-chan)
         parsed-chan (parse-json data-chan transform-fn done-marker)
         filtered-chan (filter-valid-content parsed-chan)]

     ;; Handle errors and connect to result channel
     (handle-stream-errors filtered-chan result-chan)

     result-chan)))

;; Helper function to collect all values from a channel
(defn collect-channel
  "Collects all values from a channel into a vector."
  [ch]
  (loop [result []]
    (if-let [value (async/<!! ch)]
      (recur (conj result value))
      result)))
