# clj-llm

A flexible, async-first Clojure library for interacting with Large Language Models.

## Features

- **Streaming-first design**: Every request returns chunked responses for responsive UIs
- **Lazy sequences for chunks**: Process response chunks as they arrive with Clojure's sequence abstractions
- **First-class Malli schema support**: Structured outputs using Malli schemas
- **Function calling with schema validation**: Call functions directly from natural language with automatic schema validation
- **Backend agnostic**: Designed for multiple LLM backends
- **Babashka compatible**: Works in both Clojure and Babashka environments
- **Usage tracking**: Access token usage data for cost management
- **Conversation helpers**: Easily maintain conversational context

## Installation

```clojure
{:deps {co.poyo/clj-llm {:git/url "https://github.com/yourusername/clj-llm"
                         :sha "current-sha-goes-here"}}}
```

## Quick Start

```clojure
(require '[co.poyo.clj-llm.core :as llm]
         '[co.poyo.clj-llm.backends.openai :as openai])

;; Register the OpenAI backend
(openai/register-backend!)

;; Simple text completion
@(:text (llm/prompt :openai/gpt-4.1-nano "Hello, world!"))

;; Stream chunks as they arrive
(doseq [chunk (:chunks (llm/prompt :openai/gpt-4.1-nano "Count to 10"))]
  (print chunk)
  (flush))
```

## Structured Output with Malli

Use Malli schemas to get structured, validated responses:

```clojure
(def weather-schema
  [:and
   {:name "weather-fn"
    :description "gets the weather for a given location"}
   [:map
    [:location {:description "The city name"} :string]
    [:unit {:description "Temperature unit"}
     [:enum "celsius" "fahrenheit"]]]])

;; Get structured output
@(:structured-output
  (llm/prompt :openai/gpt-4.1-nano
              "What's the weather like in Paris?"
              {:schema weather-schema}))
;; => {:location "Paris", :unit "celsius"}
```

## Function Calling with Malli Instrumentation

Call functions directly using LLM with automatic schema validation:

```clojure
;; Define and instrument a function with Malli
(defn transfer-money [{:keys [from to amount]}]
  {:transaction-id (java.util.UUID/randomUUID)
   :details {:from from, :to to, :amount amount}
   :status "completed"})

(m/=>
 transfer-money
 [:->
  [:map [:from :string] [:to :string] [:amount :int]]
  [:map
   [:transaction-id :uuid]
   [:details [:map [:from :string] [:to :string] [:amount :int]]]
   [:status :string]]])

;; Call function with natural language
(llm/call-function-with-llm
 transfer-money
 :openai/gpt-4.1-nano
 "Transfer $50 from my savings account to my checking account")
```

## Conversations

Maintain conversational context:

```clojure
(def conv (llm/conversation :openai/gpt-4.1-nano))

;; First message
((:prompt conv) "Who was the first person on the moon?")

;; Follow-up question with automatic context handling
((:prompt conv) "When did this happen?")

;; Clear conversation history
((:clear conv))
```

## Usage Data

Track token usage:

```clojure
@(:usage (llm/prompt :openai/gpt-4.1-nano "Hello, world!"))
;; => {:prompt_tokens 7, :completion_tokens 9, :total_tokens 16}
```

## Babashka Compatibility

clj-llm works seamlessly in both Clojure and Babashka environments. The included `chat_repl.clj` script shows how to create a simple CLI chat interface:

```bash
$ bb chat_repl.clj openai/gpt-4.1-nano
```

## Extending with New Backends

Create your own backend implementation by implementing the `LLMBackend` protocol:

```clojure
(defrecord MyBackend []
  proto/LLMBackend
  (-prompt [this model-id prompt-str opts] ...)
  (-stream [this model-id prompt-str opts] ...)
  (-opts-schema [this model-id] ...)
  (-get-usage [this model-id metadata-atom] ...)
  (-get-structured-output [this model-id metadata-atom] ...)
  (-get-raw-json [this model-id metadata-atom] ...))

;; Register your backend
(reg/register-backend! :my-backend (->MyBackend))
```

## License

Distributed under the Eclipse Public License version 1.0.
