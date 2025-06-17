(ns agent.examples.coder
  "Code assistant agent example"
  (:require [agent.core :as agent]
            [agent.tools :as tools]
            [co.poyo.clj-llm.backends.openai :as openai]
            [clojure.core.async :refer [<!!]]))

;; Custom code analysis tool
(def analyze-code-tool
  (agent/make-tool
   {:name "analyze-code"
    :description "Analyze code for issues, patterns, or improvements"
    :schema [:map
             [:code :string]
             [:language :string]]
    :fn (fn [{:keys [code language]}]
          ;; Mock analysis - in production, use real static analysis
          {:language language
           :lines (count (clojure.string/split-lines code))
           :analysis "Code appears to be well-structured"
           :suggestions ["Consider adding more documentation"
                         "Could benefit from error handling"]})}))

;; Code generation tool
(def generate-code-tool
  (agent/make-tool
   {:name "generate-code"
    :description "Generate code snippets or boilerplate"
    :schema [:map
             [:description :string]
             [:language :string]]
    :fn (fn [{:keys [description language]}]
          ;; Mock generation - just return a template
          (case language
            "clojure" {:code "(defn my-function [x]\n  ;; TODO: implement\n  x)"}
            "python" {:code "def my_function(x):\n    # TODO: implement\n    return x"}
            "javascript" {:code "function myFunction(x) {\n  // TODO: implement\n  return x;\n}"}
            {:code (str ";; " language " code template")}))}))

(defn -main [& args]
  (println "\n👨‍💻 Code Assistant Agent\n")

  ;; Create LLM backend
  (let [llm (openai/backend {:api-key-env "OPENAI_API_KEY"
                             :default-model "gpt-4o-mini"})

        ;; Create code assistant agent
        memory (agent/make-memory)
        code-agent (agent/create-agent
                    {:llm-provider llm
                     :system-prompt "You are an expert programming assistant. Help users with code analysis, generation, debugging, and best practices. You can analyze code, suggest improvements, and generate code snippets."
                     :tools [analyze-code-tool
                             generate-code-tool
                             tools/read-file-tool
                             tools/write-file-tool
                             tools/calculator-tool]
                     :memory memory
                     :options {:max-steps 4}})

        ;; Example tasks
        tasks [{:task "Analyze this Clojure function and suggest improvements:\n```clojure\n(defn add [x y] (+ x y))\n```"
                :type :analysis}

               {:task "Generate a Python function that calculates factorial"
                :type :generation}

               {:task "What are the best practices for error handling in Clojure?"
                :type :advice}

               {:task "Help me refactor this code to be more functional:\n```javascript\nlet total = 0;\nfor (let i = 0; i < arr.length; i++) {\n  total += arr[i];\n}\n```"
                :type :refactoring}]]

    (doseq [{:keys [task type]} tasks]
      (println "📋 Task:" task)
      (println "Type:" type)
      (println)

      ;; For code generation tasks, use chain of thought
      (if (= type :generation)
        (let [cot-response (agent/think-step-by-step code-agent task)]
          (println "🤖 Assistant (Chain of Thought):")
          (println cot-response))

        ;; For other tasks, use regular thinking with tools
        (let [result (agent/think code-agent task)]
          (if (:success result)
            (do
              (println "🤖 Assistant:" (:answer result))

              ;; Remember important patterns
              (when (= type :advice)
                (agent/remember memory :best-practices (:answer result)))

              (when (seq (:observations result))
                (println "\n📊 Tools used:")
                (doseq [obs (:observations result)]
                  (println "  -" (:tool obs)))))
            (println "❌ Error:" (:error result)))))

      (println "\n" (apply str (repeat 60 "-")) "\n"))

    ;; Show what the agent learned
    (when-let [practices (agent/recall memory :best-practices)]
      (println "📚 Remembered Best Practices:")
      (println (subs practices 0 (min 200 (count practices))) "...")
      (println))

    (println "✅ Code assistant demo complete!")))