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

(defn event-xf
  "Stateful transducer: lines → SSE event maps.
   Each emitted event is {:event \"...\" :data \"...\"}.
   :event defaults to \"message\" per spec.
   Multi-line data fields are joined with newlines."
  []
  (fn [rf]
    (let [buf (volatile! {:event "message" :data []})]
      (fn
        ([] (rf))
        ([result] (rf result))
        ([result line]
         (let [parsed (parse-sse-line line)]
           (cond
             (nil? parsed) result                      ;; comment — skip

             (= :dispatch parsed)                       ;; blank line — dispatch
             (let [{:keys [event data]} @buf]
               (vreset! buf {:event "message" :data []})
               (if (seq data)
                 (rf result {:event event :data (str/join "\n" data)})
                 result))

             :else                                     ;; field/value pair
             (let [[field value] parsed]
               (case field
                 :data  (vswap! buf update :data conj value)
                 :event (vswap! buf assoc :event value)
                 ;; :id, :retry — capture if needed in the future
                 nil)
               result))))))))

(defn done?
  "Returns true if the SSE data field signals end-of-stream.
   Handles the common \"[DONE]\" convention used by OpenAI/Anthropic."
  [^String data]
  (= data "[DONE]"))
