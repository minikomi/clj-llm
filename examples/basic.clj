(ns examples.basic
  (:require [co.poyo.clj-llm.core :as llm]
            [co.poyo.clj-llm.backends.openai :as openai]))

;; Example 1: Using OpenAI
(def openai-backend (openai/openai))

(def response
  (llm/prompt openai-backend
              "What is the capital of France?"
              {:model :gpt-4o
               :api-key (System/getenv "OPENAI_API_KEY")
               :api-base "https://api.openai.com/v1"
               :temperature 0.7}))

;; Get the text response
(println @(:text response))

;; Example 2: Using Together.ai
(def together-backend (openai/together))

(def response2
  (llm/prompt together-backend
              "Explain quantum computing in simple terms"
              {:model :mixtral-8x7b-32768
               :api-key (System/getenv "TOGETHER_API_KEY")
               :api-base "https://api.together.xyz/v1"
               :max-tokens 200}))

;; Example 3: Creating a conversation
(def conv (llm/conversation openai-backend))

;; Send messages in the conversation
((:prompt conv) "Hello!" {:model :gpt-4o
                          :api-key (System/getenv "OPENAI_API_KEY")
                          :api-base "https://api.openai.com/v1"})

((:prompt conv) "What did I just say?" {:model :gpt-4o
                                        :api-key (System/getenv "OPENAI_API_KEY")
                                        :api-base "https://api.openai.com/v1"})

;; View conversation history
@(:history conv)

;; Clear conversation
((:clear conv))