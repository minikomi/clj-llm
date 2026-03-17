#!/usr/bin/env bb

(require '[co.poyo.clj-llm.core :as llm]
         '[co.poyo.clj-llm.backend.openai :as openai]
         '[co.poyo.clj-llm.backend.openrouter :as openrouter]
         '[clojure.pprint :refer [pprint]]
         )

;; Works with OPENAI_API_KEY or OPENROUTER_KEY
(def provider
  (cond->
      (if (System/getenv "OPENROUTER_KEY")
        (openrouter/backend)
        (openai/backend))
    ;; Override default model
    (System/getenv "LLM_MODEL")
    (assoc-in [:defaults :model] (System/getenv "LLM_MODEL"))))

(def ai (assoc provider :defaults {:model (or (System/getenv "LLM_MODEL") "gpt-4o-mini")}))

;; Simple text — returns a string
(println "--- text ---")
(pprint (llm/generate ai "What is the capital of France?"))

;; With system prompt — opts before input
(println "\n--- system prompt ---")
(pprint (llm/generate ai {:system-prompt "You are a pirate. Be brief."} "What is 2+2?"))

;; Structured output — returns a map
(println "\n--- structured ---")
(pprint (llm/generate ai
                       {:schema [:map
                                [:name :string]
                                [:age :int]
                                [:occupation :string]]}
                       "Extract: Marie Curie was a 66 year old physicist"))

;; Nested schema
(println "\n--- nested schema ---")
(pprint (llm/generate ai
                       {:schema [:map
                                [:name :string]
                                [:founded :int]
                                [:employees [:vector [:map
                                                     [:name :string]
                                                     [:role :string]
                                                     [:salary :int]]]]
                                [:locations [:vector :string]]]}
                       "TechCorp founded 2010. Alice CEO $200k, Bob Engineer $120k. NYC and SF."))

;; Layer config with update+merge
(def extractor
  (update ai :defaults merge {:system-prompt "Extract structured data."
                              :schema [:map [:name :string] [:age :int] [:occupation :string]]}))
(println "\n--- layered defaults ---")
(pprint (llm/generate extractor "Marie Curie was a 66 year old physicist"))
(pprint (llm/generate extractor "Albert Einstein was a 76 year old theoretical physicist"))
