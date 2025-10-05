#!/usr/bin/env bb

(ns chat-repl
  (:require [co.poyo.clj-llm.core :as llm]
            [co.poyo.clj-llm.backends.openai :as openai]
            [clojure.core.async :refer [<!!]]
            [clojure.string :as str]))

(defn print-flush [s]
  (print s)
  (flush))

(def gpt5-extra-opts
  {:verbosity "low"
   :reasoning-effort "minimal"})

(defn -main [& args]
  (let [model-name (or (first args) "gpt-5-nano")]

    ;; Create provider - will validate API key
    (let [provider (try
                     (openai/->openai)
                     (catch Exception e
                       (println "Error:" (ex-message e))
                       (System/exit 1)))

          ;; Track conversation history
          conversation (atom [])]

      (println (str "\n🤖 Chat REPL with model: " model-name))
      (println "Type your message and press Enter (empty line to exit)")
      (println "======================================================\n")

      ;; Add system prompt to conversation
      (swap! conversation conj
             {:role :system
              :content "You are a helpful AI assistant. Be concise but thorough."})

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
                (let [opts #:co.poyo.clj-llm.core{:message-history @conversation
                                                  :provider-opts (cond-> {:model model-name}
                                                                   (str/starts-with? model-name "gpt-5-")
                                                                   (merge gpt5-extra-opts))}
                      chunks (:chunks (llm/prompt provider input opts))
                      response-text (atom "")]

                  ;; Print chunks and collect full response
                  (loop []
                    (when-let [chunk (<!! chunks)]
                      (print-flush chunk)
                      (swap! response-text str chunk)
                      (recur)))

                  ;; Add assistant response to conversation
                  (swap! conversation conj
                         {:role :user :content input}
                         {:role :assistant :content @response-text}))

                (catch Exception e
                  (println (str "\n❌ Error: " (ex-message e)))
                  (print-flush e)))

              (println "\n")
              (recur))))))))

(apply -main *command-line-args*)
