(ns co.poyo.clj-llm.sandbox
  "REPL-friendly commands for testing all features of clj-llm"
  (:require [co.poyo.clj-llm.core :as llm]
            [co.poyo.clj-llm.backends.openai :as openai]
            [co.poyo.clj-llm.backends.anthropic :as anthropic]
            [co.poyo.clj-llm.errors :as errors]
            [clojure.core.async :refer [<!!]]
            [clojure.pprint :as pp]))

(comment
  ;; ==========================================
  ;; SETUP - Create backends for testing
  ;; ==========================================
  
  ;; OpenAI backend
  (def openai-backend
    (openai/backend {:api-key-env "OPENAI_API_KEY"}))
  
  ;; Anthropic backend (Claude)
  (def anthropic-backend
    (anthropic/backend {:api-key-env "ANTHROPIC_API_KEY"}))
  
  ;; OpenRouter backend (access to many models)
  (def openrouter-backend
    (openai/openrouter {:api-key-env "OPENROUTER_API_KEY"}))
  
  ;; Local model (e.g., Ollama)
  (def local-backend
    (openai/local {:api-base "http://localhost:11434/v1"
                   :default-model "llama2"}))

  ;; ==========================================
  ;; BASIC TEXT GENERATION
  ;; ==========================================
  
  ;; Simple text generation
  (llm/generate openai-backend "What is 2+2?")
  
  ;; With temperature control
  (llm/generate openai-backend
                "Write a creative story opening"
                {:temperature 0.9})
  
  ;; With specific model
  (llm/generate openai-backend
                "Explain quantum computing"
                {:model "gpt-4o"
                 :max-tokens 200})
  
  ;; With system prompt
  (llm/generate openai-backend
                "How do I make coffee?"
                {:system-prompt "You are a professional barista with 20 years experience"})

  ;; ==========================================
  ;; STRUCTURED OUTPUT WITH MALLI SCHEMAS
  ;; ==========================================
  
  ;; Simple person extraction
  (def person-schema
    [:map
     [:name :string]
     [:age pos-int?]
     [:occupation :string]])
  
  (llm/generate openai-backend
                "Extract: Marie Curie was a 66 year old physicist"
                {:schema person-schema})
  
  ;; Complex nested schema
  (def company-schema
    [:map
     [:name :string]
     [:founded :int]
     [:employees [:vector [:map
                           [:name :string]
                           [:role :string]
                           [:salary pos-int?]]]]
     [:locations [:vector :string]]])
  
  (llm/generate openai-backend
                "Extract: TechCorp was founded in 2010. Employees: Alice (CEO, $200k), Bob (Engineer, $120k). Offices in NYC and SF."
                {:schema company-schema})

  ;; ==========================================
  ;; STREAMING RESPONSES
  ;; ==========================================
  
  ;; Basic streaming
  (let [chunks (llm/stream openai-backend "Tell me a story about a robot")]
    (loop []
      (when-let [chunk (<!! chunks)]
        (print chunk)
        (flush)
        (recur))))
  
  ;; Stream with error handling
  (let [chunks (llm/stream openai-backend "Invalid model test" {:model "fake-model"})]
    (loop []
      (when-let [chunk (<!! chunks)]
        (if (map? chunk)
          (println "\nError received:" (:error chunk))
          (print chunk))
        (recur))))

  ;; ==========================================
  ;; RAW EVENTS STREAM
  ;; ==========================================
  
  ;; Monitor all events
  (let [events (llm/events openai-backend "Hello world")]
    (loop []
      (when-let [event (<!! events)]
        (println "Event:" (:type event))
        (when (= :usage (:type event))
          (println "Token usage:" (dissoc event :type)))
        (when (= :content (:type event))
          (print (:content event)))
        (recur))))

  ;; ==========================================
  ;; ADVANCED PROMPT FUNCTION
  ;; ==========================================
  
  ;; Rich response object
  (def resp (llm/prompt openai-backend "Explain artificial intelligence"))
  
  ;; Deref for text
  @resp
  
  ;; Access different aspects
  @(:text resp)       ;; Full text promise
  @(:usage resp)      ;; Token usage info
  (:chunks resp)      ;; Text chunks channel
  (:events resp)      ;; Raw events channel
  
  ;; With structured output
  (def structured-resp
    (llm/prompt openai-backend
                "Extract: John is 30 years old"
                {:schema person-schema}))
  
  @(:structured structured-resp)  ;; Structured data

  ;; ==========================================
  ;; CONVERSATION HANDLING
  ;; ==========================================
  
  ;; Simple conversation
  (def messages
    [{:role :system :content "You are a helpful assistant"}
     {:role :user :content "My name is Alice"}
     {:role :assistant :content "Nice to meet you, Alice!"}
     {:role :user :content "What's my name?"}])
  
  (llm/generate openai-backend nil {:messages messages})
  
  ;; Building conversation incrementally
  (def conversation (atom []))
  
  ;; Add system message
  (swap! conversation conj {:role :system :content "You are a poet"})
  
  ;; Add user message and get response
  (swap! conversation conj {:role :user :content "Write a haiku about code"})
  (def response (llm/generate openai-backend nil {:messages @conversation}))
  (swap! conversation conj {:role :assistant :content response})
  
  ;; Continue conversation
  (swap! conversation conj {:role :user :content "Now write one about bugs"})
  (llm/generate openai-backend nil {:messages @conversation})

  ;; ==========================================
  ;; ERROR HANDLING TESTING
  ;; ==========================================
  
  ;; Test invalid API key
  (try
    (let [bad-backend (openai/backend {:api-key "invalid-key"})]
      (llm/generate bad-backend "test"))
    (catch Exception e
      (println "Error type:" (errors/error-type e))
      (println "Retryable?" (errors/retryable? e))
      (println "Message:" (errors/format-error e))))
  
  ;; Test invalid model
  (try
    (llm/generate openai-backend "test" {:model "fake-model"})
    (catch Exception e
      (println "Error type:" (errors/error-type e))
      (pp/pprint (ex-data e))))
  
  ;; Test network timeout (if backend supports it)
  (try
    (llm/generate openai-backend "test" {:timeout-ms 1})
    (catch Exception e
      (println "Timeout error:" (errors/error-type e))))

  ;; ==========================================
  ;; BACKEND COMPARISON
  ;; ==========================================
  
  ;; Same prompt with different backends
  (def test-prompt "Write a one-sentence summary of machine learning")
  
  ;; OpenAI
  (println "OpenAI:")
  (println (llm/generate openai-backend test-prompt))
  
  ;; Anthropic (if available)
  (println "\nAnthropic:")
  (println (llm/generate anthropic-backend test-prompt))
  
  ;; OpenRouter (if available)
  (println "\nOpenRouter:")
  (println (llm/generate openrouter-backend test-prompt {:model "anthropic/claude-3-haiku"}))

  ;; ==========================================
  ;; PERFORMANCE TESTING
  ;; ==========================================
  
  ;; Time a simple generation
  (time (llm/generate openai-backend "Hello" {:model "gpt-4o-mini"}))
  
  ;; Concurrent requests
  (def futures
    (doall
     (for [i (range 3)]
       (future (llm/generate openai-backend (str "Count to " (inc i)))))))
  
  ;; Wait for all and collect results
  (doseq [f futures]
    (println @f))

  ;; ==========================================
  ;; CONFIGURATION TESTING
  ;; ==========================================
  
  ;; Test all configuration options
  (llm/generate openai-backend
                "Be creative"
                {:model "gpt-4o-mini"
                 :temperature 0.7
                 :max-tokens 100
                 :top-p 0.9
                 :frequency-penalty 0.1
                 :presence-penalty 0.1
                 :stop ["END" "\n\n"]
                 :seed 12345})
  
  ;; Test with custom headers (backend-specific)
  (llm/generate openai-backend
                "Hello"
                {:headers {"X-Custom-Header" "test-value"}})

  ;; ==========================================
  ;; UTILITY FUNCTIONS FOR TESTING
  ;; ==========================================
  
  ;; Helper to test streaming with timing
  (defn time-stream [backend prompt]
    (let [start (System/currentTimeMillis)
          chunks (llm/stream backend prompt)
          collected (atom [])]
      (loop []
        (when-let [chunk (<!! chunks)]
          (swap! collected conj chunk)
          (recur)))
      {:duration-ms (- (System/currentTimeMillis) start)
       :chunks @collected
       :total-chars (reduce + (map count @collected))}))
  
  ;; Test streaming performance
  (time-stream openai-backend "Write a short paragraph about AI")
  
  ;; Helper to validate schema results
  (defn test-schema-extraction [backend text schema]
    (try
      (let [result (llm/generate backend text {:schema schema})]
        {:success true :result result})
      (catch Exception e
        {:success false :error (ex-message e) :type (errors/error-type e)})))
  
  ;; Test schema validation
  (test-schema-extraction
   openai-backend
   "John is 25 years old and works as a teacher"
   person-schema)

  ;; End of comment block - all commands above are ready to eval in REPL
  )