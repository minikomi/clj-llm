(ns examples
  "Examples of using the new clj-llm API"
  (:require [co.poyo.clj-llm.core :as llm]
            [co.poyo.clj-llm.backends.openai :as openai]
            [clojure.core.async :refer [<!!]]))

(comment
  ;; Create backends with explicit configuration
  (def openai-backend
    (openai/backend {:api-key-env "OPENAI_API_KEY"}))

  (def openrouter-backend
    (openai/openrouter {:api-key-env "OPENROUTER_API_KEY"}))

  (def local-backend
    (openai/local {:api-base "http://localhost:11434/v1" ;; Ollama
                   :default-model "llama2"}))

  ;; ──────────────────────────────────────────────────────────────
  ;; Simple text generation (blocking)
  ;; ──────────────────────────────────────────────────────────────

  ;; Basic usage - returns text directly
  (llm/generate openai-backend "What is 2+2?")
  ;; => "2 + 2 equals 4."

  ;; With options
  (llm/generate openai-backend
                "Write a haiku about Clojure"
                {:temperature 0.8
                 :model "gpt-4o"}) ;; Override default model

  ;; With system prompt
  (llm/generate openai-backend
                "Explain recursion"
                {:system-prompt "You are a patient teacher. Use simple examples."})

  ;; ──────────────────────────────────────────────────────────────
  ;; Structured output
  ;; ──────────────────────────────────────────────────────────────

  ;; Extract structured data using Malli schema
  (def person-schema
    [:map
     [:name :string]
     [:age pos-int?]
     [:occupation :string]])

  (llm/generate openai-backend
                "Extract info: Marie Curie was a 66 year old physicist"
                {:schema person-schema})
  ;; => {:name "Marie Curie", :age 66, :occupation "physicist"}

  ;; More complex schema
  (def recipe-schema
    [:map
     [:title :string]
     [:servings pos-int?]
     [:ingredients [:vector [:map]
                    [:item :string]
                    [:amount :string]]]
     [:steps [:vector :string]]])

  (llm/generate openai-backend
                "Parse this recipe: Simple Pasta - Serves 2. 
                 Ingredients: 200g pasta, 2 cloves garlic.
                 Steps: Boil water, cook pasta, add garlic."
                {:schema recipe-schema})

  ;; ──────────────────────────────────────────────────────────────
  ;; Streaming responses
  ;; ──────────────────────────────────────────────────────────────

  ;; Get text chunks as they arrive
  (let [chunks (llm/stream openai-backend "Tell me a story about a robot")]
    (loop []
      (when-let [chunk (<!! chunks)]
        (print chunk)
        (flush)
        (recur))))

  ;; Stream with options
  (let [chunks (llm/stream openai-backend
                           "Write a long analysis"
                           {:max-tokens 1000
                            :temperature 0.7})])
    ;; Process chunks...

;; ──────────────────────────────────────────────────────────────
  ;; Raw events access
  ;; ──────────────────────────────────────────────────────────────

  ;; Get all events (content, usage, errors, etc)
  (let [events (llm/events openai-backend "Quick test")]
    (loop []
      (when-let [event (<!! events)]
        (case (:type event)
          :content (print (:content event))
          :usage (println "\nTokens used:" (:total-tokens event))
          :error (println "Error:" (:error event))
          :done (println "\nDone!"))
        (when-not (= :done (:type event))
          (recur)))))

  ;; ──────────────────────────────────────────────────────────────
  ;; Full response object (for advanced use cases)
  ;; ──────────────────────────────────────────────────────────────

  (def response (llm/prompt openai-backend "Explain quantum computing"))

  ;; Response implements IDeref for convenience
  @response
  ;; => "Quantum computing is..."

  ;; Access individual components
  @(:text response) ;; Full text (Promise)
  @(:usage response) ;; Token usage stats (Promise)
  (:chunks response) ;; Text chunks (Channel)
  (:events response) ;; Raw events (Channel)

  ;; With structured output
  (def structured-resp
    (llm/prompt openai-backend
                "Extract: The temperature is 72F with 65% humidity"
                {:schema [:map]
                 [:temperature :int]
                 [:humidity :int]}))

  @(:structured structured-resp)
  ;; => {:temperature 72, :humidity 65}

  ;; ──────────────────────────────────────────────────────────────
  ;; Conversations (just use messages)
  ;; ──────────────────────────────────────────────────────────────

  (def messages
    [{:role :system :content "You are a helpful assistant"}
     {:role :user :content "My name is Alice"}
     {:role :assistant :content "Nice to meet you, Alice!"}
     {:role :user :content "What's my name?"}])

  (llm/generate openai-backend nil {:messages messages})
  ;; => "Your name is Alice."

  ;; Build conversation incrementally
  (def conversation (atom [{:role :system :content "You are a pirate"}]))

  (defn chat! [message]
    (swap! conversation conj {:role :user :content message})
    (let [response (llm/generate openai-backend nil {:messages @conversation})]
      (swap! conversation conj {:role :assistant :content response})
      response))

  (chat! "Hello")
  ;; => "Ahoy there, matey!"

  (chat! "What's your favorite treasure?")
  ;; => "Arr, me favorite treasure be a fine chest o' gold doubloons!"

  ;; ──────────────────────────────────────────────────────────────
  ;; Error handling
  ;; ──────────────────────────────────────────────────────────────

  (try
    (llm/generate openai-backend "Hello" {:model "invalid-model"})
    (catch Exception e
      (println "Error:" (ex-message e))))

  ;; With streaming, errors come through the events
  (let [events (llm/events openai-backend "Test" {:model "bad-model"})]
    (loop []
      (when-let [event (<!! events)]
        (when (= :error (:type event))
          (println "Stream error:" (:error event)))
        (recur)))))