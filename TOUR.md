# A Tour of clj-llm

This walks through the library piece by piece. Every example is real code.

## Summary

| Concept | How |
|---|---|
| Provider | `(openai/backend)` — a map |
| Config | `(assoc provider :defaults {...})` |
| Text generation | `(generate ai "prompt")` → string |
| With options | `(generate ai {:system-prompt "..."} "prompt")` |
| Structured output | `(generate ai {:schema s} "prompt")` → parsed data |
| Single tool call | `(generate ai {:tools [...]} "prompt")` → `{:text :tool-calls :tool-results}` |
| Agent loop | `(run-agent ai [#'tool-fn] "prompt")` → `{:text :steps :history}` |
| Streaming | `(stream-print ai "prompt")` or `(stream ai "prompt")` |
| Conversations | `(generate ai history-vector)` |
| Full access | `(request ai "prompt")` → Response record |

Everything is data. Providers are maps. Options are maps. History is a vector. Compose with the tools Clojure already gives you.

## 1. Connect to a provider

A provider is a map that knows how to talk to an LLM API.

```clojure
(require '[co.poyo.clj-llm.core :as llm]
         '[co.poyo.clj-llm.backends.openai :as openai])

;; OpenAI (reads OPENAI_API_KEY from env by default)
(def provider (openai/backend))

;; Or point at anything with an OpenAI-compatible API
(def ollama (openai/backend {:api-base "http://localhost:11434/v1"
                             :api-key "not-needed"}))

(def openrouter (openai/backend {:api-key-env "OPENROUTER_API_KEY"
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

### Common options

```clojure
(llm/generate ai {:model         "gpt-4o"          ;; override model
                  :system-prompt "Be concise."      ;; system message
                  :temperature   0.2                ;; lower = more deterministic
                  :max-tokens    500                ;; cap output length
                  :top-p         0.9}               ;; nucleus sampling
  "Explain quantum computing")
```

For provider-specific parameters not listed above, use `:provider-opts`:

```clojure
(llm/generate ai {:provider-opts {:frequency_penalty 0.5
                                  :presence_penalty 0.3}}
  "Write something creative")
```

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

## 9. Defining tools

Tools are plain functions with standard [Malli function schemas](https://github.com/metosin/malli/blob/master/docs/function-schemas.md). The `:malli/schema` metadata tells the LLM what the tool does and what arguments it takes:

```clojure
(defn get-weather
  {:malli/schema [:=> [:cat [:map {:name "get_weather"
                                   :description "Get current weather for a city"}
                             [:city {:description "City name"} :string]]]
                      :string]}
  [{:keys [city]}]
  (http/get (str "https://weather.api/" city)))
