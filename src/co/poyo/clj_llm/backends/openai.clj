(ns co.poyo.clj-llm.backends.openai
  "OpenAI and OpenAI-compatible API provider implementation.
   
   Supports OpenAI, OpenRouter, Together.ai, and any OpenAI-compatible endpoint."
  (:require
   [camel-snake-kebab.core :as csk]
   [camel-snake-kebab.extras :as cske]
   [cheshire.core :as json]
   [clojure.core.async :as a]
   [clojure.set]
   [co.poyo.clj-llm.schema :as schema]
   [co.poyo.clj-llm.protocol :as proto]
   [co.poyo.clj-llm.stream :as stream]))

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

(defn- content-part->openai
  "Convert a clj-llm content part to OpenAI's content array format."
  [part]
  (case (:type part)
    :text  {:type "text" :text (:text part)}
    :image (case (:source part)
             :url    {:type "image_url"
                      :image_url {:url (:url part)}}
             :base64 {:type "image_url"
                      :image_url {:url (str "data:" (:media-type part) ";base64," (:data part))}})
    :pdf   {:type "file"
            :file {:filename "document.pdf"
                   :file_data (str "data:" (:media-type part) ";base64," (:data part))}}))

(defn- normalize-content
  "If content is a vector of content parts, convert to OpenAI format.
   Strings pass through as-is."
  [content]
  (if (vector? content)
    (mapv content-part->openai content)
    content))

(defn- normalize-messages [messages]
  (mapv (fn [msg]
          (cond-> (clojure.set/rename-keys msg {:tool-calls :tool_calls
                                                :tool-call-id :tool_call_id})
            (:content msg) (update :content normalize-content)))
        messages))

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
  (request-events [_ {:keys [model system-prompt messages schema tools tool-choice provider-opts]}]
    (let [api-key (api-key-fn)
          url (str api-base "/chat/completions")
          headers (cond-> {"Content-Type" "application/json"}
                   api-key (assoc "Authorization" (str "Bearer " api-key)))
          body (json/generate-string (build-body model system-prompt messages schema tools tool-choice provider-opts))]
      (let [raw-ch (stream/open-event-stream url headers body)
            ch     (a/chan 256 (mapcat #(data->events % schema tools)))]
        (a/pipe raw-ch ch)
        ch))))

(defn backend
  "Create an OpenAI provider.
   Config: :api-key, :api-key-fn, :api-base.
   Default reads OPENAI_API_KEY env var.
   Pass :api-key false to skip auth (e.g. Ollama, LM Studio)."
  ([] (backend {}))
  ([{:keys [api-key api-key-fn api-base]}]
   (->OpenAIBackend
    (or api-base (:api-base default-config))
    (cond (false? api-key)  (constantly nil)
          api-key-fn         api-key-fn
          (some? api-key)    (constantly api-key)
          :else              default-api-key-fn)
    {})))

(defmethod print-method OpenAIBackend [b writer]
  (let [model (get-in b [:defaults :model])]
    (.write writer "#OpenAI")
    (when model
      (.write writer (str " " (pr-str model))))
    (.write writer (str " " (pr-str (:api-base b))))))
