(ns co.poyo.clj-llm.backends.openai
  "OpenAI and OpenAI-compatible API provider implementation.
   
   Supports OpenAI, OpenRouter, Together.ai, and any OpenAI-compatible endpoint."
  (:require
   [cheshire.core :as json]
   [co.poyo.clj-llm.schema :as schema]
   [co.poyo.clj-llm.protocol :as proto]
   [co.poyo.clj-llm.errors :as errors]
   [co.poyo.clj-llm.backends.backend-helpers :as bh]))

(def ^:private default-config
  {:api-base "https://api.openai.com/v1"})

(defn- default-api-key-fn
  "Default: read API key from OPENAI_API_KEY env var."
  []
  (System/getenv "OPENAI_API_KEY"))

(defn- build-body
  "Build OpenAI API request body"
  [model system-prompt messages schema tools tool-choice opts]
  (let [messages (bh/normalize-messages messages)
        messages-with-system (if system-prompt
                               (into [{:role "system" :content system-prompt}]
                                     messages)
                               messages)
        tools-config (cond
                       tools
                       {:tools (mapv schema/malli->json-schema tools)
                        :tool_choice (or tool-choice "auto")}

                       schema
                       {:tools [(schema/malli->json-schema schema)]
                        :tool_choice "required"})
        api-opts (bh/convert-options-for-api opts)]
    (merge
     {:stream true
      :stream_options {:include_usage true}
      :model model
      :messages messages-with-system}
     api-opts
     tools-config)))

(defn- data->internal-event
  "Convert OpenAI chunk to our event format.
   In schema mode, tool calls are treated as content.
   In tools mode, tool calls are emitted as :tool-call events."
  [data schema tools]
  (let [content (get-in data [:choices 0 :delta :content])
        tool-calls (get-in data [:choices 0 :delta :tool-calls])
        finish-reason (get-in data [:choices 0 :finish-reason])
        usage (:usage data)]
    (cond
      (:error data)
      {:type :error :error (:error data)}

      (not-empty content)
      {:type :content :content content}

      tool-calls
      (let [tool-call (first tool-calls)
            has-name? (get-in tool-call [:function :name])
            has-args? (not-empty (get-in tool-call [:function :arguments]))]
        (cond
          (and schema has-args?)
          {:type :content :content (get-in tool-call [:function :arguments])}

          (and tools has-name?)
          {:type :tool-call
           :id (get tool-call :id)
           :index (get tool-call :index)
           :name (get-in tool-call [:function :name])
           :arguments ""}

          (and tools has-args?)
          {:type :tool-call-delta
           :index (get tool-call :index)
           :arguments (get-in tool-call [:function :arguments])}

          :else nil))

      finish-reason
      {:type :finish :reason finish-reason}

      usage
      (into {:type :usage} usage)

      :else nil)))

;; ==========================================
;; OpenAI Backend
;; ==========================================

(defrecord OpenAIBackend [api-base api-key-fn defaults]
  proto/LLMProvider
  (request-stream [_ model system-prompt messages schema tools tool-choice provider-opts]
    (let [api-key (api-key-fn)
          _ (when-not api-key
              (throw (errors/error "API key function returned nil"
                                   {:provider "openai"})))
          url (str api-base "/chat/completions")
          headers {"Authorization" (str "Bearer " api-key)
                   "Content-Type" "application/json"}
          body (json/generate-string (build-body model system-prompt messages schema tools tool-choice provider-opts))]
      (bh/create-event-stream url headers body
                                  #(data->internal-event % schema tools)
                                  "openai"))))

(def ^:private openai-config-keys #{:api-key :api-key-fn :api-base})

(defn backend
  "Create an OpenAI provider. Config keys:
    :api-key-fn  - 0-arg fn that returns the API key (called per request)
    :api-key     - API key string (convenience, wrapped in constantly)
    :api-base    - API base URL (default: https://api.openai.com/v1)

   The default api-key-fn reads from the OPENAI_API_KEY env var.
   Override for custom env vars, vaults, or key rotation:
     (backend {:api-key-fn #(System/getenv "MY_KEY")})

   Set :defaults on the provider to configure model, system-prompt, schema, etc."
  ([] (backend {}))
  ([config]
   (let [unknown (seq (remove openai-config-keys (keys config)))]
     (when unknown
       (throw (errors/error
               (str "Unknown provider config keys: " (pr-str (vec unknown))
                    ". Set :defaults on the provider for prompt options.")
               {:unknown-keys (vec unknown)
                :valid-keys openai-config-keys}))))
   (let [api-key-fn (cond
                      (:api-key-fn config) (:api-key-fn config)
                      (:api-key config)    (constantly (:api-key config))
                      :else                default-api-key-fn)
         api-base   (or (:api-base config) (:api-base default-config))]
     (->OpenAIBackend api-base api-key-fn {}))))

(defmethod print-method OpenAIBackend [b writer]
  (let [model (get-in b [:defaults :model])]
    (.write writer "#OpenAI")
    (when model
      (.write writer (str " " (pr-str model))))
    (.write writer (str " " (pr-str (:api-base b))))))
