(ns co.poyo.clj-llm.backends.openai
  "OpenAI and OpenAI-compatible API provider implementation.
   
   Supports OpenAI, OpenRouter, Together.ai, and any OpenAI-compatible endpoint."
  (:require
   [camel-snake-kebab.core :as csk]
   [camel-snake-kebab.extras :as cske]
   [cheshire.core :as json]
   [clojure.set]
   [co.poyo.clj-llm.schema :as schema]
   [co.poyo.clj-llm.protocol :as proto]
   [co.poyo.clj-llm.sse :as sse]))

(def ^:private default-config
  {:api-base "https://api.openai.com/v1"})

(defn- default-api-key-fn
  "Default: read API key from OPENAI_API_KEY env var."
  []
  (System/getenv "OPENAI_API_KEY"))

(def ^:private ->snake-key (memoize csk/->snake_case_keyword))

(defn- convert-options-for-api [opts]
  (when opts
    (cske/transform-keys ->snake-key opts)))

(defn- normalize-messages [messages]
  (mapv #(clojure.set/rename-keys % {:tool-calls :tool_calls
                                     :tool-call-id :tool_call_id}) messages))

(defn- build-body
  "Build OpenAI API request body"
  [model system-prompt messages schema tools tool-choice opts]
  (let [messages (normalize-messages messages)
        messages-with-system (if system-prompt
                               (into [{:role "system" :content system-prompt}]
                                     messages)
                               messages)
        tools-config (cond
                       tools
                       {:tools (mapv schema/malli->tool-definition tools)
                        :tool_choice (or tool-choice "auto")}

                       schema
                       {:tools [(schema/malli->tool-definition schema)]
                        :tool_choice "required"})
        api-opts (convert-options-for-api opts)]
    (merge
     {:stream true
      :stream_options {:include_usage true}
      :model model
      :messages messages-with-system}
     api-opts
     tools-config)))

(defn- data->events
  "Convert one OpenAI chunk to a seq of internal events, or nil.
   In schema mode, tool calls are treated as content.
   In tools mode, tool calls are emitted as :tool-call events."
  [data schema tools]
  (let [content (get-in data [:choices 0 :delta :content])
        tool-calls (get-in data [:choices 0 :delta :tool-calls])
        finish-reason (get-in data [:choices 0 :finish-reason])
        usage (:usage data)]
    (cond
      (:error data)
      [{:type :error :error (:error data)}]

      (not-empty content)
      [{:type :content :content content}]

      tool-calls
      (keep (fn [tool-call]
              (let [fn-name (get-in tool-call [:function :name])
                    fn-args (not-empty (get-in tool-call [:function :arguments]))]
                (cond
                  (and schema fn-args)
                  {:type :content :content fn-args}

                  (and tools fn-name)
                  {:type :tool-call
                   :id (:id tool-call)
                   :index (:index tool-call)
                   :name fn-name
                   :arguments ""}

                  (and tools fn-args)
                  {:type :tool-call-delta
                   :index (:index tool-call)
                   :arguments fn-args})))
            tool-calls)

      finish-reason
      [{:type :finish :reason finish-reason}]

      usage
      [(into {:type :usage} usage)])))

;; ==========================================
;; OpenAI Backend
;; ==========================================

(defrecord OpenAIBackend [api-base api-key-fn defaults]
  proto/LLMProvider
  (request-stream [_ {:keys [model system-prompt messages schema tools tool-choice provider-opts]}]
    (let [api-key (api-key-fn)
          _ (when-not api-key
              (throw (ex-info "API key function returned nil"
                              {:provider "openai"})))
          url (str api-base "/chat/completions")
          headers {"Authorization" (str "Bearer " api-key)
                   "Content-Type" "application/json"}
          body (json/generate-string (build-body model system-prompt messages schema tools tool-choice provider-opts))]
      (->> (sse/lines url headers body)
           (eduction sse/xf)
           (eduction (mapcat #(data->events % schema tools)))))))

(defn backend
  "Create an OpenAI provider.
   Config: :api-key, :api-key-fn, :api-base.
   Default reads OPENAI_API_KEY env var."
  ([] (backend {}))
  ([{:keys [api-key api-key-fn api-base]}]
   (->OpenAIBackend
    (or api-base (:api-base default-config))
    (cond api-key-fn api-key-fn
          api-key    (constantly api-key)
          :else      default-api-key-fn)
    {})))

(defmethod print-method OpenAIBackend [b writer]
  (let [model (get-in b [:defaults :model])]
    (.write writer "#OpenAI")
    (when model
      (.write writer (str " " (pr-str model))))
    (.write writer (str " " (pr-str (:api-base b))))))
