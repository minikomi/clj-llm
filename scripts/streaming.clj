#!/usr/bin/env bb

(require '[clojure.core.async :as a]
         '[co.poyo.clj-llm.core :as llm]
         '[co.poyo.clj-llm.backends.openai :as openai])

;; Works with OPENAI_API_KEY or OPENROUTER_KEY
(def ai
  (let [k (System/getenv "OPENROUTER_KEY")]
    (-> (if k
          (openai/backend {:api-key k :api-base "https://openrouter.ai/api/v1"})
          (openai/backend))
        (assoc :defaults {:model (or (System/getenv "LLM_MODEL") "gpt-4o-mini")}))))

;; stream: channel of text chunks
(println "--- stream ---")
(let [ch (llm/stream ai "Write a haiku about Clojure")]
  (loop []
    (when-let [chunk (a/<!! ch)]
      (print chunk) (flush)
      (recur))))
(println)

;; collect into string
(println "\n--- stream into string ---")
(let [ch (llm/stream ai "Count from 1 to 5, one per line")
      sb (StringBuilder.)]
  (loop []
    (if-let [chunk (a/<!! ch)]
      (do (.append sb chunk) (recur))
      (do (println (str sb))
          (println "Got back:" (.length sb) "chars")))))

;; stream with opts
(println "\n--- stream with system prompt ---")
(let [ch (llm/stream ai {:system-prompt "Respond only in ALL CAPS"} "Say hello")
      sb (StringBuilder.)]
  (loop []
    (if-let [chunk (a/<!! ch)]
      (do (.append sb chunk) (recur))
      (println (str sb)))))
