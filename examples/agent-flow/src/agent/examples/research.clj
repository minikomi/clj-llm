(ns agent.examples.research
  "Research assistant agent example"
  (:require [agent.core :as agent]
            [agent.tools :as tools]
            [co.poyo.clj-llm.backends.openai :as openai]
            [clojure.core.async :refer [<!!]]))

(defn -main [& args]
  (println "\n🔬 Research Assistant Agent\n")

  ;; Create LLM backend
  (let [llm (openai/backend {:api-key-env "OPENAI_API_KEY"
                             :default-model "gpt-4o-mini"})

        ;; Create research agent with memory
        memory (agent/make-memory)
        research-agent (agent/create-agent
                        {:llm-provider llm
                         :system-prompt "You are a research assistant. Help users gather information, analyze data, and create summaries. Remember important findings in your memory."
                         :tools [tools/search-tool
                                 tools/calculator-tool
                                 tools/time-tool]
                         :memory memory
                         :options {:max-steps 5}})

        ;; Research task
        task "Research the benefits of functional programming and create a summary with key points."]

    (println "📋 Research Task:" task)
    (println)

    ;; First, create a plan
    (println "📝 Creating research plan...")
    (let [plan (agent/plan research-agent task)]
      (println "\nPlan:")
      (println "Goal:" (:goal plan))
      (println "\nSteps:")
      (doseq [step (:steps plan)]
        (println (str "  " (:step step) ". " (:action step))
                 "\n     Reason:" (:reason step)))
      (println "\nSuccess Criteria:")
      (doseq [criterion (:success-criteria plan)]
        (println "  -" criterion)))

    (println "\n" (apply str (repeat 50 "-")) "\n")

    ;; Execute the research
    (println "🔍 Conducting research...")
    (let [result (agent/think research-agent task)]
      (if (:success result)
        (do
          (println "\n📊 Research Complete!")
          (println "\nFindings:")
          (println (:answer result))

          ;; Store in memory
          (agent/remember memory :research-functional-programming (:answer result))
          (agent/remember memory :research-date (str (java.time.LocalDate/now)))

          (when (seq (:observations result))
            (println "\n🔧 Tools used:")
            (doseq [obs (:observations result)]
              (println "  -" (:tool obs)))))
        (println "❌ Error:" (:error result))))

    (println "\n💾 Memory contents:")
    (doseq [[k v] (agent/memories memory)]
      (println "  " k ":" (if (string? v)
                            (str (subs v 0 (min 60 (count v))) "...")
                            v)))

    (println "\n✅ Research assistant demo complete!")))