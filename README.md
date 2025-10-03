# clj-llm

Finally, an LLM library that doesn't get in your way.

Built for Clojure developers who want maximum flexibility without sacrificing simplicity. clj-llm is minimal opinionated glue that lets you work with any LLM provider using the same clean interface—from OpenAI to your local Ollama setup.

## Why clj-llm?

**You're in control.** No magic configuration, no hidden behavior. Everything is explicit data that you can inspect, transform, and compose with standard Clojure functions.

**Scales with complexity.** Start simple with one-liner text generation, then add structured output, streaming, conversations, and error handling exactly when you need them.

**Future-proof.** New provider features work without library updates because we stay out of your way.

```clojure
;; Same interface, any provider
(def openai (openai/backend {:api-key-env "OPENAI_API_KEY"}))
(def local (openai/backend {:api-base "http://localhost:11434/v1"}))
(def claude (anthropic/backend {:api-key-env "ANTHROPIC_API_KEY"}))

;; Your code doesn't change
(llm/generate any-provider "Explain quantum computing")
```

## Installation

Add to your `deps.edn`:

```clojure
{:deps {co.poyo/clj-llm {:git/url "https://github.com/poyo-ai/clj-llm"
                         :git/sha "..."}}}
```

## Quick Start

```clojure
(require '[co.poyo.clj-llm.core :as llm]
         '[co.poyo.clj-llm.backends.openai :as openai])

;; Create a backend (just data, no side effects)
(def ai (openai/backend {:api-key-env "OPENAI_API_KEY"}))

;; Generate text
(llm/generate ai "What is the meaning of life?")
;; => "The meaning of life is a profound philosophical question..."
```

## Core Philosophy: Just Data

Everything in clj-llm is plain data. Backends are maps. Options are maps. Responses are maps. This means you can:

- Compose with any Clojure function
- Serialize/deserialize configuration
- Transform responses with standard tools
- Debug by printing everything
- Test without mocking

**Explicit boundaries** keep things clear:
- `:llm/*` options control the library (schemas, message history)
- `:provider/opts` passes through directly to the provider

## Show Me: From Simple to Sophisticated

### Type-safe Data Extraction

```clojure
;; Define what you want with Malli
(def invoice-schema
  [:map
   [:invoice-number :string]
   [:total [:double {:min 0}]]
   [:items [:vector [:map [:name :string] [:price :double]]]]])

;; Extract it reliably
(llm/generate ai invoice-text {:llm/schema invoice-schema})
;; => {:invoice-number "INV-001" :total 150.0 :items [...]}
```

### Streaming That Actually Works

```clojure
;; Stream text as it arrives
(doseq [chunk (llm/stream ai "Write a long story")]
  (print chunk) (flush))

;; Or access the full response object
(let [response (llm/prompt ai "Analyze this data")]
  @(:text response)      ; Complete text when ready
  @(:usage response)     ; Token usage stats
  (:chunks response))    ; Stream of chunks
```

### Provider Flexibility

```clojure
;; OpenAI
(def openai (openai/backend {:api-key-env "OPENAI_API_KEY"}))

;; Local models (Ollama, LM Studio, etc)
(def local (openai/backend {:api-base "http://localhost:11434/v1"
                           :api-key "not-needed"}))

;; OpenRouter (access 100+ models)
(def router (openai/backend {:api-key-env "OPENROUTER_API_KEY"
                            :api-base "https://openrouter.ai/api/v1"}))

;; Switch providers without changing your code
(llm/generate any-of-them "Same interface everywhere")
```

## Real-world Examples

### Document Analysis Pipeline

```clojure
(def analysis-schema
  [:map
   [:key-points [:vector :string]]
   [:sentiment [:enum "positive" "negative" "neutral"]]
   [:action-items [:vector :string]]
   [:confidence [:double {:min 0 :max 1}]]])

(defn analyze-document [doc]
  (llm/generate ai doc {:llm/schema analysis-schema
                        :llm/system-prompt "You are a document analyst."}))
```

### Conversational AI with Memory

```clojure
(def conversation (atom [{:role :system :content "You are a helpful coding assistant"}]))

(defn chat! [message]
  (swap! conversation conj {:role :user :content message})
  (let [response (llm/generate ai nil {:llm/messages @conversation})]
    (swap! conversation conj {:role :assistant :content response})
    response))

(chat! "How do I reverse a list in Clojure?")
(chat! "What about in Python?") ; Remembers context
```

## Error Handling That Doesn't Suck

Comprehensive error categorization with helpful context:

```clojure
(require '[co.poyo.clj-llm.errors :as errors])

(try
  (llm/generate ai "Hello" {:model "invalid-model"})
  (catch Exception e
    (case (errors/error-type e)
      :llm/rate-limit    (println "Rate limited, retry in" (errors/retry-after e) "ms")
      :llm/network-error (println "Network issue, retrying...")
      :llm/invalid-key   (println "Check your API key")
      (throw e))))
```

## Advanced Features

**Cross-platform:** Same code works in Clojure and Babashka—develop with rich tooling, deploy lightweight.

**Composable:** Integrate with core.async, transducers, or any Clojure library without friction.

**Observable:** Built-in access to token usage, timing, and raw events for monitoring and debugging.

## Try It Now

```bash
# Interactive chat REPL
./scripts/chat_repl.clj gpt-4o-mini

# See structured output in action
./scripts/malli_schemas_example.clj

# Test streaming
./scripts/test_streaming.clj
```

## What's Next?

- Browse [examples.md](examples.md) for comprehensive code samples
- Check the `/scripts` directory for working examples
- Start with `llm/generate` for simple cases
- Add schemas when you need structured data
- Use streaming for long responses
- Access the full response object for advanced control

Built with ❤️ for Clojure developers who value simplicity and control.

---

*Questions? Issues? Check out our [docs](doc/) or open an issue.*