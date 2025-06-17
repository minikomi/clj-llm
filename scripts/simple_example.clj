#!/usr/bin/env bb

(ns simple-example
  (:require [babashka.classpath :as cp]))

;; Add the source directory to classpath
(cp/add-classpath "src")

(require '[co.poyo.clj-llm.core :as llm]
         '[co.poyo.clj-llm.backends.openai :as openai]
         '[clojure.core.async :refer [<!!]])

(defn -main []
  (println "\n🤖 clj-llm Simple Examples\n")

  ;; Create backend
  (let [ai (try
             (openai/backend {:api-key-env "OPENAI_API_KEY"})
             (catch Exception e
               (println "❌ Error:" (ex-message e))
               (System/exit 1)))]

    ;; Example 1: Simple generation
    (println "1️⃣ Simple text generation:")
    (println "Q: What is 2+2?")
    (print "A: ")
    (println (llm/generate ai "What is 2+2?" {:model "gpt-4o-mini"}))
    (println)

    ;; Example 2: Structured output
    (println "2️⃣ Structured output extraction:")
    (println "Extract: John Doe is a 30 year old software engineer")
    (let [schema [:map
                  [:name :string]
                  [:age pos-int?]
                  [:occupation :string]]
          result (llm/generate ai
                               "Extract: John Doe is a 30 year old software engineer"
                               {:model "gpt-4o-mini"
                                :schema schema})]
      (println "Result:" result))
    (println)

    ;; Example 3: Streaming
    (println "3️⃣ Streaming response:")
    (print "Story: ")
    (let [chunks (llm/stream ai
                             "Tell me a very short story about a robot (2 sentences)"
                             {:model "gpt-4o-mini"
                              :temperature 0.8})]
      (loop []
        (when-let [chunk (<!! chunks)]
          (print chunk)
          (flush)
          (recur))))
    (println "\n")

    ;; Example 4: Conversation
    (println "4️⃣ Conversation with context:")
    (let [messages [{:role :system :content "You are a pirate. Answer in pirate speak."}
                    {:role :user :content "Hello there!"}
                    {:role :assistant :content "Ahoy there, matey! What brings ye to these waters?"}
                    {:role :user :content "What's your favorite treasure?"}]]
      (print "Pirate says: ")
      (println (llm/generate ai nil {:model "gpt-4o-mini"
                                     :messages messages})))

    (println "\n✅ All examples completed!")))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))