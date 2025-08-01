# clj-llm

A clean, simple Clojure/Babashka library for interacting with Large Language Models.

## Features

- **Simple API** - Just `generate` for text, `stream` for streaming
- **Multiple Providers** - OpenAI, OpenRouter, Together.ai, local models
- **Structured Output** - Extract data with Malli schemas  
- **Streaming Support** - Real-time text generation with core.async
- **Cross-platform** - Works with both Clojure and Babashka
- **Explicit Configuration** - No magic, just data

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

;; Create a backend
(def ai (openai/backend {:api-key-env "OPENAI_API_KEY"}))

;; Generate text
(llm/generate ai "What is the meaning of life?")
;; => "The meaning of life is a profound philosophical question..."

;; Stream responses
(let [chunks (llm/stream ai "Tell me a story")]
  (doseq [chunk (repeatedly #(clojure.core.async/<!! chunks))]
    (when chunk (print chunk) (flush))))
```

### Try the Examples

```bash
# Interactive chat REPL
./scripts/chat_repl.clj gpt-4o-mini

# Run simple examples
./scripts/simple_example.clj

# See error handling
./scripts/error_handling.clj

# Test streaming
./scripts/test_streaming.clj
```

## API Overview

### Core Functions

- **`generate`** - Simple blocking text generation or structured data extraction
- **`stream`** - Get text chunks as they arrive
- **`events`** - Access raw events (content, usage, errors, done)
- **`prompt`** - Full response object for advanced use cases

### Creating Backends

```clojure
;; OpenAI
(def openai (openai/backend {:api-key-env "OPENAI_API_KEY"}))

;; OpenRouter (access to many models)
(def router (openai/backend {:api-key-env "OPENROUTER_API_KEY"
                            :api-base "https://openrouter.ai/api/v1"
                            :default-model "openai/gpt-4o-mini"}))

;; Together.ai
(def together (openai/backend {:api-key-env "TOGETHER_API_KEY"
                              :api-base "https://api.together.xyz/v1"
                              :default-model "meta-llama/Llama-3-70b-chat-hf"}))

;; Local models (Ollama, LM Studio, etc)
(def local (openai/backend {:api-base "http://localhost:11434/v1"
                           :api-key "not-needed"
                           :default-model "llama2"}))
```

## Examples

### Basic Text Generation

```clojure
;; Simple prompt
(llm/generate ai "Explain quantum computing in simple terms")

;; With options
(llm/generate ai "Write a poem" {:temperature 0.9
                                 :model "gpt-4o"})

;; With system prompt
(llm/generate ai "Explain recursion" 
              {:system-prompt "You are a patient teacher"})
```

### Structured Output

Extract structured data using Malli schemas:

```clojure
(def person-schema
  [:map
   [:name :string]
   [:age pos-int?]
   [:occupation :string]])

(llm/generate ai 
              "Extract: Marie Curie, 66 year old physicist"
              {:schema person-schema})
;; => {:name "Marie Curie" :age 66 :occupation "physicist"}
```

### Streaming

```clojure
(require '[clojure.core.async :refer [<!!]])

(let [chunks (llm/stream ai "Write a long story")]
  (loop []
    (when-let [chunk (<!! chunks)]
      (print chunk)
      (flush)
      (recur))))
```

### Conversations

Just use messages:

```clojure
(def messages
  [{:role :system :content "You are a helpful assistant"}
   {:role :user :content "My name is Alice"}  
   {:role :assistant :content "Nice to meet you, Alice!"}
   {:role :user :content "What's my name?"}])

(llm/generate ai nil {:messages messages})
;; => "Your name is Alice."
```

### Advanced Usage

The `prompt` function returns a rich response object:

```clojure
(def resp (llm/prompt ai "Explain AI"))

;; Deref for convenience
@resp ;; => "AI is..."

;; Or access components
@(:text resp)       ;; Full text (Promise)
@(:usage resp)      ;; Token usage info
(:chunks resp)      ;; Text chunks channel
(:events resp)      ;; Raw events channel
@(:structured resp) ;; Structured data if schema provided
```

## Configuration Options

```clojure
{:model "gpt-4o"           ;; Model to use
 :temperature 0.7          ;; Randomness (0.0-2.0)  
 :max-tokens 1000          ;; Max response length
 :system-prompt "..."      ;; System message
 :messages [...]           ;; Full conversation
 :schema [:map ...]        ;; Malli schema for structured output
 :stop ["\\n\\n" "END"]    ;; Stop sequences
 :seed 12345}              ;; For reproducibility
```

## Error Handling

clj-llm provides comprehensive error handling with categorized errors and helpful context.

### Error Categories

- **Network errors** - Connection issues, timeouts (retryable)
- **Provider errors** - Rate limits, invalid API keys, quota exceeded
- **Validation errors** - Invalid requests, schema validation failures
- **Internal errors** - Parsing failures, unexpected errors

### Basic Error Handling

```clojure
(require '[co.poyo.clj-llm.errors :as errors])

;; Synchronous - catch and inspect errors
(try
  (llm/generate ai "Hello" {:model "invalid-model"})
  (catch Exception e
    (println "Error:" (errors/format-error e))
    (println "Type:" (errors/error-type e))
    (println "Retryable:" (errors/retryable? e))))

;; Streaming - errors flow through channels
(let [chunks (llm/stream ai "Test")]
  (loop []
    (when-let [chunk (<!! chunks)]
      (if (instance? Throwable chunk)
        (println "Error:" (errors/format-error chunk))
        (print chunk))
      (recur))))
```

### Retry Logic

```clojure
;; Implement retry with exponential backoff
(defn retry-with-backoff [f max-retries]
  (loop [attempt 1]
    (try
      (f)
      (catch Exception e
        (if (and (errors/retryable? e) (<= attempt max-retries))
          (do
            (Thread/sleep (* 1000 attempt))
            (recur (inc attempt)))
          (throw e))))))

;; Use with LLM calls
(retry-with-backoff 
  #(llm/generate ai "Hello world")
  3)
```

### Error Types

```clojure
;; Check specific error types
(catch Exception e
  (case (errors/error-type e)
    :llm/rate-limit
    (let [retry-after (errors/extract-retry-after e)]
      (println "Rate limited, retry after" retry-after "ms"))
    
    :llm/invalid-api-key
    (println "Check your API key configuration")
    
    :llm/network-error
    (println "Network issue, please retry")
    
    ;; Default
    (throw e)))
```

## Project Structure

```
clj-llm/
├── src/co/poyo/clj_llm/
│   ├── core.clj              # Main API implementation
│   ├── protocol.clj          # LLMProvider protocol
│   ├── backends/
│   │   ├── openai.clj        # OpenAI-compatible backend
│   │   └── anthropic.clj     # Anthropic backend (placeholder)
│   ├── errors.clj            # Error handling utilities
│   ├── schema.clj            # Malli to JSON schema conversion
│   ├── net.cljc              # Cross-platform HTTP client
│   └── sse.clj               # Server-Sent Events parsing
├── test/                     # Test files
├── scripts/                  # Example scripts
├── examples/                 # Usage examples
└── doc/                      # Additional documentation
```

## Design Philosophy

1. **Simple by default** - Common cases are easy
2. **Explicit configuration** - Backends are just data
3. **Incremental complexity** - Simple API scales to advanced usage
4. **Cross-platform** - Same code works in Clojure and Babashka
5. **Data-oriented** - Configuration and responses are plain data

## Roadmap

- [ ] More provider implementations (Anthropic, Google, Cohere)
- [ ] Middleware system for logging, retry, caching
- [ ] Token counting utilities
- [ ] Rate limiting and backpressure
- [ ] Response validation
- [ ] Tool/function calling support

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup and guidelines.

## License

Copyright © 2024 Poyo AI. Licensed under the MIT License.