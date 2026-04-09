(ns co.poyo.clj-llm.backend.openai
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

(defn- default-api-key-fn []
    (or (System/getenv "OPENAI_API_KEY")
        (throw (ex-info "No API key provided and OPENAI_API_KEY env var not set" {}))))

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

(defn- build-tools-config
  "Extract tools/schema configuration logic."
  [schema tools tool-choice]
  (cond
    tools
    {:tools (mapv schema/malli->tool-definition tools)
     :tool_choice (or tool-choice "auto")}

    schema
    {:tools [(schema/malli->tool-definition schema)]
     :tool_choice "required"}

    :else
    nil))

(defn- build-body-internal
  "Build OpenAI API request body as a map."
  [model system-prompt messages schema tools tool-choice opts]
  (let [messages (normalize-messages messages)
        messages-with-system (if system-prompt
                               (into [{:role "system" :content system-prompt}]
                                     messages)
                               messages)
        tools-config (build-tools-config schema tools tool-choice)
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
   In tools mode, tool calls are emitted as :tool-call events.
   Reasoning content (from o1/o3 models and OpenRouter) is emitted as :reasoning events."
  [data schema tools]
  (let [content (get-in data [:choices 0 :delta :content])
        reasoning-content (or (get-in data [:choices 0 :delta :reasoning])
                             (get-in data [:choices 0 :delta :reasoning_content]))
        tool-calls (get-in data [:choices 0 :delta :tool-calls])
        finish-reason (get-in data [:choices 0 :finish-reason])
        usage (:usage data)]
    (cond
      (:error data)
      [{:type :error :error (:error data)}]

      (not-empty reasoning-content)
      [{:type :reasoning :content reasoning-content}]

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
                   :arguments (or fn-args "")}

                  (and tools fn-args)
                  {:type :tool-call-delta
                   :index (:index tool-call)
                   :arguments fn-args})))
            tool-calls)

      finish-reason
      (cond-> [{:type :finish :reason finish-reason}]
        usage (conj (into {:type :usage} usage)))

      usage
      [(into {:type :usage} usage)])))

;; ==========================================
;; OpenAI Backend
;; ==========================================

(defrecord OpenAIBackend [api-base api-key-fn]
  proto/LLMProvider
  (api-key [_] (api-key-fn))

  (build-url [_ _model] (str api-base "/chat/completions"))

  (build-headers [_]
    (let [key (api-key-fn)]
      (cond-> {"Content-Type" "application/json"}
        key (assoc "Authorization" (str "Bearer " key)))))

  (build-body [_ model system-prompt messages schema tools tool-choice provider-opts]
    (build-body-internal model system-prompt messages schema tools tool-choice provider-opts))

  (parse-chunk [_ chunk schema tools]
    (or (data->events chunk schema tools) []))

  (stream-events [_ url headers body]
    (stream/open-event-stream url headers body)))

(defn backend
  "Create an OpenAI provider.
   Config: :api-key, :api-base, :defaults.
   :api-key can be a string, a zero-arg fn, or false (skip auth)."
  ([] (backend {}))
  ([{:keys [api-key api-base defaults]}]
   (let [b (->OpenAIBackend
            (or api-base (:api-base default-config))
            (cond
              (string? api-key) (constantly api-key)
              (= api-key false) (constantly nil)
              (fn? api-key)     api-key
              :else             default-api-key-fn))]
     (if defaults
       (assoc b :defaults defaults)
       b))))
