(ns co.poyo.clj-llm.stream
  "HTTP → bounded core.async channel of decoded SSE events."
  (:require
   [camel-snake-kebab.core  :as csk]
   [camel-snake-kebab.extras :as cske]
   [cheshire.core           :as json]
   [clojure.core.async      :as a]
   [clojure.java.io         :as io]
   [clojure.string          :as str]
   [co.poyo.clj-llm.net     :as net])
  (:import (java.io InputStream)))

;; ════════════════════════════════════════════════════════════════════
;; SSE parsing
;; ════════════════════════════════════════════════════════════════════

(def ^:private ->kebab-key (memoize csk/->kebab-case-keyword))

(defn parse-sse-data
  "Extract and parse JSON from a 'data: …' SSE line.
   Returns a decoded map, or nil for non-data / blank / [DONE].
   Returns an :error map for unparseable JSON so the stream doesn't silently
   swallow provider errors."
  [^String line]
  (when (str/starts-with? line "data:")
    (let [payload (str/trim (subs line 5))]
      (cond
        (empty? payload) nil
        (= "[DONE]" payload) nil
        :else (try
                (cske/transform-keys ->kebab-key (json/parse-string payload))
                (catch Exception _
                  ;; Emit as an error event — the provider's parse-chunk
                  ;; will surface it through the normal error path.
                  {:type "error"
                   :error {:message (str "Unparseable SSE data: " payload)}}))))))

;; ════════════════════════════════════════════════════════════════════
;; HTTP streaming
;; ════════════════════════════════════════════════════════════════════

(defn- check-status!
  "Throws ex-info for non-200 responses, draining the body for the message."
  [{:keys [^InputStream body status]}]
  (when (not= 200 status)
    (let [body-str (try (slurp body) (catch Exception _ nil))]
      (throw (ex-info (cond-> (str "HTTP " status)
                        body-str (str ": " body-str))
                      (cond-> {:status status}
                        body-str (assoc :body body-str)))))))

(defn open-event-stream [url headers body]
  (let [{:keys [^InputStream body] :as response} (net/post-stream url headers body)]
    (try
      (check-status! response)
      (let [ch (a/chan 256 (keep parse-sse-data))]
        (a/thread
          (try
            (with-open [rdr (io/reader body)]
              (loop []
                (when-let [line (.readLine rdr)]
                  (when (a/>!! ch line)
                    (recur)))))
            (catch Exception e
              (when-not (.isInterrupted (Thread/currentThread))
                (a/>!! ch e)))
          (finally
            (a/close! ch))))
        ch)
      (catch Exception e
        (.close body)
        (throw e)))))
