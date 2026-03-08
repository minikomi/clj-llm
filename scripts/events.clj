#!/usr/bin/env bb

(require '[clojure.core.async :as a]
         '[co.poyo.clj-llm.core :as llm]
         '[co.poyo.clj-llm.backends.openai :as openai])

;; Works with OPENAI_API_KEY or OPENROUTER_KEY
(def ai
  (let [k (System/getenv "OPENROUTER_KEY")
        model (or (System/getenv "LLM_MODEL") "gpt-5-mini")]
    (-> (if k
          (openai/backend {:api-key k :api-base "https://openrouter.ai/api/v1"})
          (openai/backend))
        (assoc :defaults {:model model}))))

(print (format "Raw events from llm/events... [backend: %s, model: %s]\n\n"
               (:api-base ai)
               (get-in ai [:defaults :model])))
(flush)

(def t0 (System/currentTimeMillis))
(def ch (llm/events ai "Count from 1 to 5, one per line."))
(def n (atom 0))

(loop []
  (when-let [event (a/<!! ch)]
    (let [elapsed (- (System/currentTimeMillis) t0)
          i (swap! n inc)]
      (prn {:time elapsed :index i :event event}))
    (recur)))
