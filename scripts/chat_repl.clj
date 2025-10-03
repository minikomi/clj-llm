#!/usr/bin/env bb

(ns chat-repl
  (:require [co.poyo.clj-llm.core :as llm]
            [co.poyo.clj-llm.backends.openai :as openai]
            [clojure.core.async :refer [<!!]]
            [clojure.string :as str]))

(defn print-streaming-response
  "Print response chunks as they arrive"
  [chunks-chan]
  (loop []
    (when-let [chunk (<!! chunks-chan)]
      (print chunk)
      (flush)
      (recur))))

(defn -main [& args]
  (let [model-name (or (first args) "gpt-5-nano")]

    ;; Create provider - will validate API key
    (let [provider (try
                     (openai/->openai {:api-key-env "OPENAI_API_KEY"})
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
              (print "\nAI> ")
              (flush)

              (try
                ;; Stream the response
                (let [chunks (:chunks (llm/prompt provider input
                                                  (cond->
                                                   {:message-history @conversation
                                                    :provider-opts {:model model-name}}
                                                    (str/starts-with? model-name "gpt-5-")
                                                    (->
                                                     (assoc-in [:provider-opts :verbosity] "low")
                                                     (assoc-in [:provider-opts :reasoning-effort] "minimal")))))
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
                         {:role :user :content input}
                         {:role :assistant :content @response-text}))

                (catch Exception e
                  (println (str "\n❌ Error: " (ex-message e)))
                  (print e)
                  ;; Don't add error to conversation history
                  ))

              (println "\n")
              (recur))))))))

(apply -main *command-line-args*)
