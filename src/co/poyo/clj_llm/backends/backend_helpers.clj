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
  "Convert kebab-case options to snake_case format for API calls"
  [opts]
  (when opts
    (walk/postwalk
     (fn [x]
       (if (map? x)
         (update-keys x csk/->snake_case_keyword)
         x))
     opts)))

(def ^:private message-key-renames
  {:tool-calls :tool_calls
   :tool-call-id :tool_call_id})

(defn normalize-messages
  "Convert kebab-case keys in messages to snake_case for API compatibility.
   Handles :tool-calls -> :tool_calls, :tool-call-id -> :tool_call_id."
  [messages]
  (mapv #(clojure.set/rename-keys % message-key-renames) messages))

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
   - event->internal: (data -> seq-of-events | nil)
   - provider-name: string for error messages

   Returns a channel of internal events (maps with :type key)."
  [url headers body event->internal provider-name]
  (let [events-chan (chan 1024)]
    (net/post-stream url headers body
                     (fn [response]
                       (cond
                         (:error response)
                         (go
                           (>! events-chan {:type :error
                                           :error (.getMessage (:error response))
                                           :exception (:error response)})
                           (close! events-chan))

                         (= 200 (:status response))
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
                                     (if-let [evts (seq (event->internal (::sse/data chunk)))]
                                       (let [done? (loop [es evts]
                                                      (if es
                                                        (let [e (first es)]
                                                          (>! events-chan e)
                                                          (if (= :done (:type e))
                                                            true
                                                            (recur (next es))))
                                                        false))]
                                         (when-not done?
                                           (recur)))
                                       (recur)))))
                               (catch Exception e
                                 (>! events-chan {:type :error :error e}))
                               (finally
                                 (close! events-chan)))))
                         :else
                         (go
                           (>! events-chan (handle-error-response provider-name response))
                           (close! events-chan)))))
    events-chan))
