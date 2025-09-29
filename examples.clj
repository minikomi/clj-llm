(ns examples
  "Examples of using the new clj-llm API"
  (:require [co.poyo.clj-llm.core :as llm]
            [co.poyo.clj-llm.backends.openai :as openai]
            [clojure.core.async :refer [<!!]]))

(comment
  ;; Create providers with explicit configuration
  (def openai-provider
    (openai/->openai {:api-key-env "OPENAI_API_KEY"}))

  (def openrouter-provider
    (openai/->openai {:api-key-env "OPENROUTER_API_KEY"
                      :api-base "https://openrouter.ai/api/v1"}))

  (def local-provider
    (openai/->openai {:api-base "http://localhost:11434/v1" ;; Ollama
                      :defaults {:provider/opts {:model "llama2"}}}))

  ;; ──────────────────────────────────────────────────────────────
  ;; Simple text generation (blocking)
  ;; ──────────────────────────────────────────────────────────────

  ;; Basic usage - get text from response
  @(:text (llm/prompt openai-provider "What is 2+2?"))
  ;; => "2 + 2 equals 4."

  ;; With provider options
  @(:text (llm/prompt openai-provider
                      "Write a haiku about Clojure"
                      {:provider/opts {:temperature 0.8
                                       :model "gpt-4o"}})) ;; Override default model

  ;; With system prompt
  @(:text (llm/prompt openai-provider
                      "Explain recursion"
                      {:llm/system-prompt "You are a patient teacher. Use simple examples."}))

  ;; ──────────────────────────────────────────────────────────────
  ;; Structured output
  ;; ──────────────────────────────────────────────────────────────

  ;; Extract structured data using Malli schema
  (def person-schema
    [:map
     [:name :string]
     [:age pos-int?]
     [:occupation :string]])

  @(:structured (llm/prompt openai-provider
                            "Extract info: Marie Curie was a 66 year old physicist"
                            {:llm/schema person-schema}))
  ;; => {:name "Marie Curie", :age 66, :occupation "physicist"}

  ;; More complex schema
  (def recipe-schema
    [:map
     [:title :string]
     [:servings pos-int?]
     [:ingredients [:vector [:map
                             [:item :string]
                             [:amount :string]]]]
     [:steps [:vector :string]]])

  @(:structured (llm/prompt openai-provider
                            "Parse this recipe: Simple Pasta - Serves 2.
                             Ingredients: 200g pasta, 2 cloves garlic.
                             Steps: Boil water, cook pasta, add garlic."
                            {:llm/schema recipe-schema}))

  ;; ──────────────────────────────────────────────────────────────
  ;; Streaming responses
  ;; ──────────────────────────────────────────────────────────────

  ;; Get text chunks as they arrive
  (let [response (llm/prompt openai-provider "Tell me a story about a robot")
        chunks (:chunks response)]
    (loop []
      (when-let [chunk (<!! chunks)]
        (print chunk)
        (flush)
        (recur))))

  ;; Stream with options
  (let [response (llm/prompt openai-provider
                             "Write a long analysis"
                             {:provider/opts {:max_tokens 1000
                                              :temperature 0.7}})
        chunks (:chunks response)]
    ;; Process chunks...
    )

  ;; ──────────────────────────────────────────────────────────────
  ;; Raw events access
  ;; ──────────────────────────────────────────────────────────────

  ;; Get all events (content, usage, errors, etc)
  (let [response (llm/prompt openai-provider "Quick test")
        events (:events response)]
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

  (def response (llm/prompt openai-provider "Explain quantum computing"))

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
    (llm/prompt openai-provider
                "Extract: The temperature is 72F with 65% humidity"
                {:llm/schema [:map
                              [:temperature :int]
                              [:humidity :int]]}))

  @(:structured structured-resp)
  ;; => {:temperature 72, :humidity 65}

  ;; ──────────────────────────────────────────────────────────────
  ;; Conversations (use message history)
  ;; ──────────────────────────────────────────────────────────────

  (def messages
    [{:role :system :content "You are a helpful assistant"}
     {:role :user :content "My name is Alice"}
     {:role :assistant :content "Nice to meet you, Alice!"}
     {:role :user :content "What's my name?"}])

  @(:text (llm/prompt openai-provider nil {:llm/message-history messages}))
  ;; => "Your name is Alice."

  ;; Build conversation incrementally
  (def conversation (atom [{:role :system :content "You are a pirate"}]))

  (defn chat! [message]
    (swap! conversation conj {:role :user :content message})
    (let [response @(:text (llm/prompt openai-provider nil {:llm/message-history @conversation}))]
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
    @(:text (llm/prompt openai-provider "Hello" {:provider/opts {:model "invalid-model"}}))
    (catch Exception e
      (println "Error:" (ex-message e))))

  ;; With streaming, errors come through the events
  (let [response (llm/prompt openai-provider "Test" {:provider/opts {:model "bad-model"}})
        events (:events response)]
    (loop []
      (when-let [event (<!! events)]
        (when (= :error (:type event))
          (println "Stream error:" (:error event)))
        (recur)))))