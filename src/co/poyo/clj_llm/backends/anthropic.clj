(ns co.poyo.clj-llm.backends.anthropic
  "Anthropic API provider implementation"
  (:require
   [camel-snake-kebab.core :as csk]
   [camel-snake-kebab.extras :as cske]
   [cheshire.core :as json]
   [clojure.set]
   [clojure.core.async :as a :refer [go-loop <! >! chan close!]]
   [co.poyo.clj-llm.schema :as schema]
   [co.poyo.clj-llm.protocol :as proto]
   [co.poyo.clj-llm.sse :as sse]))

(def ^:private default-config
  {:api-base "https://api.anthropic.com"
   :api-version "2023-06-01"})

(defn- default-api-key-fn
  "Default: read API key from ANTHROPIC_API_KEY env var."
  []
  (or (System/getenv "ANTHROPIC_API_KEY")
      (System/getProperty "ANTHROPIC_API_KEY")))

(def ^:private ->snake-key (memoize csk/->snake_case_keyword))

(defn- convert-options-for-api [opts]
  (when opts
    (cske/transform-keys ->snake-key opts)))

(defn- normalize-messages [messages]
  (mapv #(clojure.set/rename-keys % {:tool-calls :tool_calls
                                     :tool-call-id :tool_call_id}) messages))

(defn- build-body
  "Build Anthropic API request body"
  [model system-prompt messages schema tools tool-choice opts]
  (let [messages (normalize-messages messages)
        tools-config (cond
                       tools
                       (cond-> {:tools (mapv schema/malli->tool-definition tools)}
                         (not= tool-choice "none")
                         (assoc :tool_choice (cond
                                              (= tool-choice "auto") {:type "auto"}
                                              ;; Anthropic calls "required" → "any" (different vocabulary)
                                              (= tool-choice "required") {:type "any"}
                                              :else (or tool-choice {:type "auto"}))))

                       schema
                       {:tools [(schema/malli->tool-definition schema)]
                        :tool_choice {:type "any"}})
        api-opts (convert-options-for-api opts)
        max-tokens (or (:max_tokens api-opts) 4096)
        base-body (merge
                   {:model model
                    :max_tokens max-tokens
                    :messages messages
                    :stream true}
                   api-opts
                   tools-config)]
    (if system-prompt
      (assoc base-body :system system-prompt)
      base-body)))

(defn- data->internal-events
  "Convert Anthropic SSE event to a seq of internal events (or nil).
   In schema mode, tool input JSON is treated as content.
   In tools mode, tool use blocks are emitted as :tool-call events."
  [data schema tools]
  (case (:type data)
    "content_block_delta"
    (cond
      (not-empty (get-in data [:delta :text]))
      [{:type :content :content (get-in data [:delta :text])}]

      (and schema (not-empty (get-in data [:delta :partial-json])))
      [{:type :content :content (get-in data [:delta :partial-json])}]

      (and tools (not-empty (get-in data [:delta :partial-json])))
      [{:type :tool-call-delta
        :index (:index data)
        :arguments (get-in data [:delta :partial-json])}]

      :else nil)

    "content_block_start"
    (when (and tools (= "tool_use" (get-in data [:content_block :type])))
      [{:type :tool-call
        :id (get-in data [:content_block :id])
        :index (:index data)
        :name (get-in data [:content_block :name])
        :arguments ""}])

    "message_delta"
    (when-let [usage (:usage data)]
      [(into {:type :usage} usage)])

    "message_stop"
    [{:type :done}]

    "message_start"
    (when-let [usage (get-in data [:message :usage])]
      [(into {:type :usage} usage)])

    ;; Lifecycle events with no useful payload — skip
    ("content_block_stop" "ping")
    nil

    "error"
    [{:type :error :error (:error data)}]

    ;; Unknown event type
    nil))

;; ==========================================
;; Anthropic Backend
;; ==========================================

(defrecord AnthropicBackend [api-base api-key-fn api-version defaults]
  proto/LLMProvider
  (request-stream [_ {:keys [model system-prompt messages schema tools tool-choice provider-opts]}]
    (let [api-key (api-key-fn)
          _ (when-not api-key
              (throw (ex-info "API key function returned nil"
                              {:provider "anthropic"})))
          url (str api-base "/v1/messages")
          headers {"x-api-key" api-key
                   "anthropic-version" api-version
                   "Content-Type" "application/json"}
          body (json/generate-string (build-body model system-prompt messages schema tools tool-choice provider-opts))]
      (let [raw-ch (sse/create-event-stream url headers body "anthropic")
            out-ch (chan 1024)]
        (go-loop []
          (if-let [data (<! raw-ch)]
            (do (if (= :error (:type data))
                  (>! out-ch data)
                  (doseq [e (data->internal-events data schema tools)]
                    (>! out-ch e)))
                (recur))
            (close! out-ch)))
        out-ch))))

(defn backend
  "Create an Anthropic provider.
   Config: :api-key, :api-key-fn, :api-base, :api-version.
   Default reads ANTHROPIC_API_KEY env var."
  ([] (backend {}))
  ([{:keys [api-key api-key-fn api-base api-version]}]
   (->AnthropicBackend
    (or api-base (:api-base default-config))
    (cond api-key-fn api-key-fn
          api-key    (constantly api-key)
          :else      default-api-key-fn)
    (or api-version (:api-version default-config))
    {})))

(defmethod print-method AnthropicBackend [b writer]
  (let [model (get-in b [:defaults :model])]
    (.write writer "#Anthropic")
    (when model
      (.write writer (str " " (pr-str model))))
    (.write writer (str " " (pr-str (:api-base b))))))
