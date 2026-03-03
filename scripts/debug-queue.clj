#!/usr/bin/env bb

(require '[co.poyo.clj-llm.core :as llm]
         '[co.poyo.clj-llm.backends.openai :as openai])

(def ai
  (let [k (System/getenv "OPENROUTER_KEY")]
    (-> (if k
          (openai/backend {:api-key k :api-base "https://openrouter.ai/api/v1"})
          (openai/backend))
        (assoc :defaults {:model (or (System/getenv "LLM_MODEL") "gpt-4o-mini")}))))

(println "1. calling events...")
(let [evts (llm/events ai "Say hi")]
  (println "2. got lazy seq")
  (println "3. first:" (first evts))
  (println "4. rest:" (take 3 (rest evts))))
(println "5. done")
