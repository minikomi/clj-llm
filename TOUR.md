# A Tour of clj-llm

This walks through the library piece by piece. Every example is real code.

## 1. Connect to a provider

A provider is a map that knows how to talk to an LLM API.

```clojure
(require '[co.poyo.clj-llm.core :as llm]
         '[co.poyo.clj-llm.backends.openai :as openai])

;; OpenAI (reads OPENAI_API_KEY from env by default)
(def provider (openai/->openai))

;; Or point at anything with an OpenAI-compatible API
(def ollama (openai/->openai {:api-base "http://localhost:11434/v1"
                              :api-key "not-needed"}))

(def openrouter (openai/->openai {:api-key-env "OPENROUTER_API_KEY"
                                  :api-base "https://openrouter.ai/api/v1"}))
```

A provider is just a map. You can print it, store it, pass it around.

## 2. Set defaults

Put your model and any other config on the `:defaults` key. It's just `assoc`.

```clojure
(def ai (assoc provider :defaults {:model "gpt-4o-mini"}))
```

That's it. `ai` is still a map. You can layer more config later:

```clojure
(def careful-ai (update ai :defaults merge {:model "gpt-4o"}))
```

## 3. Generate text

`generate` calls the LLM and returns the result. The input — a string or a message history vector — is always the **last** argument.

```clojure
(llm/generate ai "What is the capital of France?")
;; => "The capital of France is Paris."
```

Pass options as a map before the input:

```clojure
(llm/generate ai {:system-prompt "Answer in one word."} "What is the capital of France?")
;; => "Paris."
```

Per-call options merge on top of `:defaults`. Per-call wins.

## 4. Threading

Because input is last, `generate` threads naturally with `->>`. The output of one call (a string) becomes the input of the next.

```clojure
(->> "The mitochondria is the powerhouse of the cell. It make ATP."
     (llm/generate ai {:system-prompt "Fix grammar and spelling."})
     (llm/generate ai {:system-prompt "Translate to French."}))
;; => "La mitochondrie est la centrale énergétique de la cellule. Elle produit de l'ATP."
```

No special pipeline abstraction. Just Clojure.

## 5. Structured output

Pass a `:schema` (a Malli schema) and `generate` returns parsed, validated data instead of a string.

```clojure
(llm/generate ai
  {:schema [:map
            [:name :string]
            [:age :int]
            [:occupation :string]]}
  "Marie Curie was a 66 year old physicist")
;; => {:name "Marie Curie" :age 66 :occupation "physicist"}
```

The return value is the data. Not `{:structured {:name ...}}`. Just the map.

Schemas can be as complex as Malli allows:

```clojure
(def company-schema
  [:map
   [:name :string]
   [:founded :int]
   [:employees [:vector [:map
                         [:name :string]
                         [:role :string]
                         [:salary :int]]]]
   [:locations [:vector :string]]])

(llm/generate ai {:schema company-schema}
  "TechCorp founded 2010. Alice is CEO at $200k, Bob is Engineer at $120k. Offices in NYC and SF.")
;; => {:name "TechCorp"
;;     :founded 2010
;;     :employees [{:name "Alice" :role "CEO" :salary 200000}
;;                 {:name "Bob" :role "Engineer" :salary 120000}]
;;     :locations ["NYC" "SF"]}
```

## 6. Reusable configurations

Since the provider is a map, you build reusable "tasks" with standard map operations:

```clojure
(def extractor
  (update ai :defaults merge
    {:system-prompt "Extract structured data from the input."
     :schema [:map [:name :string] [:age :int] [:occupation :string]]}))

(llm/generate extractor "Marie Curie was a 66 year old physicist")
;; => {:name "Marie Curie" :age 66 :occupation "physicist"}

(llm/generate extractor "Albert Einstein was a 76 year old theoretical physicist")
;; => {:name "Albert Einstein" :age 76 :occupation "theoretical physicist"}

;; Override per-call when needed
(llm/generate extractor {:model "gpt-4o"} "some tricky input")
```

No special `task` or `chain` function. It's `assoc` and `merge`.

## 7. Streaming

`stream-print` prints chunks to stdout as they arrive and returns the full text:

```clojure
(llm/stream-print ai "Write a haiku about Clojure")
;; prints: Data flows like water / Parentheses embrace all / REPL sparks joy
;; => "Data flows like water\nParentheses embrace all\nREPL sparks joy"
```

`stream` returns a `core.async` channel of text chunks for custom processing:

```clojure
(require '[clojure.core.async :refer [<!!]])

(let [ch (llm/stream ai "Count from 1 to 5")]
  (loop []
    (when-let [chunk (<!! ch)]
      (print chunk)
      (flush)
      (recur))))
```

Both take opts in the same position:

```clojure
(llm/stream-print ai {:system-prompt "Respond in ALL CAPS"} "Say hello")
```

## 8. Conversations

Message history is a vector. You pass it as the input (last arg). There's no conversation object — you own the data.

```clojure
(def history
  [{:role :system :content "You are a helpful coding assistant."}
   {:role :user :content "How do I reverse a list in Clojure?"}
   {:role :assistant :content "Use (reverse coll) or (into () coll)."}
   {:role :user :content "What about in Python?"}])

(llm/generate ai history)
;; => "In Python, use reversed(lst) or lst[::-1]."
```

