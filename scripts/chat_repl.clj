#!/usr/bin/env bb

(ns chat-repl
  (:require [co.poyo.clj-llm.core :as llm]
            [co.poyo.clj-llm.backends.openai :as openai]
            [clojure.core.async :refer [<!!]]))



(defn print-streaming-response
  "Print response chunks as they arrive"
  [chunks-chan]
  (loop []
    (when-let [chunk (<!! chunks-chan)]
      (print chunk)
      (flush)
      (recur))))

(defn -main [& args]
  (let [model-name (first args)]

    (when-not model-name
      (println "Usage: chat-repl <model-name>")
      (println "Examples:")
      (println "  ./scripts/chat_repl.clj gpt-4o")
      (println "  ./scripts/chat_repl.clj gpt-4o-mini")
      (println "  ./scripts/chat_repl.clj claude-3-sonnet-20240229")
      (System/exit 1))

    ;; Create backend - will validate API key
    (let [backend (try
                    (openai/backend {:api-key-env "OPENAI_API_KEY"})
                    (catch Exception e
                      (println "Error:" (ex-message e))
                      (println "\nMake sure OPENAI_API_KEY is set in your environment")
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
        (print "You> ")
        (flush)

        (let [input (read-line)]
          (if (empty? input)
            (do
              (println "\n👋 Goodbye!")
              (System/exit 0))

            (do
              ;; Add user message to conversation
              (swap! conversation conj {:role :user :content input})

              (print "\nAI> ")
              (flush)

              (try
                ;; Stream the response
                (let [chunks (llm/stream backend nil
                                         {:model model-name
                                          :messages @conversation
                                          :temperature 0.7})
                      ;; Collect response for history
                      response-text (atom "")]

                  ;; Print chunks and collect full response
                  (loop []
                    (when-let [chunk (<!! chunks)]
                      (print chunk)
                      (flush)
                      (swap! response-text str chunk)
                      (recur)))

                  ;; Add assistant response to conversation
                  (swap! conversation conj
                         {:role :assistant :content @response-text}))

                (catch Exception e
                  (println (str "\n❌ Error: " (ex-message e)))
                  ;; Don't add error to conversation history
                  ))

              (println "\n")
              (recur))))))))

(apply -main *command-line-args*)
