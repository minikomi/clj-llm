# A Tour of clj-llm

This walks through the library piece by piece. Every example is real code.

## Summary

| Concept | How |
|---|---|
| Provider | `(openai/backend)` — a map |
| Config | `(assoc provider :defaults {...})` |
| Text generation | `(generate ai "prompt")` → `{:text "..." :usage {...}}` |
| With options | `(generate ai {:system-prompt "..."} "prompt")` |
| Structured output | `(generate ai {:schema s} "prompt")` → `{:structured {...} ...}` |
| Single tool call | `(generate ai {:tools [...]} "prompt")` → `{:text :tool-calls :tool-results}` |
| Agent loop | `(run-agent ai [#'tool-fn] "prompt")` → `{:text :steps :history}` |
| Streaming | `(stream ai "prompt")` → core.async channel of text chunks |
| Raw events | `(events ai "prompt")` → core.async channel of event maps |
| Conversations | `(generate ai history-vector)` |

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

(def openrouter (openai/backend {:api-key-fn #(System/getenv "OPENROUTER_API_KEY")
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

`generate` calls the LLM and returns a result map. The input — a string or a message history vector — is always the **last** argument.

```clojure
(llm/generate ai "What is the capital of France?")
;; => {:text "The capital of France is Paris." :usage {:prompt-tokens 14 :completion-tokens 8}}
```

Pull out just the text when you need it:

```clojure
(:text (llm/generate ai "What is the capital of France?"))
;; => "The capital of France is Paris."
```

Pass options as a map before the input:

```clojure
(llm/generate ai {:system-prompt "Answer in one word."} "What is the capital of France?")
;; => {:text "Paris." :usage {...}}
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

Because input is last and the result is a map, extract `:text` when threading between calls:

```clojure
(->> "The mitochondria is the powerhouse of the cell. It make ATP."
     (llm/generate ai {:system-prompt "Fix grammar and spelling."})
     :text
     (llm/generate ai {:system-prompt "Translate to French."})
     :text)
;; => "La mitochondrie est la centrale énergétique de la cellule. Elle produit de l'ATP."
```

No special pipeline abstraction. Just Clojure.

## 5. Structured output

Pass a `:schema` (a Malli schema) and `generate` returns parsed, validated data in the `:structured` key:

```clojure
(llm/generate ai
  {:schema [:map
            [:name :string]
            [:age :int]
            [:occupation :string]]}
  "Marie Curie was a 66 year old physicist")
;; => {:text "{\"name\":\"Marie Curie\",...}"
;;     :structured {:name "Marie Curie" :age 66 :occupation "physicist"}
;;     :usage {...}}
```

Pull out the data directly:

```clojure
(:structured (llm/generate ai {:schema [:map [:name :string] [:age :int]]} "Marie Curie, age 66"))
;; => {:name "Marie Curie" :age 66}
```

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

(:structured
  (llm/generate ai {:schema company-schema}
    "TechCorp founded 2010. Alice is CEO at $200k, Bob is Engineer at $120k. Offices in NYC and SF."))
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

(:structured (llm/generate extractor "Marie Curie was a 66 year old physicist"))
;; => {:name "Marie Curie" :age 66 :occupation "physicist"}

(:structured (llm/generate extractor "Albert Einstein was a 76 year old theoretical physicist"))
;; => {:name "Albert Einstein" :age 76 :occupation "theoretical physicist"}

;; Override per-call when needed
(llm/generate extractor {:model "gpt-4o"} "some tricky input")
```

No special `task` or `chain` function. It's `assoc` and `merge`.

## 7. Streaming

`stream` returns a `core.async` channel of text chunks:

```clojure
(require '[clojure.core.async :refer [<!!]])

(let [ch (llm/stream ai "Write a haiku about Clojure")]
  (loop []
    (when-let [chunk (<!! ch)]
      (print chunk)
      (flush)
      (recur))))
;; prints: Data flows like water / Parentheses embrace all / REPL sparks joy
```

Collect into a string:

```clojure
(let [ch (llm/stream ai "Count from 1 to 5")]
  (loop [sb (StringBuilder.)]
    (if-let [chunk (<!! ch)]
      (recur (.append sb chunk))
      (str sb))))
```

Options go in the same position:

```clojure
(let [ch (llm/stream ai {:system-prompt "Respond in ALL CAPS"} "Say hello")]
  (loop []
    (when-let [chunk (<!! ch)]
      (print chunk) (flush)
      (recur))))
```

Close the channel to cancel the stream and clean up HTTP resources.

## 8. Conversations

Message history is a vector. You pass it as the input (last arg). There's no conversation object — you own the data.

```clojure
(def history
  [{:role :system :content "You are a helpful coding assistant."}
   {:role :user :content "How do I reverse a list in Clojure?"}
   {:role :assistant :content "Use (reverse coll) or (into () coll)."}
   {:role :user :content "What about in Python?"}])

(:text (llm/generate ai history))
;; => "In Python, use reversed(lst) or lst[::-1]."
```

A simple chat loop:

```clojure
(def conversation (atom []))

(defn chat! [message]
  (swap! conversation conj {:role :user :content message})
  (let [{:keys [text]} (llm/generate ai @conversation)]
    (swap! conversation conj {:role :assistant :content text})
    text))

(chat! "What's the tallest mountain?")
;; => "Mount Everest, at 8,849 meters."

(chat! "How about the second tallest?")
;; => "K2, at 8,611 meters."
```

## 9. Defining tools

Tools are plain functions with standard [Malli function schemas](https://github.com/metosin/malli/blob/master/docs/function-schemas.md). The `:malli/schema` metadata tells the LLM what the tool does and what arguments it takes:

```clojure
(require '[cheshire.core :as json])

;; Geocode: city name → coordinates (free Open-Meteo API, no key needed)
(defn geocode
  {:malli/schema [:=> [:cat [:map {:name "geocode"
                                   :description "Look up latitude and longitude for a city"}
                             [:city {:description "City name"} :string]]]
                      [:map [:name :string] [:country :string]
                            [:latitude :double] [:longitude :double]]]}
  [{:keys [city]}]
  (let [geo (-> (slurp (str "https://geocoding-api.open-meteo.com/v1/search?name="
                            (java.net.URLEncoder/encode city "UTF-8") "&count=1"))
                (json/parse-string true))
        loc (first (:results geo))]
    (select-keys loc [:name :country :latitude :longitude]))))

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
```

Tools are regular Clojure functions. Call them directly, test them, compose them:

```clojure
(geocode {:city "Tokyo"})
;; => {:name "Tokyo" :country "Japan" :latitude 35.6895 :longitude 139.69171}

(get-weather {:latitude 35.6895 :longitude 139.6917})
;; => "20.1°C, wind 7.6 km/h"
```

Schemas live in var metadata — standard Malli, nothing custom:

```clojure
(:malli/schema (meta #'geocode))
;; => [:=> [:cat [:map {:name "geocode" ...} ...]] :string]
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
(llm/generate ai {:tools [#'geocode #'get-weather]} "What's the weather in Tokyo?")
;; => {:text nil
;;     :tool-calls [{:id "call_abc" :name "geocode" :arguments {:city "Tokyo"}}]
;;     :tool-results ["{\"name\":\"Tokyo\",...}"]  ;; auto-serialized to JSON
;;     :usage {...}}
```

When tools are configured, `generate` always returns a map with these keys:

- `:text` — any text the model produced alongside tool calls (often `nil`)
- `:tool-calls` — vector of `{:id :name :arguments}` maps
- `:tool-results` — vector of results from executing each tool call

If the model decides no tools are needed, you get empty vectors:

```clojure
(llm/generate ai {:tools [#'geocode #'get-weather]} "What is 2 + 2?")
;; => {:text "2 + 2 = 4" :tool-calls [] :tool-results [] :usage {...}}
```

This is one LLM call. The model sees the tools, optionally calls them, and you get back data. No loops, no agents.

## 11. Multi-turn agents with `run-agent`

`run-agent` is the autonomous loop. It calls the LLM, executes tools, feeds results back, and repeats until the model stops calling tools.

```clojure
(llm/run-agent ai [#'geocode #'get-weather] "Weather in Tokyo?")
;; Step 1: LLM calls geocode({:city "Tokyo"}) → coordinates
;; Step 2: LLM calls get-weather({:latitude 35.69 :longitude 139.69}) → conditions
;; Step 3: LLM produces final answer
;; => {:text    "It's currently 20.1°C in Tokyo with light wind."
;;     :history [{:role :user ...} {:role :assistant ...} {:role :tool ...} ...]
;;     :steps   [{:tool-calls [...] :tool-results [...]}
;;              {:tool-calls [...] :tool-results [...]}]
;;     :usage   {:prompt-tokens ... :completion-tokens ...}}
```

`:text` is the final answer. `:history` is the full conversation (reusable). `:steps` records each tool-calling iteration.

Options go between tools and input:

```clojure
(llm/run-agent ai [#'geocode #'get-weather] {:max-steps 3} "Weather in Tokyo?")
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
  (:structured (llm/generate ai {:schema [:map [:name :string] [:status :string]]} history)))
;; => {:name "Alice" :status "active"}
```

This keeps `run-agent` focused on the tool loop and `generate` as the single place structured extraction happens.

## 12. `generate` vs `run-agent`

| | `generate` | `run-agent` |
|---|---|---|
| LLM calls | Exactly one | Loop until done |
| Tools | Optional — pass `:tools` in opts | Required — pass as second arg |
| Executes tools | Yes, once | Yes, each iteration |
| Returns | `{:text :usage}`, `{:structured ...}`, or `{:text :tool-calls :tool-results}` | `{:text :history :steps}` |
| Stop control | N/A (one call) | `:stop-when` predicate, `:max-steps` |
| Use when | You know one call is enough | The model needs to iterate |

`generate` is the workhorse. `run-agent` is for when the model needs to drive.

## 13. Raw event stream

`events` returns a `core.async` channel of raw provider events. Use it when you need fine-grained control over the stream:

```clojure
(require '[clojure.core.async :refer [<!!]])

(let [ch (llm/events ai "Count to 5")]
  (loop []
    (when-let [event (<!! ch)]
      (println (:type event) (dissoc event :type))
      (recur))))
;; :content {:content "1"}
;; :content {:content ", 2"}
;; ...
;; :usage {:prompt-tokens 10 :completion-tokens 20 ...}
;; :done {}
```

Event types: `:content`, `:tool-call`, `:tool-call-delta`, `:usage`, `:finish`, `:error`, `:done`.

Close the channel to cancel and clean up HTTP resources.

## 14. Error handling

Connection errors throw the underlying Java exception directly (`ConnectException`, `HttpTimeoutException`, etc.). No wrapping.

HTTP errors (non-200) throw `ex-info` with the status and the API's response body — the actual error message from OpenAI/Anthropic:

```clojure
(try
  (llm/generate ai {:model "nonexistent-model"} "hello")
  (catch clojure.lang.ExceptionInfo e
    (let [{:keys [status body]} (ex-data e)]
      (println "HTTP" status)
      (println body)))
  (catch Exception e
    ;; Connection/timeout errors — plain Java exceptions
    (println "Connection error:" (.getMessage e))))
;; HTTP 404
;; {"error":{"message":"The model 'nonexistent-model' does not exist",...}}
```

Option validation errors (bad keys, missing model) are also `ex-info` with `:error-type :llm/invalid-request`.

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
(:text (llm/generate openai-ai "Explain monads in one sentence."))
(:text (llm/generate claude-ai "Explain monads in one sentence."))
(:text (llm/generate local-ai  "Explain monads in one sentence."))
```
