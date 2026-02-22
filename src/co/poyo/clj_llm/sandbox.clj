(ns co.poyo.clj-llm.sandbox
  "REPL-friendly examples for clj-llm"
  (:require [co.poyo.clj-llm.core :as llm]
            [co.poyo.clj-llm.backends.openai :as openai]
            [clojure.core.async :refer [<!!]]))

(comment

  ;; ======================================
  ;; Setup
  ;; ======================================

  ;; Provider = connection
  (def provider (openai/->openai))

  ;; Defaults = just data on the map
  (def ai (assoc provider :defaults {:model "gpt-4o-mini"}))

  ;; Layer more config with update+merge
  (def extractor (update ai :defaults merge
                        {:schema [:map [:name :string] [:age :int] [:occupation :string]]
                         :system-prompt "Extract structured data"}))

  ;; ======================================
  ;; Basic text -- returns a string
  ;; ======================================

  (llm/generate ai "What is 2+2?")
  ;; => "2+2 equals 4."

  (llm/generate ai {:system-prompt "You are a poet"} "Write a haiku")

  ;; ======================================
  ;; Structured output -- returns a parsed map
  ;; ======================================

  (llm/generate ai {:schema [:map [:name :string] [:age :int] [:occupation :string]]}
                "Extract: Marie Curie was a 66 year old physicist")
  ;; => {:name "Marie Curie" :age 66 :occupation "physicist"}

  ;; Or use a pre-configured extractor
  (llm/generate extractor "Marie Curie was a 66 year old physicist")
  ;; => {:name "Marie Curie" :age 66 :occupation "physicist"}

  ;; ======================================
  ;; Streaming
  ;; ======================================

  ;; Print as it streams, returns full text
  (llm/stream-print ai "Tell me a story about a robot.")

  ;; Raw channel
  (let [ch (llm/stream ai "Count to 5")]
    (loop []
      (when-let [chunk (<!! ch)]
        (print chunk) (flush)
        (recur))))

  ;; ======================================
  ;; Tool calling -- defns with Malli schemas
  ;; ======================================

  ;; Standard Malli function schema on the var metadata
  (defn get-weather
    {:malli/schema [:=> [:cat [:map {:name "get_weather"
                                     :description "Get weather for a city"}
                               [:city {:description "City name"} :string]]]
                        :string]}
    [{:keys [city]}]
    (str "Sunny, 22C in " city))

  ;; It's a regular function -- test it directly
  (get-weather {:city "Tokyo"})
  ;; => "Sunny, 22C in Tokyo"

  ;; run-agent reads schemas from var metadata and calls the fns
  (llm/run-agent ai [#'get-weather] "What's the weather in Tokyo?")
  ;; => {:text "It's sunny and 22C in Tokyo!"
  ;;     :history [...]
  ;;     :steps [{:tool-calls [...] :tool-results [...]}]}

  ;; ======================================
  ;; Full response (prompt)
  ;; ======================================

  (def resp (llm/prompt ai "Explain AI briefly"))
  @resp              ;; text (IDeref)
  @(:text resp)      ;; same
  @(:usage resp)     ;; token counts

  ;; ======================================
  ;; Composition -- threading just works
  ;; ======================================

  (->> "Raw technical document with some errors"
       (llm/generate ai {:system-prompt "Fix grammar"})
       (llm/generate ai {:system-prompt "Translate to French"}))

  ;; ======================================
  ;; Conversations -- history is just a vector
  ;; ======================================

  (def conversation
    (atom [{:role :system :content "You are a helpful coding assistant"}]))

  (defn chat! [msg]
    (swap! conversation conj {:role :user :content msg})
    (let [text (llm/generate ai @conversation)]
      (swap! conversation conj {:role :assistant :content text})
      text))

  (chat! "How do I reverse a list in Clojure?")
  (chat! "What about in Python?") ;; remembers context

  ;; ======================================
  ;; Error handling
  ;; ======================================

  (try
    (llm/generate ai {:model "fake-model"} "test")
    (catch Exception e
      (println "Error:" (.getMessage e))
      (println "Data:" (ex-data e))))

  )
