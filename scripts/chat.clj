#!/usr/bin/env bb

(require '[co.poyo.clj-llm.core :as llm]
         '[co.poyo.clj-llm.backends.openai :as openai])

(def model (or (first *command-line-args*)
               (System/getenv "LLM_MODEL")
               "gpt-4o-mini"))

;; Works with OPENAI_API_KEY or OPENROUTER_KEY
(def ai
  (let [openrouter-key (System/getenv "OPENROUTER_KEY")]
    (-> (if openrouter-key
          (openai/backend {:api-key openrouter-key
                            :api-base "https://openrouter.ai/api/v1"})
          (openai/backend))
        (assoc :defaults {:model model}))))

(def history (atom [{:role :system :content "You are a helpful assistant. Be concise."}]))

(println (str "Chat with " model " (Ctrl-C to quit)"))
(println)

(loop []
  (print "> ")
  (flush)
  (when-let [input (read-line)]
    (when-not (empty? input)
      (swap! history conj {:role :user :content input})
      (let [{:keys [text]} (llm/stream-print ai @history)]
        (swap! history conj {:role :assistant :content text}))
      (println))
    (recur)))
