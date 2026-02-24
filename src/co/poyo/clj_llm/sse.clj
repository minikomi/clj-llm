(ns co.poyo.clj-llm.sse
  "Simple SSE parsing for streaming responses"
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.core.async :as a :refer [chan >!! close! thread]]
   [clojure.java.io :as io]
   [clojure.walk :as walk]
   [camel-snake-kebab.core :as csk]))

(defn- json->kebab
  "Parse JSON string and transform all keys to kebab-case"
  [json-str]
  (->> json-str
       (json/parse-string)
       (walk/postwalk
        (fn [x]
          (if (map? x)
            (update-keys x (comp keyword csk/->kebab-case))
            x)))))

(defn- default-sse-parser
  "Default SSE parser - extracts 'data:' lines and attempts JSON parsing.
   Ignores 'event:' lines (type is in the JSON data itself).
   Returns {::data parsed-json}, {::data {::unparsed raw-string}}, or {::done true}"
  [line]
  (cond
    ;; OpenAI/Anthropic encode event type inside the JSON payload,
    ;; so the SSE event: field is redundant — skip it.
    (str/starts-with? line "event:")
    nil

    ;; Parse data: lines
    (str/starts-with? line "data:")
    (let [raw-data (str/trim (subs line 5))]
      (if (= raw-data "[DONE]")
        {::done true}
        (try
          {::data (json->kebab raw-data)}
          (catch Exception _
            ;; Non-JSON data lines (e.g. keep-alive pings) — safe to skip.
            ;; Consumer in backend_helpers checks ::unparsed and recurs.
            {::data {::unparsed raw-data}}))))

    ;; Empty line or other
    :else
    nil))

(defn parse-sse
  "Parse SSE stream from an InputStream into a channel of events.

   Options:
   - :buffer-size - Channel buffer size (default: 1024)
   - :parser-fn - Function that takes a line and returns event map or nil (default: stateful parser)
                  Parser can emit {::done true} to signal end of stream

   Returns channel of event maps: {::data ...}, {::done true}, or {::error ...}"
  ([input-stream] (parse-sse input-stream {}))
  ([input-stream {:keys [buffer-size parser-fn]
                  :or {buffer-size 1024
                       parser-fn default-sse-parser}}]
   (let [out (chan buffer-size)]
     (thread
       (try
         (with-open [reader (io/reader input-stream)]
           (loop []
             (when-let [line (.readLine reader)]
               (if-let [event (parser-fn line)]
                 (do
                   (>!! out event)
                   (when-not (::done event)
                     (recur)))
                 (recur)))))
         (catch Exception e
           (>!! out {::error e}))
         (finally
           (close! out))))
     out)))
