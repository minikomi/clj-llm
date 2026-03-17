#!/usr/bin/env bb

(require '[co.poyo.clj-llm.core :as llm]
         '[co.poyo.clj-llm.backend.openai :as openai]
         '[co.poyo.clj-llm.backend.openrouter :as openrouter])

(def user-input (or (first *command-line-args*) "Write a haiku about Clojure"))
(def start-time (System/nanoTime))
(def state (volatile! {}))

(defn format-ms [ns]
  (format "%.2fs" (/ (double ns) 1e9)))

(defn print-section-title [title]
  (println "\n---" title "---\nFirst token:" (format-ms (- (System/nanoTime) start-time)) ":"))

;; Provider
;; prefer OPENROUTER_KEY if set, otherwise fall back to regular OpenAI backend
(def provider
  (cond->
      (if (System/getenv "OPENROUTER_KEY")
        (openrouter/backend)
        (openai/backend))
    ;; Override default model
    (System/getenv "LLM_MODEL")
    (assoc-in [:defaults :model] (System/getenv "LLM_MODEL"))))

;; Generate Settings
(def ai-settings
  {:system-prompt "You are a helpful guy working at the gas station."

   :on-reasoning
   (fn [chunk]
     (when-not (:first-reasoning-token @state)
       (let [now (System/nanoTime)]
         (vswap! state assoc :first-reasoning-token now)
         (print-section-title "Reasoning")))
     (print chunk)
     (flush))

   :on-text
   (fn [chunk]
     (when-not (:first-text-token @state)
       (let [now (System/nanoTime)]
         (vswap! state assoc :first-text-token now)
         (print-section-title "Text")))
     (print chunk)
     (flush))})

;; Run
(println provider)
(println "--- Start ---\nRequest Sent: " (format-ms (- (System/nanoTime) start-time)) "\n")
(llm/generate provider ai-settings user-input)
(println "\n--- Done ---\nTotal time:" (format-ms (- (System/nanoTime) start-time)))
