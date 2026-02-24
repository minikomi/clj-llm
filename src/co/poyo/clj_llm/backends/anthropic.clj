(ns co.poyo.clj-llm.backends.anthropic
  "Anthropic API provider implementation"
  (:require
   [cheshire.core :as json]
   [co.poyo.clj-llm.schema :as schema]
   [co.poyo.clj-llm.protocol :as proto]
   [co.poyo.clj-llm.errors :as errors]
   [co.poyo.clj-llm.backends.backend-helpers :as backend]))

(def ^:private default-config
  {:api-base "https://api.anthropic.com"
   :api-version "2023-06-01"})

(defn- default-api-key-fn
  "Default: read API key from ANTHROPIC_API_KEY env var."
  []
  (or (System/getenv "ANTHROPIC_API_KEY")
      (System/getProperty "ANTHROPIC_API_KEY")))

(defn- build-body
  "Build Anthropic API request body"
  [model system-prompt messages output-schema tools tool-choice opts]
  (let [messages (backend/normalize-messages messages)
        tools-config (cond
                       tools
                       (cond-> {:tools (mapv schema/malli->tool-definition tools)}
                         (not= tool-choice "none")
                         (assoc :tool_choice (cond
                                              (= tool-choice "auto") {:type "auto"}
                                              ;; Anthropic calls "required" → "any" (different vocabulary)
                                              (= tool-choice "required") {:type "any"}
                                              :else (or tool-choice {:type "auto"}))))

                       output-schema
                       {:tools [(schema/malli->tool-definition output-schema)]
                        :tool_choice {:type "any"}})
        api-opts (backend/convert-options-for-api opts)
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
   In output-schema mode, tool input JSON is treated as content.
   In tools mode, tool use blocks are emitted as :tool-call events."
  [data output-schema tools]
  (case (:type data)
    "content_block_delta"
    (cond
      (not-empty (get-in data [:delta :text]))
      [{:type :content :content (get-in data [:delta :text])}]

      (and output-schema (not-empty (get-in data [:delta :partial-json])))
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
  (request-stream [_ model system-prompt messages output-schema tools tool-choice provider-opts]
    (let [api-key (api-key-fn)
          _ (when-not api-key
              (throw (errors/error "API key function returned nil"
                                   {:provider "anthropic"})))
          url (str api-base "/v1/messages")
          headers {"x-api-key" api-key
                   "anthropic-version" api-version
                   "Content-Type" "application/json"}
          body (json/generate-string (build-body model system-prompt messages output-schema tools tool-choice provider-opts))]
      (backend/create-event-stream url headers body
                                  #(data->internal-events % output-schema tools)
                                  "anthropic"))))

(def ^:private anthropic-config-keys #{:api-key :api-key-fn :api-base :api-version})

(defn backend
  "Create an Anthropic provider. Config keys:
    :api-key-fn  - 0-arg fn that returns the API key (called per request)
    :api-key     - API key string (convenience, wrapped in constantly)
    :api-base    - API base URL (default: https://api.anthropic.com)
    :api-version - API version (default: 2023-06-01)

   The default api-key-fn reads from the ANTHROPIC_API_KEY env var.
   Override for custom env vars, vaults, or key rotation:
     (backend {:api-key-fn (fn [] (System/getenv \"MY_KEY\"))})

   Set :defaults on the provider to configure model, system-prompt, output-schema, etc."
  ([] (backend {}))
  ([config]
   (let [unknown (seq (remove anthropic-config-keys (keys config)))]
     (when unknown
       (throw (errors/error
               (str "Unknown provider config keys: " (pr-str (vec unknown))
                    ". Set :defaults on the provider for prompt options.")
               {:unknown-keys (vec unknown)
                :valid-keys anthropic-config-keys}))))
   (let [api-key-fn (cond
                      (:api-key-fn config) (:api-key-fn config)
                      (:api-key config)    (constantly (:api-key config))
                      :else                default-api-key-fn)
         api-base    (or (:api-base config) (:api-base default-config))
         api-version (or (:api-version config) (:api-version default-config))]
     (->AnthropicBackend api-base api-key-fn api-version {}))))

(defmethod print-method AnthropicBackend [b writer]
  (let [model (get-in b [:defaults :model])]
    (.write writer "#Anthropic")
    (when model
      (.write writer (str " " (pr-str model))))
    (.write writer (str " " (pr-str (:api-base b))))))
