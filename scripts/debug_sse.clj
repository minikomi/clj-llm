#!/usr/bin/env bb
;;
;; Stream canonical LLM events (content / tool-call chunks / usage)
;; from the new OpenAI backend to stdout in real-time.
;;
(require '[co.poyo.clj-llm.core  :as llm]
         '[co.poyo.clj-llm.backends.openai :as openai]
         '[clojure.core.async   :refer [<!!]]
         '[clojure.edn          :as edn])

(openai/register-backend!)

(defn usage []
  (println "usage: openai_stream.clj \"your prompt here\"")
  (System/exit 1))

;; ─────────── config ───────────
(when-not (System/getenv "OPENAI_API_KEY")
  (binding [*out* *err*]
    (println "OPENAI_API_KEY env var is missing") (usage)))

(let [prompt (first *command-line-args*)]
  (when-not prompt
    (binding [*out* *err*]
      (println "Prompt is required") (usage)))

  ;; ─────────── stream ───────────

  (println "Streaming OpenAI response for prompt:" prompt)
  (let [chunks-chan (:chunks (llm/prompt :openai/gpt-4.1-nano prompt))]
    (loop []
      (let [chunk (<!! chunks-chan)]
        (when chunk
          (if (:content chunk)
            (do
              (print (:content chunk))
              (flush)
              (recur))
            ;; Handle tool calls or usage events if needed
            ))))))
