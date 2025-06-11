#!/usr/bin/env bb
;;
;; Stream canonical LLM events (content / tool-call chunks / usage)
;; from the new OpenAI backend to stdout in real-time.
;;
(require '[co.poyo.clj-llm.core  :as llm]
         '[co.poyo.clj-llm.backends.openai :as openai]
         '[clojure.core.async   :refer [<!!]]
         '[clojure.edn          :as edn])

(openai/register)

(defn usage []
  (println "usage: openai_stream.clj \"your prompt here\"")
  (System/exit 1))

;; ─────────── config ───────────
(when-not (System/getenv "OPENAI_API_KEY")
  (binding [*out* *err*]
    (println "OPENAI_API_KEY env var is missing") (usage)))

;; ─────────── run ───────────
(let [{:keys [chunks]} (llm/prompt :openai/gpt-4.1-nano (first *command-line-args*)
                                    )]
  (loop []
    (when-some [ev (<!! chunks)]   ; nil ⇒ channel closed
      (prn ev) (flush)
      (recur))))
