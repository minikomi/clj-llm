#!/usr/bin/env bb

(ns chat-repl
  (:require [co.poyo.clj-llm.core :as llm]
            [co.poyo.clj-llm.backends.openai :as openai]
            [clojure.core.async :refer [<!!]]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]))

(def cli-options
  [["-m" "--model MODEL" "Model to use"
    :default "gpt-5-nano"]
   ["-s" "--system-prompt PROMPT" "Custom system prompt"
    :default (str "You are a helpful, Rich Hickey like person. "
                  "Give short, well planned out, concise answers "
                  "in line with Rich Hickey's philosophies.")]
   ["-u" "--show-usage" "Show token usage after each response"
    :default false
    :flag true]
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
        "  chat-repl -s 'You are a pirate' -m gpt-4o"]
       (str/join \newline)))

(defn print-flush [s] (print s) (flush))

(defn greeting [model system-prompt]
  (println (str "\n🤖 Chat REPL with model: " model))
  (println (str "📝 System prompt: " (subs system-prompt 0 (min 50 (count system-prompt))) "..."))
  (println "Type your message and press Enter (empty line to exit)")
  (println "======================================================"))

(defn print-usage [usage]
  (let [{:keys [prompt-tokens completion-tokens total-tokens
                clj-llm/duration]} usage
        seconds (/ duration 1000.0)]
    (println "\n\n======================================================")
    (println "📊 Usage Stats:")
    (println (format "Tokens: %d in / %d out / %d total"
                     prompt-tokens completion-tokens total-tokens))
    (println (format "Duration: %.2fs / tps: %.2f" seconds (if (pos? seconds)
                                                             (/ completion-tokens seconds)
                                                             "?")))
    (when-let [reasoning (:reasoning-tokens (:completion-tokens-details usage))]
      (when (pos? reasoning)
        (println (format "  Reasoning tokens: %d" reasoning))))))

(defn main-loop [{:keys [model system-prompt show-usage]}]
  (let [provider-opts (when (str/starts-with? model "gpt-5")
                        {:verbosity "low"
                         :reasoning-effort "minimal"})
        provider (-> (openai/->openai)
                     (llm/with-model model)
                     (llm/with-system-prompt system-prompt)
                     (llm/with-provider-opts provider-opts))
        conversation (atom [])]
    (greeting model system-prompt)
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
              (let [{:keys [chunks usage]} (llm/prompt provider input {::llm/message-history @conversation})
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
