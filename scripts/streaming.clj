#!/usr/bin/env bb

(require '[co.poyo.clj-llm.core :as llm]
         '[co.poyo.clj-llm.backends.openai :as openai]
         '[clojure.string :as str])

;; Works with OPENAI_API_KEY or OPENROUTER_KEY
(def provider
  (let [openrouter-key (System/getenv "OPENROUTER_KEY")]
    (if openrouter-key
      (openai/backend {:api-key openrouter-key
                        :api-base "https://openrouter.ai/api/v1"})
      (openai/backend))))

(def ai (assoc provider :defaults {:model (or (System/getenv "LLM_MODEL") "gpt-4o-mini")}))

;; stream: lazy seq of text chunks
(println "--- stream ---")
(doseq [chunk (llm/stream ai "Write a haiku about Clojure")]
  (print chunk) (flush))
(println)

;; stream-print: prints + returns full text
(println "\n--- stream-print ---")
(let [text (llm/stream-print ai "Count from 1 to 5, one per line")]
  (println "Got back:" (count text) "chars"))

;; stream with opts
(println "\n--- stream with system prompt ---")
(println (str/join (llm/stream ai {:system-prompt "Respond only in ALL CAPS"} "Say hello")))
