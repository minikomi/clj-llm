(ns agent.examples.weather
  "Weather assistant agent example"
  (:require [agent.core :as agent]
            [agent.tools :as tools]
            [co.poyo.clj-llm.backends.openai :as openai]
            [clojure.core.async :refer [<!!]]))

(defn -main [& args]
  (println "\n🌤️  Weather Assistant Agent\n")

  ;; Create LLM backend
  (let [llm (openai/backend {:api-key-env "OPENAI_API_KEY"
                             :default-model "gpt-4o-mini"})

        ;; Create weather agent with tools
        weather-agent (agent/create-agent
                       {:llm-provider llm
                        :system-prompt "You are a helpful weather assistant. Use the available tools to answer questions about weather and time."
                        :tools [tools/weather-tool
                                tools/time-tool
                                tools/calculator-tool]
                        :options {:max-steps 3}})

        ;; Example queries
        queries ["What's the weather in Tokyo?"
                 "If it's 72°F in New York, what is that in Celsius?"
                 "What time is it now, and what's the weather in London?"]]

    (doseq [query queries]
      (println "👤 User:" query)
      (println)

      (let [result (agent/think weather-agent query)]
        (if (:success result)
          (do
            (println "🤖 Agent:" (:answer result))
            (when (seq (:observations result))
              (println "\n📊 Tools used:")
              (doseq [obs (:observations result)]
                (println "  -" (:tool obs) "with" (:parameters obs)))))
          (println "❌ Error:" (:error result))))

      (println "\n" (apply str (repeat 50 "-")) "\n"))

    (println "✅ Weather assistant demo complete!")))