```

A tool is a regular Clojure function. Call it directly, test it, compose it:

```clojure
(get-weather {:city "Tokyo"})
;; => "Sunny, 22°C"
```

The schema lives in var metadata — standard Malli, nothing custom:

```clojure
(:malli/schema (meta #'get-weather))
;; => [:=> [:cat [:map {:name "get_weather" ...} ...]] :string]
```

All three standard Malli approaches work. You can also use `m/=>` to keep the schema separate:

```clojure
(defn get-weather [{:keys [city]}] (str "Sunny in " city))
(m/=> get-weather [:=> [:cat [:map {:name "get_weather"
                                    :description "Get weather"}
                              [:city :string]]]
                       :string])
```

Or `mx/defn` for inline schema hints:

```clojure
(require '[malli.experimental :as mx])

(mx/defn get-weather :- :string
  [args :- [:map {:name "get_weather" :description "Get weather"}
             [:city :string]]]
  (str "Sunny in " (:city args)))
```

## 10. Single-turn tool calls with `generate`

Pass `:tools` to `generate` for a single LLM call with tool access. The model decides whether to call tools. If it does, `generate` executes them and returns everything:

```clojure
(llm/generate ai {:tools [#'get-weather]} "What's the weather in Tokyo?")
;; => {:text nil
;;     :tool-calls [{:id "call_abc" :name "get_weather" :arguments {:city "Tokyo"}}]
;;     :tool-results ["Sunny, 22°C"]}
```

When tools are configured, `generate` always returns a map with three keys:

- `:text` — any text the model produced alongside tool calls (often `nil`)
- `:tool-calls` — vector of `{:id :name :arguments}` maps
- `:tool-results` — vector of results from executing each tool call

If the model decides no tools are needed, you get empty vectors:

```clojure
(llm/generate ai {:tools [#'get-weather]} "What is 2 + 2?")
;; => {:text "2 + 2 = 4" :tool-calls [] :tool-results []}
```

This is one LLM call. The model sees the tools, optionally calls them, and you get back data. No loops, no agents.

## 11. Multi-turn agents with `run-agent`

`run-agent` is the autonomous loop. It calls the LLM, executes tools, feeds results back, and repeats until the model stops calling tools.

```clojure
(llm/run-agent ai [#'get-weather #'search] "Plan a day trip to Tokyo")
;; => {:text    "Based on the weather and attractions..."
;;     :history [{:role :user ...} {:role :assistant ...} {:role :tool ...} ...]
;;     :steps   [{:tool-calls [...] :tool-results [...]}
;;              {:tool-calls [...] :tool-results [...]}]}
```

`:text` is the final answer. `:history` is the full conversation (reusable). `:steps` records each tool-calling iteration.

Options go between tools and input:

```clojure
(llm/run-agent ai [#'get-weather] {:max-steps 3} "Weather in Tokyo?")
```

### Controlling when the agent stops

By default, the loop stops when the model returns no tool calls — it's done.

Use `:stop-when` for explicit control. It's a predicate called after each LLM response, before tools are executed. Return truthy to stop:

```clojure
;; Stop when the model calls a "done" tool
(llm/run-agent ai [#'research #'done]
  {:stop-when (fn [{:keys [tool-calls]}]
                (some #(= "done" (:name %)) tool-calls))}
  "Research quantum computing")
;; => {:text "..."
;;     :tool-calls [{:name "done" :arguments {:summary "..."}}]  ;; pending, not executed
;;     :steps [...]
;;     :history [...]}
```

The predicate receives `{:tool-calls [...] :text "..."}`. When `:stop-when` fires, pending tool calls are returned in `:tool-calls` on the result — they were not executed.

`:max-steps` caps iterations as a safety net (default 10).

### Structured output after tool use

For structured output after tool use, compose with `generate`:

```clojure
(let [{:keys [history]} (llm/run-agent ai [#'lookup] "Look up user 123")]
  (llm/generate ai {:schema [:map [:name :string] [:status :string]]} history))
;; => {:name "Alice" :status "active"}
```

This keeps `run-agent` focused on the tool loop and `generate` as the single place structured extraction happens.

## 12. `generate` vs `run-agent`

| | `generate` | `run-agent` |
|---|---|---|
| LLM calls | Exactly one | Loop until done |
| Tools | Optional — pass `:tools` in opts | Required — pass as second arg |
| Executes tools | Yes, once | Yes, each iteration |
| Returns | String, structured data, or `{:text :tool-calls :tool-results}` | `{:text :history :steps}` |
| Stop control | N/A (one call) | `:stop-when` predicate, `:max-steps` |
| Use when | You know one call is enough | The model needs to iterate |

`generate` is the workhorse. `run-agent` is for when the model needs to drive.

## 13. Full response access

When you need token usage, raw SSE events, or fine-grained streaming control, use `request` directly:

```clojure
(def resp (llm/request ai "Explain AI briefly"))

@resp              ;; block for text (Response implements IDeref)
@(:text resp)      ;; same thing, explicit
@(:usage resp)     ;; {:prompt_tokens 12 :completion_tokens 45 ...}
(:chunks resp)     ;; core.async channel of text chunks
(:events resp)     ;; core.async channel of raw events
```

`request` returns a `Response` record. `generate` is built on top of it — it calls `request` and extracts the natural value.

For raw events only (without the full Response record), use `events`:

```clojure
(let [ch (llm/events ai "Count to 5")]
  (loop []
    (when-let [event (<!! ch)]
      (println (:type event) event)
      (when-not (= :done (:type event))
        (recur)))))
;; :content {:type :content :content "1"}
;; :content {:type :content :content ", 2"}
;; ...
;; :usage {:type :usage :prompt-tokens 10 :completion-tokens 20 ...}
;; :done {:type :done}
```

## 14. Error handling

Errors are `ex-info` exceptions with an `:error-type` keyword in `ex-data` for programmatic dispatch:

```clojure
(require '[co.poyo.clj-llm.errors :as errors])

(try
  (llm/generate ai {:model "nonexistent-model"} "hello")
  (catch Exception e
    (case (errors/error-type e)
      :llm/rate-limit    (println "Rate limited, retry in" (errors/retry-after e) "ms")
      :llm/network-error (println "Network issue")
      :llm/invalid-key   (println "Bad API key")
      :llm/server-error  (println "Server error, try again")
      :llm/invalid-request (println "Bad request:" (ex-message e))
      (throw e))))
```

Error types:

| Keyword | Meaning |
|---|---|
| `:llm/rate-limit` | 429 — too many requests |
| `:llm/invalid-key` | 401/403 — authentication failed |
| `:llm/invalid-request` | 400/404/422 — bad input |
| `:llm/server-error` | 500/502/503/504 — provider issue |
| `:llm/unknown` | Other errors |

The library does not do automatic retries. Use your own retry logic or a library like `again`.

## 15. Multiple providers

The same code works with any provider. Only the connection changes.

```clojure
(require '[co.poyo.clj-llm.backends.anthropic :as anthropic])

(def openai-ai  (assoc (openai/backend)     :defaults {:model "gpt-4o-mini"}))
(def claude-ai  (assoc (anthropic/backend) :defaults {:model "claude-sonnet-4-20250514"}))
(def local-ai   (assoc (openai/backend {:api-base "http://localhost:11434/v1"
                                        :api-key "x"})
                       :defaults {:model "llama3"}))

;; Same call, any provider
(llm/generate openai-ai "Explain monads in one sentence.")
(llm/generate claude-ai "Explain monads in one sentence.")
(llm/generate local-ai  "Explain monads in one sentence.")
```
