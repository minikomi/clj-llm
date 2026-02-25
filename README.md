# clj-llm

An LLM library that doesn't get in your way.

Built for Clojure developers who want maximum flexibility without sacrificing simplicity. Minimal opinionated glue for any LLM provider — from OpenAI to your local Ollama setup.

## Summary

| Concept | How |
|---|---|
| Provider | `(openai/backend)` — a map |
| Config | `(assoc provider :defaults {...})` |
| Text generation | `(generate ai "prompt")` → string |
| With options | `(generate ai {:system-prompt "..."} "prompt")` |
| Structured output | `(generate ai {:schema s} "prompt")` → parsed data |
| Tool calling | `(run-agent ai [#'tool-fn] "prompt")` → `{:text ... :steps ...}` |
| Tool hooks | `:on-tool-calls`, `:on-tool-result` — observe the agent in real time |
| Streaming | `(stream-print ai "prompt")` or `(stream ai "prompt")` |
| Conversations | `(generate ai history-vector)` |
| Full access | `(request ai "prompt")` → Response record |

## Why clj-llm?

**You're in control.** No magic configuration, no hidden behavior. Everything is explicit data.

**Composable.** Input-last design means `generate` threads naturally with `->>`. Config is just `assoc`/`merge`.

**Natural returns.** `generate` returns the value you want — a string for text, a parsed map for structured output — not a wrapper you have to unwrap.

```clojure
;; Same interface, any provider
(def openai (openai/backend))  ;; reads OPENAI_API_KEY env var by default
(def local  (openai/backend {:api-base "http://localhost:11434/v1"}))
(def claude (anthropic/backend))  ;; reads ANTHROPIC_API_KEY env var by default

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
(def provider (openai/backend))

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

```

### Providers are just maps

```clojure
;; Provider = just the connection
(def provider (openai/backend))

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

### Common options

```clojure
(llm/generate ai {:model         "gpt-4o"
                  :system-prompt "Be concise."
                  :temperature   0.2
                  :max-tokens    500
                  :top-p         0.9}
  "Explain quantum computing")

;; For provider-specific params not listed above:
(llm/generate ai {:provider-opts {:frequency_penalty 0.5}} "hello")
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

