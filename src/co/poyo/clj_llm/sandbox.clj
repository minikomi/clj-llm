(ns co.poyo.clj-llm.sandbox
  "REPL-friendly examples for clj-llm"
  (:require [co.poyo.clj-llm.core :as llm]
            [co.poyo.clj-llm.backends.openai :as openai]
            [clojure.core.async :refer [<!!]]))

(comment

  ;; ══════════════════════════════════════
  ;; Setup
  ;; ══════════════════════════════════════

  ;; Provider = connection
  (def provider (openai/->openai))

  ;; Defaults = just data on the map
  (def ai (assoc provider :defaults {:model "gpt-4o-mini"}))

  ;; Layer more config with update+merge
  (def extractor (update ai :defaults merge
                        {:schema [:map [:name :string] [:age :int] [:occupation :string]]
                         :system-prompt "Extract structured data"}))

  ;; ══════════════════════════════════════
  ;; Basic text — returns a string
  ;; ══════════════════════════════════════

  (llm/generate ai "What is 2+2?")
  ;; => "2+2 equals 4."

  (llm/generate ai {:system-prompt "You are a poet"} "Write a haiku")

  ;; ══════════════════════════════════════
  ;; Structured output — returns a parsed map
  ;; ══════════════════════════════════════

  (llm/generate ai {:schema [:map [:name :string] [:age :int] [:occupation :string]]}
                "Extract: Marie Curie was a 66 year old physicist")
  ;; => {:name "Marie Curie" :age 66 :occupation "physicist"}

  ;; Or use a pre-configured extractor
  (llm/generate extractor "Marie Curie was a 66 year old physicist")
  ;; => {:name "Marie Curie" :age 66 :occupation "physicist"}

  ;; ══════════════════════════════════════
  ;; Streaming
  ;; ══════════════════════════════════════

  ;; Print as it streams, returns full text
  (llm/stream-print ai "Tell me a story about a robot.")

  ;; Raw channel
  (let [ch (llm/stream ai "Count to 5")]
    (loop []
      (when-let [chunk (<!! ch)]
        (print chunk) (flush)
        (recur))))

  ;; ══════════════════════════════════════
  ;; Tool calling
  ;; ══════════════════════════════════════

  (def weather-tool
    [:map {:name "get_weather" :description "Get weather for a city"}
     [:city {:description "City name"} :string]])

  ;; Returns a vector of tool calls
  (llm/generate ai {:tools [weather-tool]} "What's the weather in Tokyo?")
  ;; => [{:id "call_..." :name "get_weather" :arguments {:city "Tokyo"}}]
  ;; (meta result) => {:message {:role :assistant :tool-calls [...]}}

  ;; Round-trip: feed tool results back as history
  (let [calls   (llm/generate ai {:tools [weather-tool]} "Weather?")
        msg     (:message (meta calls))
        results (mapv #(llm/tool-result (:id %) (str "Sunny in " (:city (:arguments %)))) calls)
        history (into [{:role :user :content "Weather?"} msg] results)]
    (llm/generate ai history))

  ;; ══════════════════════════════════════
  ;; Full response (prompt)
  ;; ══════════════════════════════════════

  (def resp (llm/prompt ai "Explain AI briefly"))
  @resp              ;; text (IDeref)
  @(:text resp)      ;; same
  @(:usage resp)     ;; token counts

  ;; ══════════════════════════════════════
  ;; Composition — threading just works
  ;; ══════════════════════════════════════

  (->> "Raw technical document with some errors"
       (llm/generate ai {:system-prompt "Fix grammar"})
       (llm/generate ai {:system-prompt "Translate to French"}))

  ;; ══════════════════════════════════════
  ;; Conversations — history is just a vector
  ;; ══════════════════════════════════════

  (def conversation
    (atom [{:role :system :content "You are a helpful coding assistant"}]))

  (defn chat! [msg]
    (swap! conversation conj {:role :user :content msg})
    (let [text (llm/generate ai @conversation)]
      (swap! conversation conj {:role :assistant :content text})
      text))

  (chat! "How do I reverse a list in Clojure?")
  (chat! "What about in Python?") ;; remembers context

  ;; ══════════════════════════════════════
  ;; Error handling
  ;; ══════════════════════════════════════

  (try
    (llm/generate ai {:model "fake-model"} "test")
    (catch Exception e
      (println "Error:" (.getMessage e))
      (println "Data:" (ex-data e))))

  )
