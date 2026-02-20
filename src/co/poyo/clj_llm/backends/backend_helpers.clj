(ns co.poyo.clj-llm.backends.backend-helpers
  "Shared utilities for LLM backend implementations."
  (:require
   [camel-snake-kebab.core :as csk]
   [cheshire.core :as json]
   [clojure.core.async :as a :refer [chan go <! >! close!]]
   [co.poyo.clj-llm.net :as net]
   [co.poyo.clj-llm.sse :as sse]
   [co.poyo.clj-llm.errors :as errors]
   [clojure.walk :as walk]))

(defn convert-options-for-api
  "Convert kebab-case options to snake_case format for API calls"
  [opts]
  (when opts
    (walk/postwalk
     (fn [x]
       (if (map? x)
         (update-keys x csk/->snake_case_keyword)
         x))
     opts)))

(defn handle-error-response
  "Parse error response from API and create appropriate error event.
   provider-name is a string like \"openai\" or \"anthropic\"."
  [provider-name response]
  (try
    (let [body-str (cond
                     (string? (:body response)) (:body response)
                     (instance? java.io.InputStream (:body response)) (slurp (:body response))
                     :else (str (:body response)))
          body (try
                 (json/parse-string body-str true)
                 (catch Exception _
                   body-str))
          status (:status response)
          error (errors/parse-http-error provider-name status body)]
      {:type :error
       :error (.getMessage error)
       :status status
       :provider-error body
       :exception error})
    (catch Exception e
      {:type :error
       :error (str "HTTP " (:status response) ": " (:body response))
       :status (:status response)
       :exception (errors/error
                   (str "Failed to parse error response: " (.getMessage e))
                   {:response response})})))

(defn create-event-stream
  "Create an event channel from an SSE streaming API call.

   Takes:
   - url: full API endpoint URL
   - headers: HTTP headers map
   - body: JSON string request body
   - event->internal: fn that converts parsed SSE data to internal event format,
                      receives the parsed data map, returns internal event or nil
   - provider-name: string for error messages

   Returns a channel of internal events (maps with :type key)."
  [url headers body event->internal provider-name]
  (let [events-chan (chan 1024)]
    (net/post-stream url headers body
                     (fn handle-response [response]
                       (if (= 200 (:status response))
                         (let [sse-chan (sse/parse-sse (:body response))]
                           (go
                             (try
                               (loop []
                                 (when-let [chunk (<! sse-chan)]
                                   (cond
                                     (::sse/done chunk)
                                     (>! events-chan {:type :done})

                                     (::sse/error chunk)
                                     (do
                                       (>! events-chan {:type :error :error (::sse/error chunk)})
                                       (recur))

                                     (get-in chunk [::sse/data ::sse/unparsed])
                                     (recur)

                                     :else
                                     (if-let [internal-event (event->internal (::sse/data chunk))]
                                       (do
                                         (>! events-chan internal-event)
                                         (when (not= :done (:type internal-event))
                                           (recur)))
                                       (recur)))))
                               (catch Exception e
                                 (>! events-chan {:type :error :error e}))
                               (finally
                                 (close! events-chan)))))
                         (go
                           (>! events-chan (handle-error-response provider-name response))
                           (close! events-chan)))))
    events-chan))
