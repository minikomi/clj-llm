(ns agent.main
  "Main entry point for agent examples"
  (:require [clojure.string :as str]))

(defn print-usage []
  (println "
Agent Flow Examples
==================

Usage: clojure -M:run [example]

Available examples:
  weather   - Weather assistant that can check weather and do temperature conversions
  research  - Research assistant that can plan and execute research tasks
  chat      - Interactive chat agent with memory and tool access
  coder     - Code assistant that can analyze and generate code

Or use the aliases directly:
  clojure -M:weather
  clojure -M:research  
  clojure -M:chat
  clojure -M:coder
"))

(defn -main [& args]
  (let [example (first args)]
    (case example
      "weather" (require 'agent.examples.weather)
      "research" (require 'agent.examples.research)
      "chat" (require 'agent.examples.chat)
      "coder" (require 'agent.examples.coder)
      nil (print-usage)
      (do
        (println (str "Unknown example: " example))
        (print-usage)))

    (when example
      (let [ns-sym (symbol (str "agent.examples." example))]
        (when-let [main-fn (ns-resolve ns-sym '-main)]
          (main-fn))))))