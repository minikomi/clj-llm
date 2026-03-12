(ns co.poyo.clj-llm.backends.anthropic
  "Anthropic API provider implementation"
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

(defn- content-part->anthropic
  "Convert a clj-llm content part to Anthropic's content array format."
  [part]
  (case (:type part)
    :text  {:type "text" :text (:text part)}
    :image (case (:source part)
             :url    {:type "image"
                      :source {:type "url" :url (:url part)}}
             :base64 {:type "image"
                      :source {:type "base64"
                               :media_type (:media-type part)
                               :data (:data part)}})
    :pdf   {:type "document"
            :source {:type "base64"
                     :media_type (:media-type part)
                     :data (:data part)}}))

(defn- normalize-content
  "If content is a vector of content parts, convert to Anthropic format.
   Strings pass through as-is."
  [content]
  (if (vector? content)
    (mapv content-part->anthropic content)
    content))

(defn- normalize-messages [messages]
  (mapv (fn [msg]
          (cond-> (clojure.set/rename-keys msg {:tool-calls :tool_calls
                                                :tool-call-id :tool_call_id})
            (:content msg) (update :content normalize-content)))
        messages))

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

(defn- data->event
  "Convert one Anthropic SSE event to an internal event, or nil.
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
       :arguments (get-in data [:delta :partial-json])})

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

    "message_start"
    (when-let [usage (get-in data [:message :usage])]
      (into {:type :usage} usage))

    ;; Lifecycle events with no useful payload — skip
    ("content_block_stop" "ping")
    nil

    "error"
    {:type :error :error (:error data)}

    ;; Unknown event type
    nil))

;; ==========================================
;; Anthropic Backend
;; ==========================================

(defrecord AnthropicBackend [api-base api-key-fn api-version defaults]
  proto/LLMProvider
  (api-key [_] (api-key-fn))

  (build-url [_ _model] (str api-base "/v1/messages"))

  (build-headers [_]
    (let [key (api-key-fn)]
      (cond-> {"Content-Type" "application/json"
               "anthropic-version" api-version}
        key (assoc "x-api-key" key))))

  (build-body [_ model system-prompt messages schema tools tool-choice provider-opts]
    (let [messages (normalize-messages messages)
          tools-config (cond
                         tools
                         (cond-> {:tools (mapv schema/malli->tool-definition tools)}
                           (not= tool-choice "none")
                           (assoc :tool_choice (cond
                                                (= tool-choice "auto") {:type "auto"}
                                                (= tool-choice "required") {:type "any"}
                                                :else (or tool-choice {:type "auto"}))))

                         schema
                         {:tools [(schema/malli->tool-definition schema)]
                          :tool_choice {:type "any"}})
          api-opts (convert-options-for-api provider-opts)]
      (merge
       {:model model
        :max_tokens (or (:max_tokens api-opts) 4096)
        :messages messages
        :stream true}
       api-opts
       tools-config
       (when system-prompt {:system system-prompt}))))

  (parse-chunk [_ chunk schema tools]
    (or (data->event chunk schema tools) []))

  (stream-events [_ url headers body]
    (stream/open-event-stream url headers body)))


(defn backend
  "Create an Anthropic provider.
   Config: :api-key, :api-base, :api-version, :defaults.
   :api-key can be a string, a zero-arg fn, or false (skip auth)."
  ([] (backend {}))
  ([{:keys [api-key api-base api-version defaults]}]
   (let [b (->AnthropicBackend
            (or api-base (:api-base default-config))
            (cond (false? api-key)  (constantly nil)
                  (fn? api-key)     api-key
                  :else             (constantly api-key))
            (or api-version (:api-version default-config)))]
     (if defaults
       (assoc b :defaults defaults)
       b))))

(defmethod print-method AnthropicBackend [b writer]
  (let [model (get-in b [:defaults :model])]
    (.write writer "#Anthropic")
    (when model
      (.write writer (str " " (pr-str model))))
    (.write writer (str " " (pr-str (:api-base b))))))
