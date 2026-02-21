#!/usr/bin/env bb

(require '[co.poyo.clj-llm.core :as llm]
         '[co.poyo.clj-llm.backends.openai :as openai])

;; Works with OPENAI_API_KEY or OPENROUTER_KEY
(def provider
  (let [openrouter-key (System/getenv "OPENROUTER_KEY")]
    (if openrouter-key
      (openai/->openai {:api-key openrouter-key
                        :api-base "https://openrouter.ai/api/v1"})
      (openai/->openai))))

(def ai (assoc provider :defaults {:model (or (System/getenv "LLM_MODEL") "gpt-4o-mini")}))

;; Simple text — returns a string
(println "--- text ---")
(println (llm/generate ai "What is the capital of France?"))

;; With system prompt
(println "\n--- system prompt ---")
(println (llm/generate ai "What is 2+2?"
                        {:system-prompt "You are a pirate. Be brief."}))

;; Structured output — returns a map
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

;; Layer config with update+merge
(println "\n--- layered defaults ---")
(def extractor
  (update ai :defaults merge {:system-prompt "Extract structured data."
                                  :schema [:map [:name :string] [:age :int] [:occupation :string]]}))

(println (llm/generate extractor "Marie Curie was a 66 year old physicist"))
(println (llm/generate extractor "Albert Einstein was a 76 year old theoretical physicist"))
