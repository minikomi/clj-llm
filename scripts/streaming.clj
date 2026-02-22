#!/usr/bin/env bb

(require '[co.poyo.clj-llm.core :as llm]
         '[co.poyo.clj-llm.backends.openai :as openai]
         '[clojure.core.async :refer [<!!]])

;; Works with OPENAI_API_KEY or OPENROUTER_KEY
(def provider
  (let [openrouter-key (System/getenv "OPENROUTER_KEY")]
    (if openrouter-key
      (openai/backend {:api-key openrouter-key
                        :api-base "https://openrouter.ai/api/v1"})
      (openai/backend))))

(def ai (assoc provider :defaults {:model (or (System/getenv "LLM_MODEL") "gpt-4o-mini")}))

;; stream-print: prints chunks live, returns full text
(println "--- stream-print ---")
(let [text (llm/stream-print ai "Write a haiku about Clojure")]
  (println "\nGot back:" (count text) "chars"))

;; stream: returns a channel of text chunks
(println "\n--- stream channel ---")
(let [ch (llm/stream ai "Count from 1 to 5, one per line")]
  (loop []
    (when-let [chunk (<!! ch)]
      (print chunk)
      (flush)
      (recur)))
  (println))

;; stream with opts
(println "\n--- stream with system prompt ---")
(let [ch (llm/stream ai {:system-prompt "Respond only in ALL CAPS"} "Say hello")]
  (loop []
    (when-let [chunk (<!! ch)]
      (print chunk)
      (flush)
      (recur)))
  (println))