Tools are plain functions with standard [Malli function schemas](https://github.com/metosin/malli/blob/master/docs/function-schemas.md):

```clojure
(require '[cheshire.core :as json])

;; Geocode: city name → coordinates (free Open-Meteo API, no key needed)
(defn geocode
  {:malli/schema [:=> [:cat [:map {:name "geocode"
                                   :description "Look up latitude and longitude for a city"}
                             [:city {:description "City name"} :string]]]
                      :string]}
  [{:keys [city]}]
  (let [geo (-> (slurp (str "https://geocoding-api.open-meteo.com/v1/search?name="
                            (java.net.URLEncoder/encode city "UTF-8") "&count=1"))
                (json/parse-string true))
        loc (first (:results geo))]
    (json/generate-string (select-keys loc [:name :country :latitude :longitude]))))

;; Weather: coordinates → current conditions
(defn get-weather
  {:malli/schema [:=> [:cat [:map {:name "get_weather"
                                   :description "Get current weather at a location. Call geocode first to get coordinates."}
                             [:latitude {:description "Latitude"} :double]
                             [:longitude {:description "Longitude"} :double]]]
                      :string]}
  [{:keys [latitude longitude]}]
  (let [wx (-> (slurp (str "https://api.open-meteo.com/v1/jma?latitude=" latitude
                           "&longitude=" longitude
                           "&current=temperature_2m,weather_code,wind_speed_10m"
                           "&timezone=auto"))
               (json/parse-string true))]
    (let [c (:current wx)]
      (str (:temperature_2m c) "°C, wind " (:wind_speed_10m c) " km/h"))))

;; They're regular functions — call them, test them, compose them
(geocode {:city "Tokyo"})
;; => "{\"name\":\"Tokyo\",\"country\":\"Japan\",\"latitude\":35.6895,\"longitude\":139.69171}"

(get-weather {:latitude 35.6895 :longitude 139.6917})
;; => "20.1°C, wind 7.6 km/h"
```

`run-agent` reads `:malli/schema` from var metadata, calls the functions when the model invokes them, and chains multi-step tool use automatically:

```clojure
(llm/run-agent ai [#'geocode #'get-weather] "Weather in Tokyo?")
;; => {:text "It's currently 20.1°C in Tokyo with light wind."
;;     :history [...]
;;     :steps [{:tool-calls [...] :tool-results [...]} ...]}
```

All three standard Malli approaches work — `{:malli/schema ...}` metadata, `mx/defn`, and `m/=>`:

```clojure
;; m/=> annotation (schema separate from defn)
(defn get-weather [{:keys [city]}] (str "Sunny in " city))
(m/=> get-weather [:=> [:cat [:map {:name "get_weather"} [:city :string]]] :string])
```

For structured output after tool use, compose with `generate`:

```clojure
(let [{:keys [history]} (llm/run-agent ai [#'lookup] "find user 123")]
  (llm/generate ai {:schema user-schema} history))
;; => {:name "Alice" :status "active"}
```

`generate` does not accept tools — it's a pure value function. If getting the value requires tool calls, use `run-agent`.

### Observing tool execution

Use `:on-tool-calls` and `:on-tool-result` to watch the agent work in real time — for logging, progress UI, streaming updates, etc:

```clojure
(llm/run-agent ai [#'geocode #'get-weather]
  {:on-tool-calls  (fn [{:keys [step tool-calls]}]
                     (println "Step" step "→" (mapv :name tool-calls)))
   :on-tool-result (fn [{:keys [tool-call result]}]
                     (println "  " (:name tool-call) "=>" (subs result 0 (min 60 (count result)))))
  "Weather in Tokyo?")
;; Step 0 → ["geocode"]
;;   geocode => {"name":"Tokyo","country":"Japan","latitude":35.6895
;; Step 1 → ["get_weather"]
;;   get_weather => {"temperature_c":20.1,"conditions":"Clear sky"
;; => {:text "It's currently 20.1°C in Tokyo..." :steps [...] ...}
```

Both callbacks receive the current `:step` index (0-based). `:on-tool-result` also includes `:error` (the exception) when a tool throws.

## Provider Flexibility

```clojure
;; OpenAI — reads OPENAI_API_KEY env var by default
(def openai (openai/backend))

;; Static key
(def openai (openai/backend {:api-key "sk-..."}))

;; Custom key function — vault, SSM, rotation, whatever
(def openai (openai/backend {:api-key-fn #(fetch-from-vault "openai-key")}))

;; Custom env var
(def router (openai/backend {:api-key-fn #(System/getenv "OPENROUTER_KEY")
                             :api-base "https://openrouter.ai/api/v1"}))

;; Local models (Ollama, LM Studio, etc)
(def local (openai/backend {:api-base "http://localhost:11434/v1"
                            :api-key "not-needed"}))

;; Same code, any provider
(def ai (assoc any-provider :defaults {:model "gpt-4o-mini"}))
(llm/generate ai "Same interface everywhere")
```

The `api-key-fn` is called on every request — no caching. For expensive key lookups, wrap with `memoize` or your own TTL cache.

## Error Handling

Errors are `ex-info` exceptions with `:error-type` in `ex-data`:

```clojure
(require '[co.poyo.clj-llm.errors :as errors])

(try
  (llm/generate ai {:model "invalid-model"} "Hello")
  (catch Exception e
    (case (errors/error-type e)
      :llm/rate-limit    (println "Rate limited, retry in" (errors/retry-after e) "ms")
      :llm/network-error (println "Network issue, retrying...")
      :llm/invalid-key   (println "Check your API key")
      :llm/server-error  (println "Server error, try again")
      (throw e))))
```

The library does not do automatic retries. Use your own retry logic or a library like `again`.

## Full Response Access

When you need token usage or raw events, use `request` directly:

```clojure
(def resp (llm/request ai "Explain AI briefly"))

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
