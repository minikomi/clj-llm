# clj-llm

An LLM library that doesn't get in your way.

Built for Clojure developers who want maximum flexibility without sacrificing simplicity. Minimal opinionated glue for any LLM provider — from OpenAI to your local Ollama setup.

## Why clj-llm?

**You're in control.** No magic configuration, no hidden behavior. Everything is explicit data.

**Composable.** Input-last design means `generate` threads naturally with `->>`. Config is just `assoc`/`merge`.

**Natural returns.** `generate` returns the value you want — a string for text, a parsed map for structured output, a keyed map for tool calls — not a wrapper you have to unwrap.

```clojure
;; Same interface, any provider
(def openai (openai/backend {:api-key-env "OPENAI_API_KEY"}))
(def local  (openai/backend {:api-base "http://localhost:11434/v1"}))
(def claude (anthropic/backend {:api-key-env "ANTHROPIC_API_KEY"}))

;; Put defaults on the provider
(def ai (assoc openai :defaults {:model "gpt-4o-mini"}))

;; Generate — returns a string
(llm/generate ai "Explain quantum computing")
;; => "Quantum computing uses quantum mechanical phenomena..."
```

## Installation

```clojure
{:deps {co.poyo/clj-llm {:git/url "https://github.com/poyo-ai/clj-llm"
                         :git/sha "..."}}}
```

## Quick Start

```clojure
(require '[co.poyo.clj-llm.core :as llm]
         '[co.poyo.clj-llm.backends.openai :as openai])

;; Provider = connection details
(def provider (openai/->openai))

;; Task = provider + configuration
(def ai (assoc provider :defaults {:model "gpt-4o-mini"}))

;; Generate text
(llm/generate ai "What is the meaning of life?")
;; => "The meaning of life is a profound philosophical question..."
```

## Core Design

### Input-last, threads with `->>`

The input (string or message history) is always the last argument:

```clojure
;; Pipeline — output flows naturally
(->> "Raw technical document with errors"
     (llm/generate ai {:system-prompt "Fix grammar and spelling"})
     (llm/generate ai {:system-prompt "Simplify for a general audience"})
     (llm/generate ai {:system-prompt "Translate to French"}))
```

### Natural returns

`generate` returns the value you actually want:

```clojure
;; Text → string
(llm/generate ai "hello")
;; => "Hello! How can I help?"

;; Schema → parsed map
(llm/generate ai {:schema person-schema} "Marie Curie was a 66yo physicist")
;; => {:name "Marie Curie" :age 66 :occupation "physicist"}

;; Tools → map with :tool-calls, :text, :message
(llm/generate ai {:tools [weather-tool]} "Weather in Tokyo?")
;; => {:tool-calls [{:id "call_..." :name "get_weather" :arguments {:city "Tokyo"}}]
;;     :message {:role :assistant :tool_calls [...]}}
```

### Providers are just maps

```clojure
;; Provider = just the connection
(def provider (openai/->openai))

;; Defaults = what you want it to do
(def ai (assoc provider :defaults {:model "gpt-4o-mini"}))

;; Layer more config with merge
(def extractor (update ai :defaults merge
                        {:system-prompt "Extract structured data"
                         :schema person-schema}))

;; Use it
(llm/generate extractor "Marie Curie was a 66yo physicist")
;; => {:name "Marie Curie" :age 66 :occupation "physicist"}

;; Override per-call when needed
(llm/generate extractor {:model "gpt-4o"} "Albert Einstein...")
```

## Structured Output

```clojure
(def invoice-schema
  [:map
   [:invoice-number :string]
   [:total [:double {:min 0}]]
   [:items [:vector [:map [:name :string] [:price :double]]]]])

(llm/generate ai {:schema invoice-schema} invoice-text)
;; => {:invoice-number "INV-001" :total 150.0 :items [...]}
```

## Streaming

```clojure
;; Print as it streams, get full text back
(llm/stream-print ai "Write a long story")

;; Channel for custom processing
(let [ch (llm/stream ai "Count to 10")]
  (loop []
    (when-let [chunk (<!! ch)]
      (print chunk) (flush)
      (recur))))
```

## Conversations

Message history is just a vector you pass as input:

```clojure
(def conversation
  (atom [{:role :system :content "You are a helpful coding assistant"}]))

(defn chat! [message]
  (swap! conversation conj {:role :user :content message})
  (let [response (llm/generate ai @conversation)]
    (swap! conversation conj {:role :assistant :content response})
    response))

(chat! "How do I reverse a list in Clojure?")
(chat! "What about in Python?")  ;; remembers context
```

## Tool Calling

```clojure
(def weather-tool
  [:map {:name "get_weather" :description "Get weather for a city"}
   [:city {:description "City name"} :string]])

;; Returns a map with :tool-calls, :text, :message
(let [result (llm/generate ai {:tools [weather-tool]} "Weather in Tokyo?")]
  ;; result => {:tool-calls [{:id "call_..." :name "get_weather" :arguments {:city "Tokyo"}}]
  ;;            :text nil
  ;;            :message {:role :assistant :tool_calls [...]}}

  ;; Build history and feed results back
  (let [{:keys [tool-calls message]} result
        results (mapv #(llm/tool-result (:id %) "Sunny, 22°C") tool-calls)
        history (into [{:role :user :content "Weather in Tokyo?"} message] results)]
    (llm/generate ai history)))
;; => "It's sunny and 22°C in Tokyo!"
```

### Agentic Loop

```clojure
(defn execute [{:keys [name arguments]}]
  (case name
    "get_weather" (str "Sunny, 22°C in " (:city arguments))))

(llm/run-agent ai {:tools [weather-tool]} execute "Weather in Tokyo?")
;; => {:text "It's sunny and 22°C in Tokyo!"
;;     :history [...]
;;     :steps [{:tool-calls [...] :tool-results [...]}]}
```

## Provider Flexibility

```clojure
;; OpenAI
(def openai (openai/->openai))

;; Local models (Ollama, LM Studio, etc)
(def local (openai/->openai {:api-base "http://localhost:11434/v1"
                             :api-key "not-needed"}))

;; OpenRouter (access 100+ models)
(def router (openai/->openai {:api-key-env "OPENROUTER_API_KEY"
                              :api-base "https://openrouter.ai/api/v1"}))

;; Same code, any provider
(def ai (assoc any-provider :defaults {:model "gpt-4o-mini"}))
(llm/generate ai "Same interface everywhere")
```

## Error Handling

```clojure
(require '[co.poyo.clj-llm.errors :as errors])

(try
  (llm/generate ai {:model "invalid-model"} "Hello")
  (catch Exception e
    (case (errors/error-type e)
      :llm/rate-limit    (println "Rate limited, retry in" (errors/retry-after e) "ms")
      :llm/network-error (println "Network issue, retrying...")
      :llm/invalid-key   (println "Check your API key")
      (throw e))))
```

## Full Response Access

When you need token usage or raw events, use `prompt` directly:

```clojure
(def resp (llm/prompt ai "Explain AI briefly"))

@resp              ;; block for text (IDeref)
@(:text resp)      ;; same
@(:usage resp)     ;; token counts
(:chunks resp)     ;; channel of text chunks
(:events resp)     ;; channel of raw events
```

## Try It Now

```bash
# Interactive chat REPL
./scripts/chat.clj gpt-4o-mini

# See structured output in action  
./scripts/generate.clj

# Test streaming
./scripts/streaming.clj

# Tool calling
./scripts/tools.clj
```

Built with ❤️ for Clojure developers who value simplicity and control.
