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
  {:api-key-env "OPENAI_API_KEY"
   :api-base "https://api.openai.com/v1"})

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

(defrecord OpenAIBackend [api-base api-key defaults]
  proto/LLMProvider
  (request-stream [_ model system-prompt messages schema tools tool-choice provider-opts]
    (let [url (str api-base "/chat/completions")
          headers {"Authorization" (str "Bearer " api-key)
                   "Content-Type" "application/json"}
          body (json/generate-string (build-body model system-prompt messages schema tools tool-choice provider-opts))]
      (bh/create-event-stream url headers body
                                  #(data->internal-event % schema tools)
                                  "openai"))))

(def ^:private openai-config-keys #{:api-key :api-key-env :api-base})

(defn ->openai
  "Create an OpenAI provider. Config keys:
    :api-key     - API key string
    :api-key-env - env var name (default: OPENAI_API_KEY)
    :api-base    - API base URL (default: https://api.openai.com/v1)

   Set :defaults on the provider to configure model, system-prompt, schema, etc."
  ([] (->openai {}))
  ([config]
   (let [unknown (seq (remove openai-config-keys (keys config)))]
     (when unknown
       (throw (errors/error
               (str "Unknown provider config keys: " (pr-str (vec unknown))
                    ". Set :defaults on the provider for prompt options.")
               {:unknown-keys (vec unknown)
                :valid-keys openai-config-keys}))))
   (let [api-env  (or (:api-key-env config) (:api-key-env default-config))
         api-key  (or (:api-key config) (System/getenv api-env))
         api-base (or (:api-base config) (:api-base default-config))]
     (when-not api-key
       (throw (errors/error "Missing API key"
                            {:provider "openai" :api-key-env api-env})))
     (->OpenAIBackend api-base api-key {}))))

(defmethod print-method OpenAIBackend [backend writer]
  (let [model (get-in backend [:defaults :model])]
    (.write writer "#OpenAI")
    (when model
      (.write writer (str " " (pr-str model))))
    (.write writer (str " " (pr-str (:api-base backend))))))
