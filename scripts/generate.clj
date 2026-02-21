#!/usr/bin/env bb

(require '[co.poyo.clj-llm.core :as llm]
         '[co.poyo.clj-llm.backends.openai :as openai])

;; Works with OPENAI_API_KEY or OPENROUTER_KEY
(def ai
  (let [openrouter-key (System/getenv "OPENROUTER_KEY")]
    (-> (if openrouter-key
          (openai/->openai {:api-key openrouter-key
                            :api-base "https://openrouter.ai/api/v1"})
          (openai/->openai))
        (llm/with-defaults {:model (or (System/getenv "LLM_MODEL") "gpt-4o-mini")}))))

;; Simple text
(println "--- text ---")
(println (llm/generate ai "What is the capital of France?"))

;; With system prompt
(println "\n--- system prompt ---")
(println (llm/generate ai "What is 2+2?"
                       {:system-prompt "You are a pirate. Be brief."}))

;; Structured output
(println "\n--- structured ---")
(println (llm/generate ai
                       "Extract: Marie Curie was a 66 year old physicist"
                       {:schema [:map
                                [:name :string]
                                [:age :int]
                                [:occupation :string]]}))

;; Nested schema
(println "\n--- nested schema ---")
(println (llm/generate ai
                       "TechCorp founded 2010. Alice CEO $200k, Bob Engineer $120k. NYC and SF."
                       {:schema [:map
                                [:name :string]
                                [:founded :int]
                                [:employees [:vector [:map
                                                     [:name :string]
                                                     [:role :string]
                                                     [:salary :int]]]]
                                [:locations [:vector :string]]]}))
