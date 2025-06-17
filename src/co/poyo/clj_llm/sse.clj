(ns co.poyo.clj-llm.sse
  "Server-Sent Events (SSE) parsing for streaming LLM responses.
   Works with both Clojure and Babashka."
  (:require [clojure.string :as str]
            [clojure.core.async :as a :refer [chan go-loop <! >! close!]]
            [clojure.java.io :as io]))

(defn- parse-sse-line
  "Parse a single SSE line into a map.
   Returns nil for empty lines or comments."
  [line]
  (cond
    ;; Empty line - signals end of event
    (str/blank? line) nil

    ;; Comment line
    (str/starts-with? line ":") nil

    ;; Field line
    :else
    (let [colon-idx (str/index-of line ":")]
      (if colon-idx
        (let [field (subs line 0 colon-idx)
              value (str/trim (subs line (inc colon-idx)))]
          {field value})
        ;; Line with no colon is treated as field with empty value
        {line ""}))))

(defn parse-sse
  "Parse SSE stream from an InputStream into a channel of events.
   
   Each event is a map with the SSE fields as keys.
   Common fields are 'data', 'event', 'id', 'retry'.
   
   The channel closes when the stream ends or encounters an error.
   
   Example output:
     {\"data\" \"...json...\"}
     {\"event\" \"message\"}
     {\"data\" \"[DONE]\"}
   
   Args:
     input-stream - Java InputStream containing SSE data
     
   Returns:
     core.async channel of parsed SSE events"
  [input-stream]
  (let [out-chan (chan 1024)]
    (go-loop [reader (io/reader input-stream)
              current-event {}]
      (if-let [line (.readLine reader)]
        (if (str/blank? line)
          ;; Empty line - emit event if we have one
          (if (seq current-event)
            (do
              (>! out-chan current-event)
              (recur reader {}))
            (recur reader {}))
          ;; Parse line and add to current event
          (if-let [parsed (parse-sse-line line)]
            (recur reader (merge current-event parsed))
            (recur reader current-event)))
        ;; Stream ended
        (do
          ;; Emit final event if any
          (when (seq current-event)
            (>! out-chan current-event))
          (.close reader)
          (close! out-chan))))

    out-chan))

(defn parse-sse-string
  "Parse SSE from a string (useful for testing).
   
   Args:
     sse-string - String containing SSE data
     
   Returns:
     Vector of parsed events"
  [sse-string]
  (let [lines (str/split-lines sse-string)]
    (loop [remaining lines
           current-event {}
           events []]
      (if (empty? remaining)
        ;; Add final event if any
        (if (seq current-event)
          (conj events current-event)
          events)
        (let [line (first remaining)]
          (if (str/blank? line)
            ;; Empty line - complete current event
            (if (seq current-event)
              (recur (rest remaining) {} (conj events current-event))
              (recur (rest remaining) {} events))
            ;; Parse line
            (if-let [parsed (parse-sse-line line)]
              (recur (rest remaining) (merge current-event parsed) events)
              (recur (rest remaining) current-event events))))))))