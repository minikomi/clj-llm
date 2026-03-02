(ns co.poyo.clj-llm.backends.backend-helpers
  "Shared utilities for LLM backend implementations."
  (:require
   [camel-snake-kebab.core :as csk]
   [cheshire.core :as json]
   [clojure.core.async :as a :refer [chan go <! >! close!]]
   [clojure.set]
   [clojure.walk :as walk]
   [co.poyo.clj-llm.net :as net]
   [co.poyo.clj-llm.sse :as sse]
   [co.poyo.clj-llm.errors :as errors]))

(defn convert-options-for-api
  "Convert kebab-case option keys to snake_case for API calls."
  [opts]
  (when opts
    (walk/postwalk
     (fn [x] (if (map? x) (update-keys x csk/->snake_case_keyword) x))
     opts)))

(def ^:private message-key-renames
  {:tool-calls :tool_calls
   :tool-call-id :tool_call_id})

(defn normalize-messages
  "Rename kebab-case keys to snake_case in messages for API compatibility."
  [messages]
  (mapv #(clojure.set/rename-keys % message-key-renames) messages))

(defn- parse-error-body
  "Read response body as string, attempt JSON parse."
  [response]
  (let [body-str (cond
                   (string? (:body response)) (:body response)
                   (instance? java.io.InputStream (:body response)) (slurp (:body response))
                   :else (str (:body response)))]
    (try (json/parse-string body-str true)
         (catch Exception _ body-str))))

(defn- error-event
  "Build an :error event map from a provider error response."
  [provider-name response]
  (try
    (let [body (parse-error-body response)
          ex (errors/parse-http-error provider-name (:status response) body)]
      {:type :error :error (.getMessage ex) :status (:status response)
       :provider-error body :exception ex})
    (catch Exception e
      {:type :error
       :error (str "HTTP " (:status response) ": " (:body response))
       :status (:status response)
       :exception (errors/error (str "Failed to parse error: " (.getMessage e))
                                {:response response})})))

(defn- pipe-sse-events
  "Pipe SSE events through parse-sse-data into out-chan."
  [sse-chan parse-sse-data out-chan]
  (go
    (try
      (loop []
        (when-let [chunk (<! sse-chan)]
          (cond
            (::sse/done chunk)
            (>! out-chan {:type :done})

            (::sse/error chunk)
            (do (>! out-chan {:type :error :error (::sse/error chunk)})
                (recur))

            :else
            (let [evts (seq (parse-sse-data (::sse/data chunk)))
                  done? (when evts
                          (loop [[e & more] evts]
                            (>! out-chan e)
                            (if (= :done (:type e))
                              true
                              (when more (recur more)))))]
              (when-not done? (recur))))))
      (catch Exception e
        (>! out-chan {:type :error :error e}))
      (finally
        (close! out-chan)))))

(defn create-event-stream
  "POST to a streaming API and return a channel of internal events.
   parse-sse-data: (data-map -> seq-of-event-maps | nil)"
  [url headers body parse-sse-data provider-name]
  (let [out (chan 1024)]
    (net/post-stream url headers body
      (fn [{:keys [error status body] :as response}]
        (cond
          error
          (go (>! out {:type :error :error (.getMessage error) :exception error})
              (close! out))

          (= 200 status)
          (pipe-sse-events (sse/parse-sse body) parse-sse-data out)

          :else
          (go (>! out (error-event provider-name response))
              (close! out)))))
    out))
