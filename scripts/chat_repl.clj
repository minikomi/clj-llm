#!/usr/bin/env bb

(ns chat-repl
  (:require [co.poyo.clj-llm.core :as llm]
            [co.poyo.clj-llm.backends.openai :as openai]
            [co.poyo.clj-llm.backends.anthropic :as anthropic]
            [clojure.core.async :refer [<!!]]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]))

(def cli-options
  [["-p" "--provider PROVIDER" "Provider to use (openai or anthropic)"
    :default "openai"
    :validate [#(#{"openai" "anthropic"} %) "Must be 'openai' or 'anthropic'"]]
   ["-m" "--model MODEL" "Model to use" :default "gpt-5-nano"]
   ["-s" "--system-prompt PROMPT" "Custom system prompt"
    :default (str "You are a helpful, Rich Hickey like person."
                  "Give short, well planned out, concise answers."
                  "in line with Rich Hickey's philosophies.")
    :default-desc "You are helpful, Rich Hickey like person.."]
   ["-u" "--show-usage" "Show token usage after each response" :default false]
   ["-h" "--help" "Show this help"]])

(defn usage [summary]
  (->> ["Chat REPL - Interactive LLM conversation"
        ""
        "Usage: chat-repl [options]"
        ""
        "Options:"
        summary
        ""
        "Examples:"
        "  chat-repl"
        "  chat-repl -m gpt-4o"
        "  chat-repl -p anthropic -m claude-sonnet-4-5"
        "  chat-repl -s 'You are a pirate' -m gpt-4o"]
       (str/join \newline)))

(defn print-flush [s] (print s) (flush))

(defn greeting [provider model system-prompt]
  (println (str "\n🤖 Chat REPL with " provider " / " model))
  (println (str "📝 System prompt: " (subs system-prompt 0 (min 50 (count system-prompt))) "..."))
  (println "Type your message and press Enter (empty line to exit)")
  (println "======================================================"))

(defn print-usage [usage]
  (let [{:keys [prompt-tokens completion-tokens total-tokens
                input-tokens output-tokens
                clj-llm/duration]} usage
        ;; Support both OpenAI (prompt-tokens/completion-tokens) and Anthropic (input-tokens/output-tokens)
        in-tokens (or prompt-tokens input-tokens 0)
        out-tokens (or completion-tokens output-tokens 0)
        total (or total-tokens (+ in-tokens out-tokens))
        seconds (/ duration 1000.0)]
    (println "\n======================================================\n")
    (println "\n📊 Usage Stats:")
    (println (format "  Tokens: %d in / %d out / %d total"
                     in-tokens out-tokens total))
    (println (format "  Duration: %.2fs / tps: %.2f" seconds (if (pos? seconds)
                                                               (/ out-tokens seconds)
                                                               "?")))
    (when-let [reasoning (:reasoning-tokens (:completion-tokens-details usage))]
      (when (pos? reasoning)
        (println (format "  Reasoning tokens: %d" reasoning))))))

(defn main-loop [{:keys [provider model system-prompt show-usage]}]
  (let [provider-opts (when (str/starts-with? model "gpt-5")
                        {:verbosity "low"
                         :reasoning-effort "minimal"})
        backend (case provider
                  "openai" (openai/->openai)
                  "anthropic" (anthropic/->anthropic))
        llm-provider (llm/with-defaults backend
                       {:model model
                        :system-prompt system-prompt
                        :provider-opts provider-opts})
        conversation (atom [])]
    (greeting provider model system-prompt)
    (loop []
      (print-flush "You> ")
      (let [input (read-line)]
        (if (empty? input)
          (do
            (println "\n👋 Goodbye!")
            (System/exit 0))
          (do
            (print-flush "\nAI> ")
            (try
              ;; Stream the response
              (let [{:keys [chunks usage]} (llm/prompt llm-provider input {:message-history @conversation})
                    response-text (atom "")]
                ;; Print chunks and collect full response
                (loop []
                  (when-let [chunk (<!! chunks)]
                    (print-flush chunk)
                    (swap! response-text str chunk)
                    (recur)))
                (when show-usage (print-usage @usage))
                ;; Add assistant response to conversation
                (swap! conversation conj
                       {:role :user :content input}
                       {:role :assistant :content @response-text}))
              (catch Exception e
                (println (str "\n❌ Error: " (ex-message e)))
                (print-flush e)))
            (println "\n")
            (recur)))))))

(defn -main [& args]
  (let [{:keys [options arguments summary opt-errors]} (parse-opts args cli-options)]
    (when opt-errors
      (println "Opt-Errors:")
      (doseq [error opt-errors]
        (println "  " error))
      (println)
      (println (usage summary))
      (System/exit 1))

    (when (:help options)
      (println (usage summary))
      (System/exit 0))

    (main-loop options)))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
