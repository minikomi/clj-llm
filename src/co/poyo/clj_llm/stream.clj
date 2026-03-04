(ns co.poyo.clj-llm.stream
  "HTTP → bounded core.async channel of decoded SSE events."
  (:require
   [camel-snake-kebab.core :as csk]
   [camel-snake-kebab.extras :as cske]
   [cheshire.core :as json]
   [clojure.core.async :as a]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [co.poyo.clj-llm.net :as net])
  (:import (java.io BufferedReader InputStream)))

;; ════════════════════════════════════════════════════════════════════
;; SSE parsing
;; ════════════════════════════════════════════════════════════════════

(def ^:private ->kebab-key (memoize csk/->kebab-case-keyword))

(defn parse-data-line
  "Parse one SSE text line into a decoded map.
   Returns nil for non-data lines, blank/[DONE], and invalid JSON."
  [^String line]
  (when (str/starts-with? line "data:")
    (let [data (str/trim (subs line 5))]
      (when-not (or (str/blank? data)
                    (= "[DONE]" data))
        (try
          (cske/transform-keys ->kebab-key (json/parse-string data))
          (catch Exception _
            nil))))))

(defn open-event-stream
  "POST to an SSE endpoint, return a bounded core.async channel of
   decoded SSE data maps.  Closing the channel cancels the HTTP request.

   Throws on connection errors (directly from java.net.http) and
   non-200 responses (ex-info with :status and :body from the API)."
  [url headers body]
  (let [{:keys [status body]} (net/post-stream url headers body)]
    (when (not= 200 status)
      (let [response-body (try (slurp ^InputStream body) (catch Exception _ nil))]
        (throw (ex-info (str "HTTP " status (when response-body (str ": " response-body)))
                        (cond-> {:status status}
                          response-body (assoc :body response-body))))))
    (let [ch (a/chan 256)]
      (a/thread
        (try
          (with-open [^BufferedReader rdr (io/reader ^InputStream body)]
            (loop []
              (when-let [line (.readLine rdr)]
                (let [evt (parse-data-line line)]
                  (cond
                    (nil? evt)      (recur)   ; non-data line, skip
                    (a/>!! ch evt)  (recur)   ; delivered, continue
                    :else           nil)))))  ; channel closed, stop
          (catch Exception e
            (when-not (.isInterrupted (Thread/currentThread))
              (a/>!! ch e)))
          (finally
            (a/close! ch))))
      ch)))
