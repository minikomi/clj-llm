#!/usr/bin/env bb

(require '[co.poyo.clj-llm.core :as llm]
         '[co.poyo.clj-llm.backends.openai :as openai]
         '[clojure.core.async :refer [<!!]])

;; Works with OPENAI_API_KEY or OPENROUTER_KEY
(def ai
  (let [openrouter-key (System/getenv "OPENROUTER_KEY")]
    (-> (if openrouter-key
          (openai/->openai {:api-key openrouter-key
                            :api-base "https://openrouter.ai/api/v1"})
          (openai/->openai))
        (llm/with-defaults {:model (or (System/getenv "LLM_MODEL") "gpt-4o-mini")}))))

;; Easy way — prints as it streams, returns full text
(println "--- stream-print ---")
(def full-text (llm/stream-print ai "Tell me a very short story about a robot."))
(println "(returned" (count full-text) "chars)")

;; Channel way — for custom processing
(println "\n--- raw channel ---")
(let [ch (llm/stream ai "Count to 5, one per line.")]
  (loop []
    (when-let [chunk (<!! ch)]
      (print chunk)
      (flush)
      (recur)))
  (println))

;; Events — see everything the provider sends
(println "\n--- events ---")
(let [ch (llm/events ai "Say hi.")]
  (loop []
    (when-let [event (<!! ch)]
      (println event)
      (when-not (= :done (:type event))
        (recur)))))
