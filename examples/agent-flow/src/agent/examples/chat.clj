(ns agent.examples.chat
  "Interactive chat agent example"
  (:require [agent.core :as agent]
            [agent.tools :as tools]
            [co.poyo.clj-llm.backends.openai :as openai]
            [clojure.core.async :refer [<!!]]
            [clojure.string :as str]))

(defn print-stream [chunks]
  (loop []
    (when-let [chunk (<!! chunks)]
      (if (map? chunk)
        (when-let [error (:error chunk)]
          (println "\n❌ Error:" (.getMessage error)))
        (do (print chunk)
            (flush)))
      (recur))))

(defn -main [& args]
  (println "\n💬 Interactive Chat Agent\n")
  (println "Type 'quit' to exit, 'memory' to see agent memory, or 'tools' to list available tools.")
  (println (apply str (repeat 50 "-")))

  ;; Create LLM backend
  (let [llm (openai/backend {:api-key-env "OPENAI_API_KEY"
                             :default-model "gpt-4o-mini"})

        ;; Create chat agent with memory and tools
        memory (agent/make-memory)
        chat-agent (agent/create-agent
                    {:llm-provider llm
                     :system-prompt "You are a helpful AI assistant with access to various tools. You can remember important information from our conversation."
                     :tools tools/basic-tools
                     :memory memory
                     :options {:max-steps 3}})

        ;; Conversation history
        messages (atom [])]

    ;; Remember user name if provided
    (println "\n🤖 Assistant: Hello! I'm your AI assistant. What's your name?")
    (print "👤 You: ")
    (flush)

    (when-let [name (read-line)]
      (agent/remember memory :user-name name)
      (println (str "\n🤖 Assistant: Nice to meet you, " name "! How can I help you today?")))

    ;; Main chat loop
    (loop []
      (print "\n👤 You: ")
      (flush)

      (when-let [input (read-line)]
        (cond
          ;; Exit
          (= (str/lower-case input) "quit")
          (println "\n👋 Goodbye!")

          ;; Show memory
          (= (str/lower-case input) "memory")
          (do
            (println "\n💾 Agent Memory:")
            (doseq [[k v] (agent/memories memory)]
              (println "  " k ":" v))
            (recur))

          ;; List tools
          (= (str/lower-case input) "tools")
          (do
            (println "\n🔧 Available Tools:")
            (doseq [tool tools/basic-tools]
              (println "  -" (agent/tool-name tool) ":" (agent/tool-description tool)))
            (recur))

          ;; Check for tool request
          (str/includes? (str/lower-case input) "use")
          (do
            (print "\n🤖 Assistant: ")
            (let [result (agent/think chat-agent input)]
              (if (:success result)
                (println (:answer result))
                (println "I encountered an error:" (:error result))))
            (recur))

          ;; Regular conversation
          :else
          (do
            ;; Update conversation history
            (swap! messages conj {:role "user" :content input})

            ;; Stream response
            (print "\n🤖 Assistant: ")
            (let [response-chunks (agent/stream-converse chat-agent @messages)]
              (print-stream response-chunks))

            ;; For simplicity, we'll regenerate to get the full response for history
            (let [full-response (agent/converse chat-agent @messages)]
              (swap! messages conj {:role "assistant" :content full-response}))

            (println)
            (recur)))))))