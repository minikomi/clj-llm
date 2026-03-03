(ns co.poyo.clj-llm.sse
  "SSE text parsing and simple data payload decoding.
   Pure transforms only — no HTTP, no I/O."
  (:require
   [camel-snake-kebab.core :as csk]
   [camel-snake-kebab.extras :as cske]
   [cheshire.core :as json]
   [clojure.string :as str]))

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
  "Lines → SSE event maps ({:event \"...\" :data \"...\"}).
   :event defaults to \"message\" per spec.
   Multi-line data fields are joined with newlines.

   (parse-events lines)  → lazy seq
   (parse-events)        → transducer"
  ([lines]
   (sequence (parse-events) lines))
  ([]
   (fn [rf]
     (let [event (volatile! "message")
           data  (volatile! (transient []))]
       (fn
         ([] (rf))
         ([result]
          (let [d (persistent! @data)]
            (rf (if (seq d)
                  (rf result {:event @event :data (str/join "\n" d)})
                  result))))
         ([result line]
          (let [parsed (parse-sse-line line)]
            (cond
              (nil? parsed) result

              (= :dispatch parsed)
              (let [d (persistent! @data)
                    e @event]
                (vreset! event "message")
                (vreset! data (transient []))
                (if (seq d)
                  (rf result {:event e :data (str/join "\n" d)})
                  result))

              :else
              (let [[field value] parsed]
                (case field
                  :data  (vswap! data conj! value)
                  :event (vreset! event value)
                  nil)
                result)))))))))


(def ^:private ->kebab-key (memoize csk/->kebab-case-keyword))

(defn parse-data-lines
  "Simple SSE extraction for providers that emit one JSON object per data line.

   Keeps only `data:` lines, trims payload, drops blank/[DONE], parses JSON,
   and kebab-cases map keys.

   (parse-data-lines lines) -> lazy seq of decoded maps
   (parse-data-lines)       -> transducer"
  ([lines]
   (sequence (parse-data-lines) lines))
  ([]
   (comp
    (keep (fn [^String line]
            (when (str/starts-with? line "data:")
              (let [data (str/trim (subs line 5))]
                (when-not (or (str/blank? data)
                              (= "[DONE]" data))
                  (try
                    (cske/transform-keys ->kebab-key
                                         (json/parse-string data))
                    (catch Exception _
                      nil))))))))))