A simple chat loop:

```clojure
(def conversation (atom []))

(defn chat! [message]
  (swap! conversation conj {:role :user :content message})
  (let [response (llm/generate ai @conversation)]
    (swap! conversation conj {:role :assistant :content response})
    response))

(chat! "What's the tallest mountain?")
;; => "Mount Everest, at 8,849 meters."

(chat! "How about the second tallest?")
;; => "K2, at 8,611 meters."
```

## 9. Tool calling

`generate` is a pure value function — it doesn't do tool calls. When getting a value requires tool calls, use `run-agent`.

Define tools as Malli schemas with `:name` and `:description` metadata:

```clojure
(def get-weather
  [:map {:name "get_weather" :description "Get current weather for a city"}
   [:city {:description "City name"} :string]])
```

Write an executor — a function that takes a tool call and returns a result:

```clojure
(defn execute [{:keys [name arguments]}]
  (case name
    "get_weather" (str "Sunny, 22°C in " (:city arguments))))
```

`run-agent` handles the loop: call the model, execute tools, feed results back, repeat until the model produces a final text response.

```clojure
(llm/run-agent ai {:tools [get-weather] :execute execute} "Weather in Tokyo?")
;; => {:text    "It's sunny and 22°C in Tokyo!"
;;     :history [{:role :user ...} {:role :assistant ...} {:role :tool ...} ...]
;;     :steps   [{:tool-calls [...] :tool-results [...]}]}
```

`:text` is the final answer. `:history` is the full conversation (reusable). `:steps` records each tool-calling iteration.

Limit iterations with `:max-steps`:

```clojure
(llm/run-agent ai {:tools [get-weather] :execute execute :max-steps 3} "Weather in Tokyo?")
```

If your agent should return structured data, pass `:schema`:

```clojure
(llm/run-agent ai
  {:tools [lookup-tool] :execute execute
   :schema [:map [:name :string] [:status :string]]}
  "Look up user 123")
;; => {:text {:name "Alice" :status "active"} :history [...] :steps [...]}
```

## 10. Why `generate` doesn't do tools

Tool calling is fundamentally different from generation. When a model requests a tool call, it hasn't produced a value yet — it's mid-computation. Having `generate` return tool-call payloads would mean the return type depends on what the model decides to do, forcing callers to branch on the result type whenever tools are configured.

Instead:

- **`generate`** — single request, single value back. Always a string or structured data.
- **`run-agent`** — loop until done. Handles tool calls internally, returns the final value.

The name tells you the execution model.

## 11. Full response access

When you need token usage, raw SSE events, or fine-grained streaming control, use `prompt` directly:

```clojure
(def resp (llm/prompt ai "Explain AI briefly"))

@resp              ;; block for text (Response implements IDeref)
@(:text resp)      ;; same thing, explicit
@(:usage resp)     ;; {:prompt_tokens 12 :completion_tokens 45 ...}
(:chunks resp)     ;; core.async channel of text chunks
(:events resp)     ;; core.async channel of raw events
```

`prompt` returns a `Response` record. `generate` is built on top of it — it calls `prompt` and extracts the natural value.

## 12. Error handling

```clojure
(require '[co.poyo.clj-llm.errors :as errors])

(try
  (llm/generate ai {:model "nonexistent-model"} "hello")
  (catch Exception e
    (case (errors/error-type e)
      :llm/rate-limit    (println "Rate limited, retry in" (errors/retry-after e) "ms")
      :llm/network-error (println "Network issue")
      :llm/invalid-key   (println "Bad API key")
      (throw e))))
```

## 13. Multiple providers

The same code works with any provider. Only the connection changes.

```clojure
(require '[co.poyo.clj-llm.backends.anthropic :as anthropic])

(def openai-ai  (assoc (openai/->openai)     :defaults {:model "gpt-4o-mini"}))
(def claude-ai  (assoc (anthropic/->anthropic) :defaults {:model "claude-sonnet-4-20250514"}))
(def local-ai   (assoc (openai/->openai {:api-base "http://localhost:11434/v1"
                                         :api-key "x"})
                       :defaults {:model "llama3"}))

;; Same call, any provider
(llm/generate openai-ai "Explain monads in one sentence.")
(llm/generate claude-ai "Explain monads in one sentence.")
(llm/generate local-ai  "Explain monads in one sentence.")
```

## Summary

| Concept | How |
|---|---|
| Provider | `(openai/->openai)` — a map |
| Config | `(assoc provider :defaults {...})` |
| Text generation | `(generate ai "prompt")` → string |
| With options | `(generate ai {:system-prompt "..."} "prompt")` |
| Structured output | `(generate ai {:schema s} "prompt")` → parsed data |
| Tool calling | `(run-agent ai {:tools t :execute f} "prompt")` → `{:text ... :steps ...}` |
| Streaming | `(stream-print ai "prompt")` or `(stream ai "prompt")` |
| Conversations | `(generate ai history-vector)` |
| Agent loop | `(run-agent ai {:tools t :execute exec-fn} "prompt")` |
| Full access | `(prompt ai "prompt")` → Response record |

Everything is data. Providers are maps. Options are maps. History is a vector. Compose with the tools Clojure already gives you.
