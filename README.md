# clj-llm

A flexible, async-first Clojure library for interacting with Large Language Models.

## Features

- **Streaming-first design**: Real-time chunked responses using core.async channels
- **Multiple output formats**: Access raw events, concatenated text, structured data, or tool calls
- **First-class Malli schema support**: Structured outputs with automatic schema validation
- **Function calling**: Call functions directly from natural language with schema validation
- **Backend agnostic**: Currently supports OpenAI and Anthropic, easily extensible
- **Babashka compatible**: Works in both Clojure and Babashka environments
- **Usage tracking**: Built-in token usage monitoring for cost management
- **Conversation helpers**: Maintain conversational context with automatic history management
- **File attachments**: Support for image attachments (OpenAI)

## Installation

#+end_srcclojure
{:deps {co.poyo/clj-llm {:git/url "https://github.com/yourusername/clj-llm"
                         :sha "current-sha-goes-here"}}}
#+begin_src

## Quick Start

#+end_srcclojure
(require '[co.poyo.clj-llm.core :as llm]
         '[co.poyo.clj-llm.backends.openai :as openai])

;; Register the OpenAI backend
(openai/register-backend!)

;; Simple text completion
@(:text (llm/prompt :openai/gpt-4o "Hello, world!"))

;; Stream chunks as they arrive
(let [response (llm/prompt :openai/gpt-4o "Count to 10")]
  (loop []
    (when-let [chunk (clojure.core.async/<!! (:chunks response))]
      (when (= :content (:type chunk))
        (print (:content chunk))
        (flush))
      (recur))))
#+begin_src

## Response Format

Every `prompt` call returns a map with multiple ways to access the response:

#+end_srcclojure
(let [response (llm/prompt :openai/gpt-4o "Hello!")]
  {:chunks        (:chunks response)        ; core.async channel of events
   :json          @(:json response)         ; raw event vector (delays until complete)
   :text          @(:text response)         ; concatenated content string
   :usage         @(:usage response)        ; token usage stats
   :tool-calls    @(:tool-calls response)   ; parsed tool calls
   :structured-output @(:structured-output response)}) ; first tool call args
#+begin_src

## Structured Output with Malli

Use Malli schemas to get structured, validated responses:

#+end_srcclojure
(def weather-schema
  [:map
   {:name "get_weather"
    :description "Get weather information for a location"}
   [:location {:description "The city name"} :string]
   [:unit {:description "Temperature unit"} [:enum "celsius" "fahrenheit"]]])

;; Get structured output
@(:structured-output
  (llm/prompt :openai/gpt-4o
              "What's the weather like in Paris in Celsius?"
              {:schema weather-schema
               :validate-output? true}))
;; => {:location "Paris", :unit "celsius"}
#+begin_src

## Function Calling with Malli Instrumentation

Call functions directly using LLM with automatic validation:

#+end_srcclojure
(require '[malli.core :as m])

;; Define and instrument a function
(defn transfer-money [{:keys [from to amount]}]
  {:transaction-id (java.util.UUID/randomUUID)
   :details {:from from, :to to, :amount amount}
   :status "completed"})

(m/=> transfer-money
  [:=> [:cat [:map
              [:from :string]
              [:to :string]
              [:amount :int]]]
       [:map
        [:transaction-id :uuid]
        [:details [:map [:from :string] [:to :string] [:amount :int]]]
        [:status :string]]])

;; Call function with natural language
(llm/call-function-with-llm
 transfer-money
 :openai/gpt-4o
 "Transfer $50 from my savings account to my checking account")
#+begin_src

## Conversations

Maintain conversational context automatically:

#+end_srcclojure
(def conv (llm/conversation :openai/gpt-4o))

;; First message
@(:text ((:prompt conv) "Who was the first person on the moon?"))

;; Follow-up question with automatic context
@(:text ((:prompt conv) "When did this happen?"))

;; Access conversation history
@(:history conv)

;; Clear conversation history
((:clear conv))
#+begin_src

## File Attachments

Attach images to your prompts (OpenAI):

#+end_srcclojure
(llm/prompt :openai/gpt-4o
            "What's in this image?"
            {:attachments [{:type :image
                           :path "/path/to/image.png"}]})
#+begin_src

## Usage Tracking

Monitor token consumption:

#+end_srcclojure
@(:usage (llm/prompt :openai/gpt-4o "Hello, world!"))
;; => {:prompt 10, :completion 5, :total 15}
#+begin_src

## Backend Configuration

### OpenAI

#+end_srcclojure
(require '[co.poyo.clj-llm.backends.openai :as openai])
(openai/register-backend!)

;; Use with API key from environment (OPENAI_API_KEY) or pass directly
(llm/prompt :openai/gpt-4o "Hello!" {:api-key "your-key-here"})
#+begin_src

### Anthropic

#+end_srcclojure
(require '[co.poyo.clj-llm.backends.anthropic :as anthropic])
(anthropic/register-backend!)

;; Use with API key from environment (ANTHROPIC_API_KEY)
(llm/prompt :anthropic/claude-3-sonnet "Hello!")
#+begin_src

## Babashka Compatibility

Works seamlessly in Babashka. Check the `scripts/` directory for examples:

#+end_srcbash
$ bb scripts/chat_repl.clj :openai/gpt-4o
#+begin_src

## Advanced Options

#+end_srcclojure
(llm/prompt :openai/gpt-4o "Write a story"
            {:temperature 0.8
             :max-tokens 500
             :top-p 0.9
             :frequency-penalty 0.1
             :presence-penalty 0.1
             :stop ["THE END"]
             :seed 42
             :history [{:role :system :content "You are a creative writer"}]})
#+begin_src

## Creating Custom Backends

Implement the `LLMBackend` protocol:

#+end_srcclojure
(require '[co.poyo.clj-llm.protocol :as proto]
         '[co.poyo.clj-llm.registry :as reg])

(defrecord MyBackend []
  proto/LLMBackend
  (-raw-stream [this model-id prompt-str opts]
    ;; Return {:channel async-channel-of-events}
    )
  (-opts-schema [this model-id]
    ;; Return Malli schema for backend-specific options
    ))

;; Register your backend
(reg/register-backend! :my-backend (->MyBackend))
#+begin_src

## Event Types

The streaming channel emits events with these types:

- `:content` - Text content chunks: `{:type :content :content "text"}`
- `:usage` - Token usage: `{:type :usage :prompt 10 :completion 5 :total 15}`
- `:tool-call-delta` - Tool call chunks: `{:type :tool-call-delta :index 0 :id "call_123" :name "function_name" :arguments "{partial"}`

## License

Distributed under the Eclipse Public License version 1.0.
