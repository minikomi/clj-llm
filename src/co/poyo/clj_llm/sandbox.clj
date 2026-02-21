(ns co.poyo.clj-llm.sandbox
  "REPL-friendly examples for clj-llm"
  (:require [co.poyo.clj-llm.core :as llm]
            [co.poyo.clj-llm.backends.openai :as openai]
            [clojure.core.async :refer [<!!]]))

(comment

  ;; ══════════════════════════════════════
  ;; Setup
  ;; ══════════════════════════════════════

  (def ai (openai/->openai))

  (def ai-mini
    (llm/with-defaults ai {:model "gpt-4o-mini"}))

  ;; ══════════════════════════════════════
  ;; Basic text
  ;; ══════════════════════════════════════

  (llm/generate ai-mini "What is 2+2?")

  (llm/generate ai-mini "Write a haiku"
                {:system-prompt "You are a poet"})

  ;; ══════════════════════════════════════
  ;; Structured output
  ;; ══════════════════════════════════════

  (llm/generate ai-mini
                "Extract: Marie Curie was a 66 year old physicist"
                {:schema [:map
                          [:name :string]
                          [:age :int]
                          [:occupation :string]]})

  (def company-schema
    [:map
     [:name :string]
     [:founded :int]
     [:employees [:vector [:map
                           [:name :string]
                           [:role :string]
                           [:salary :int]]]]
     [:locations [:vector :string]]])

  (llm/generate ai-mini
                "TechCorp founded 2010. Alice CEO $200k, Bob Engineer $120k. NYC and SF."
                {:schema company-schema})

  ;; ══════════════════════════════════════
  ;; Streaming
  ;; ══════════════════════════════════════

  (llm/stream-print ai-mini "Tell me a story about a robot.")

  ;; Raw channel if you need it
  (let [ch (llm/stream ai-mini "Count to 5")]
    (loop []
      (when-let [chunk (<!! ch)]
        (print chunk)
        (flush)
        (recur))))

  ;; ══════════════════════════════════════
  ;; Tool calling
  ;; ══════════════════════════════════════

  (def weather-tool
    [:map {:name "get_weather" :description "Get weather for a city"}
     [:city {:description "City name"} :string]])

  (llm/generate ai-mini "What's the weather in Tokyo?"
                {:tools [weather-tool]})
  ;; => [{:id "call_..." :name "get_weather" :arguments {:city "Tokyo"}}]

  ;; ══════════════════════════════════════
  ;; Full response (prompt)
  ;; ══════════════════════════════════════

  (def resp (llm/prompt ai-mini "Explain AI briefly"))

  @resp              ;; text (IDeref)
  @(:text resp)      ;; same
  @(:usage resp)     ;; token counts

  ;; ══════════════════════════════════════
  ;; Building agents
  ;; ══════════════════════════════════════

  (def cat-agent
    (llm/with-defaults ai
      {:model "gpt-4o-mini"
       :system-prompt "You are a cat. Be brief. Love emojis."
       :schema [:map
                [:cat-answer :string]
                [:emojis [:vector :string]]]}))

  (llm/generate cat-agent "What is 2+2?")

  ;; Override per-call
  (llm/generate cat-agent "What is 2+2?" {:model "gpt-4o"})

  ;; ══════════════════════════════════════
  ;; Conversations
  ;; ══════════════════════════════════════

  (def conversation
    (atom [{:role :system :content "You are a helpful coding assistant"}]))

  (defn chat! [msg]
    (swap! conversation conj {:role :user :content msg})
    (let [response (llm/generate ai-mini nil {:message-history @conversation})]
      (swap! conversation conj {:role :assistant :content response})
      response))

  (chat! "How do I reverse a list in Clojure?")
  (chat! "What about in Python?")

  ;; ══════════════════════════════════════
  ;; Error handling
  ;; ══════════════════════════════════════

  (try
    (llm/generate ai-mini "test" {:model "fake-model"})
    (catch Exception e
      (println "Error:" (.getMessage e))
      (println "Data:" (ex-data e))))

  )
