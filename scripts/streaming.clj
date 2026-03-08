#!/usr/bin/env bb

(require '[co.poyo.clj-llm.core :as llm]
         '[co.poyo.clj-llm.backends.openai :as openai])

;; Works with OPENAI_API_KEY or OPENROUTER_KEY
(def ai
  (let [k (System/getenv "OPENROUTER_KEY")]
    (-> (if k
          (openai/backend {:api-key k :api-base "https://openrouter.ai/api/v1"})
          (openai/backend))
        (assoc :defaults {:model (or (System/getenv "LLM_MODEL") "gpt-4o-mini")}))))

;; stream via :on-text callback
(println "--- stream ---")
(llm/generate ai {:on-text #(do (print %) (flush))} "Write a haiku about Clojure")
(println)

;; collect into string while streaming
(println "\n--- stream into string ---")
(let [sb (StringBuilder.)]
  (llm/generate ai {:on-text #(.append sb %)} "Count from 1 to 5, one per line")
  (println (str sb))
  (println "Got back:" (.length sb) "chars"))

;; stream with system prompt
(println "\n--- stream with system prompt ---")
(let [sb (StringBuilder.)]
  (llm/generate ai {:system-prompt "Respond only in ALL CAPS"
                    :on-text #(.append sb %)}
                "Say hello")
  (println (str sb)))
