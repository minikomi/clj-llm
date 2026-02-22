(ns co.poyo.clj-llm.backends.anthropic
  "Anthropic API provider implementation"
  (:require
   [cheshire.core :as json]
   [co.poyo.clj-llm.schema :as schema]
   [co.poyo.clj-llm.protocol :as proto]
   [co.poyo.clj-llm.errors :as errors]
   [co.poyo.clj-llm.backends.backend-helpers :as bh]))

(def ^:private default-config
  {:api-key-env "ANTHROPIC_API_KEY"
   :api-base "https://api.anthropic.com"
   :api-version "2023-06-01"})

(defn- build-body
  "Build Anthropic API request body"
  [model system-prompt messages schema tools tool-choice opts]
  (let [messages (bh/normalize-messages messages)
        tools-config (cond
                       tools
                       {:tools (mapv schema/malli->json-schema tools)
                        :tool_choice (cond
                                       (= tool-choice "auto") {:type "auto"}
                                       (= tool-choice "required") {:type "any"}
                                       (= tool-choice "none") nil
                                       :else (or tool-choice {:type "auto"}))}

                       schema
                       {:tools [(schema/malli->json-schema schema)]
                        :tool_choice {:type "any"}})
        api-opts (bh/convert-options-for-api opts)
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

(defn- data->internal-event
  "Convert Anthropic SSE event to our internal event format.
   In schema mode, tool input JSON is treated as content.
   In tools mode, tool use blocks are emitted as :tool-call events."
  [data schema tools]
  (case (:type data)
    "content_block_delta"
    (cond
      (not-empty (get-in data [:delta :text]))
      {:type :content :content (get-in data [:delta :text])}

      (and schema (not-empty (get-in data [:delta :partial-json])))
      {:type :content :content (get-in data [:delta :partial-json])}

      (and tools (not-empty (get-in data [:delta :partial-json])))
      {:type :tool-call-delta
       :index (:index data)
       :arguments (get-in data [:delta :partial-json])}

      :else nil)

    "content_block_start"
    (when (and tools (= "tool_use" (get-in data [:content_block :type])))
      {:type :tool-call
       :id (get-in data [:content_block :id])
       :index (:index data)
       :name (get-in data [:content_block :name])
       :arguments ""})

    "message_delta"
    (when-let [usage (:usage data)]
      (into {:type :usage} usage))

    "message_stop"
    {:type :done}

    ("message_start" "content_block_stop" "ping")
    nil

    "error"
    {:type :error :error (:error data)}

    ;; Unknown event type
    nil))

;; ==========================================
;; Anthropic Backend
;; ==========================================

(defrecord AnthropicBackend [api-base api-key api-version defaults]
  proto/LLMProvider
  (request-stream [_ model system-prompt messages schema tools tool-choice provider-opts]
    (let [url (str api-base "/v1/messages")
          headers {"x-api-key" api-key
                   "anthropic-version" api-version
                   "Content-Type" "application/json"}
          body (json/generate-string (build-body model system-prompt messages schema tools tool-choice provider-opts))]
      (bh/create-event-stream url headers body
                                  #(data->internal-event % schema tools)
                                  "anthropic"))))

(def ^:private anthropic-config-keys #{:api-key :api-key-env :api-base :api-version})

(defn backend
  "Create an Anthropic provider. Config keys:
    :api-key     - API key string
    :api-key-env - env var name (default: ANTHROPIC_API_KEY)
    :api-base    - API base URL (default: https://api.anthropic.com)
    :api-version - API version (default: 2023-06-01)

   Set :defaults on the provider to configure model, system-prompt, schema, etc."
  ([] (backend {}))
  ([config]
   (let [unknown (seq (remove anthropic-config-keys (keys config)))]
     (when unknown
       (throw (errors/error
               (str "Unknown provider config keys: " (pr-str (vec unknown))
                    ". Set :defaults on the provider for prompt options.")
               {:unknown-keys (vec unknown)
                :valid-keys anthropic-config-keys}))))
   (let [api-env     (or (:api-key-env config) (:api-key-env default-config))
         api-key     (or (:api-key config)
                         (System/getenv api-env)
                         (System/getProperty api-env))
         api-base    (or (:api-base config) (:api-base default-config))
         api-version (or (:api-version config) (:api-version default-config))]
     (when-not api-key
       (throw (errors/error "Missing API key"
                            {:provider "anthropic" :api-key-env api-env})))
     (->AnthropicBackend api-base api-key api-version {}))))

(defmethod print-method AnthropicBackend [backend writer]
  (let [model (get-in backend [:defaults :model])]
    (.write writer "#Anthropic")
    (when model
      (.write writer (str " " (pr-str model))))
    (.write writer (str " " (pr-str (:api-base backend))))))
