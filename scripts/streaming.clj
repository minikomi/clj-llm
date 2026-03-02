#!/usr/bin/env bb

(require '[co.poyo.clj-llm.core :as llm]
         '[co.poyo.clj-llm.backends.openai :as openai])

;; Works with OPENAI_API_KEY or OPENROUTER_KEY
(def provider
  (let [openrouter-key (System/getenv "OPENROUTER_KEY")]
    (if openrouter-key
      (openai/backend {:api-key openrouter-key
                        :api-base "https://openrouter.ai/api/v1"})
      (openai/backend))))

(def ai (assoc provider :defaults {:model (or (System/getenv "LLM_MODEL") "gpt-4o-mini")}))

;; stream-print: prints chunks live, returns full result
(println "--- stream-print ---")
(let [{:keys [text]} (llm/stream-print ai "Write a haiku about Clojure")]
  (println "Got back:" (count text) "chars"))

;; request: reduce over raw events for full control
(println "\n--- request + reduce ---")
(let [text (reduce (fn [sb event]
                     (when (= :content (:type event))
                       (print (:content event))
                       (flush)
                       (.append sb (:content event)))
                     sb)
                   (StringBuilder.)
                   (llm/request ai "Count from 1 to 5, one per line"))]
  (println)
  (println "Got back:" (.length text) "chars"))

;; request with opts
(println "\n--- request with system prompt ---")
(reduce (fn [_ event]
          (when (= :content (:type event))
            (print (:content event))
            (flush)))
        nil
        (llm/request ai {:system-prompt "Respond only in ALL CAPS"} "Say hello"))
(println)
