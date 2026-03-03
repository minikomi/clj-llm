(ns co.poyo.clj-llm.sse
  "Pure SSE text parsing per the EventSource spec.
   Turns a stream of lines into SSE events — no HTTP, no JSON, no I/O."
  (:require [clojure.string :as str]))

(defn parse-sse-line
  "Parse a single SSE line into a field/value pair, or a sentinel.
   Returns one of:
     [:data  \"...\"]   — a data field
     [:event \"...\"]   — an event-type field
     [:id    \"...\"]   — a last-event-id field
     [:retry \"...\"]   — a reconnection-time field
     :dispatch         — blank line = dispatch the current event
     nil               — comment or unrecognized (skip)"
  [^String line]
  (cond
    (str/blank? line)           :dispatch
    (str/starts-with? line ":") nil          ;; comment
    :else
    (let [idx (.indexOf line (int \:))]
      (if (neg? idx)
        ;; Field with no colon — value is empty string per spec
        [(keyword line) ""]
        (let [field (subs line 0 idx)
              ;; Strip optional single leading space after colon
              value (let [rest (subs line (inc idx))]
                      (if (str/starts-with? rest " ")
                        (subs rest 1)
                        rest))]
          [(keyword field) value])))))

(defn parse-events
  "Parse a seq of SSE lines into a lazy seq of event maps.
   Each event is {:event \"...\" :data \"...\"}.
   :event defaults to \"message\" per spec.
   Multi-line data fields are joined with newlines."
  [lines]
  (lazy-seq
   (loop [lines lines
          event "message"
          data  []]
     (if-let [line (first lines)]
       (let [parsed (parse-sse-line line)]
         (cond
           (nil? parsed)
           (recur (rest lines) event data)

           (= :dispatch parsed)
           (if (seq data)
             (cons {:event event :data (str/join "\n" data)}
                   (parse-events (rest lines)))
             (recur (rest lines) "message" []))

           :else
           (let [[field value] parsed]
             (case field
               :data  (recur (rest lines) event (conj data value))
               :event (recur (rest lines) value data)
               (recur (rest lines) event data)))))
       (when (seq data)
         (list {:event event :data (str/join "\n" data)}))))))

