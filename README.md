# clj-llm

A small Clojure library for talking to LLMs without a lot of ceremony. Providers are maps, requests are plain data, and results come back as maps you can pass around.

## Installation

```clojure
{:deps {co.poyo/clj-llm {:git/url "https://github.com/poyo-ai/clj-llm"
                         :git/sha "..."}}}
```

## Quick start

```clojure
(require '[co.poyo.clj-llm.core :as llm]
         '[co.poyo.clj-llm.backend.openai :as openai])

(def ai (openai/backend {:api-key "sk-..."
                         :defaults {:model "gpt-4o-mini"}}))

(:text (llm/generate ai "What is the capital of France?"))
;; => "The capital of France is Paris."
```

## Overview

| Concept | How |
|---|---|
| Provider | `(openai/backend)` gives you a map |
| Config | `(assoc provider :defaults {...})` |
| Text | `(generate ai "prompt")` → `{:text "..." :usage {...}}` |
| Options | `(generate ai {:system-prompt "..."} "prompt")` |
| Structured | `(generate ai {:schema s} "prompt")` → `{:structured {...} ...}` |
| Tool call | `(generate ai {:tools [...]} "prompt")` → `{:tool-calls [...] :tool-results [...]}` |
| Agent loop | `(run-agent ai {:tools [#'tool]} "prompt")` → `{:text ... :steps ... :history ...}` |
| Streaming | `(generate ai {:on-text print} "prompt")` |
| Raw events | `(events ai "prompt")` → core.async channel |
| Images/PDFs | `(generate ai ["describe" {:type :image :path "photo.jpg"}])` |
| Chaining | `(->> "text" (generate ai) (generate ai))` |

## Providers

Pick a backend, put shared config in `:defaults`, and use normal map operations when you want variants:

```clojure
(def openai  (openai/backend {:api-key "sk-..."}))
(def claude  (anthropic/backend {:api-key "sk-ant-..."}))
(def ollama  (openai/backend {:api-base "http://localhost:11434/v1"
                              :api-key false}))
(def router  (openai/backend {:api-key "sk-..."
                              :api-base "https://openrouter.ai/api/v1"}))

(def ai (openai/backend {:api-key "sk-..."
                         :defaults {:model "gpt-4o-mini"}}))

;; Make a tweaked provider by changing the map
(def careful (update ai :defaults merge {:model "gpt-4o" :temperature 0.2}))
```

`api-key` can be a string, a zero-arg function called on every request, or `false` when your endpoint does not need auth.

When no `:api-key` is provided, each backend reads from a default environment variable:

| Backend | Env var |
|---|---|
| OpenAI | `OPENAI_API_KEY` |
| Anthropic | `ANTHROPIC_API_KEY` |
| OpenRouter | `OPENROUTER_KEY` |

## Generate

Input is always last. Options go before it, so threading stays pleasant:

```clojure
(llm/generate ai "hello")
;; => {:text "Hello!" :usage {:prompt-tokens 5 :completion-tokens 3}}

(llm/generate ai {:system-prompt "Answer in one word."} "Capital of France?")
;; => {:text "Paris." :usage {...}}

(llm/generate ai {:model         "gpt-4o"
                  :system-prompt "Be concise."
                  :temperature   0.2
                  :max-tokens    100}
  "Explain recursion")
```

Provider-specific params go under `:provider-opts`, for example `{:provider-opts {:frequency_penalty 0.5}}`.

## Chaining

Results have `:text` and sometimes `:structured`. Pass a result into the next call and clj-llm unwraps the useful bit:

```clojure
(->> "The mitochondria is the powerhouse of the cell. It make ATP."
     (llm/generate ai {:system-prompt "Fix grammar."})
     (llm/generate ai {:system-prompt "Translate to French."}))
;; => {:text "La mitochondrie est la centrale..." :usage {...}}
```

A result with `:structured` unwraps with `prn-str`.

## Structured output

Pass a Malli schema when you want parsed, validated data back:

```clojure
(llm/generate ai
  {:schema [:map [:name :string] [:age :int] [:occupation :string]]}
  "Marie Curie was a 66 year old physicist")
;; => {:text "{...}" :structured {:name "Marie Curie" :age 66 :occupation "physicist"} :usage {...}}
```

If you reuse the same extraction setup, just bake it into provider defaults:

```clojure
(def extractor
  (update ai :defaults merge
    {:system-prompt "Extract structured data."
     :schema [:map [:name :string] [:age :int] [:occupation :string]]}))

(:structured (llm/generate extractor "Albert Einstein was a 76 year old physicist"))
;; => {:name "Albert Einstein" :age 76 :occupation "theoretical physicist"}
```

## Streaming

`:on-text` streams chunks while `generate` still returns the full result:

```clojure
(llm/generate ai {:on-text (fn [chunk] (print chunk) (flush))} "Write a haiku")
;; prints live, then returns {:text "..." :usage {...}}
```

For reasoning-capable models, `:on-reasoning` gives you the reasoning stream separately when the provider sends it:

```clojure
(llm/generate ai {:on-reasoning (fn [chunk] (print "[thinking]" chunk) (flush))
                  :on-text      (fn [chunk] (print chunk) (flush))}
  "Solve this logic puzzle: ...")
;; prints reasoning chunks with [thinking] prefix, then the final answer
```

## Conversations

Message history is just a vector you pass as input:

```clojure
(def convo (atom []))

(defn chat! [msg]
  (swap! convo conj {:role :user :content msg})
  (let [{:keys [text]} (llm/generate ai @convo)]
    (swap! convo conj {:role :assistant :content text})
    text))

(chat! "What's the tallest mountain?")  ;; => "Mount Everest..."
(chat! "Second tallest?")               ;; => "K2..."
```

## Images and PDFs

Images and PDFs are ordinary maps in the main API. Local images are read and base64-encoded for you. URL images pass through by reference unless you ask for resizing.

```clojure
(:text (llm/generate ai ["What's in this image?" {:type :image :path "photo.jpg"}]))
(:text (llm/generate ai ["Describe this" {:type :image :url "https://example.com/chart.png"}]))
(:text (llm/generate claude-ai ["Summarize" {:type :pdf :path "invoice.pdf"}]))

;; Resize to control cost and size limits
(llm/generate ai ["Describe" {:type :image :path "huge.jpg" :max-edge 512}])
(llm/generate ai ["Describe" {:type :image
                              :path "photo.png"
                              :max-edge 1024
                              :format "jpeg"
                              :quality 85}])
```

Media maps work with structured output too:

```clojure
(def ReceiptSummary
  [:map
   [:store :string]
   [:total :string]
   [:currency :string]])

(:structured
 (llm/generate ai
   {:schema ReceiptSummary}
   ["Extract the receipt summary."
    {:type :image :path "receipt.jpg" :max-edge 512}]))
;; => {:store "Lidl" :total "43.79" :currency "EUR"}
```

## Tool calling

Tools are regular functions with [Malli function schemas](https://github.com/metosin/malli/blob/master/docs/function-schemas.md):

```clojure
(defn get-weather
  {:malli/schema [:=> [:cat [:map {:name "get_weather"
                                   :description "Get current weather"}
                             [:city {:description "City name"} :string]]]
                      :string]}
  [{:keys [city]}]
  (str "Sunny, 22°C in " city))

;; Single LLM call: the model decides whether to use tools
(llm/generate ai {:tools [#'get-weather]} "Weather in Tokyo?")
;; => {:text nil
;;     :tool-calls [{:id "call_1" :name "get_weather" :arguments {:city "Tokyo"}}]
;;     :tool-results ["Sunny, 22°C in Tokyo"]
;;     :usage {...}}

;; Agent loop: keep calling tools until the model is done
(llm/run-agent ai {:tools [#'get-weather]} "Weather in Tokyo?")
;; => {:text    "It's currently sunny and 22°C in Tokyo."
;;     :history [...]
;;     :steps   [{:tool-calls [...] :tool-results [...]}]
;;     :usage   {...}}
```

All Malli schema styles work: `{:malli/schema ...}` metadata, `mx/defn`, `m/=>`.

### Agent options

```clojure
(llm/run-agent ai
  {:tools [#'search #'done]
   :max-steps  5
   :stop-when  (fn [{:keys [tool-calls]}]
                 (some #(= "done" (:name %)) tool-calls))
   :on-text        (fn [chunk] (print chunk) (flush))
   :on-reasoning   (fn [chunk] (print "[thinking]" chunk) (flush))
   :on-tool-calls  (fn [{:keys [step tool-calls]}]
                     (println "Step" step (mapv :name tool-calls)))
   :on-tool-result (fn [{:keys [tool-call result error]}]
                     (println " ->" (:name tool-call) result))}
  "Research quantum computing")
```

`:stop-when` fires before tools execute, so pending calls are returned in `:tool-calls` without being run.

### Structured output after tool use

```clojure
(let [{:keys [history]} (llm/run-agent ai {:tools [#'lookup]} "Find user 123")]
  (:structured (llm/generate ai {:schema [:map [:name :string] [:status :string]]} history)))
```

## generate vs run-agent

| | `generate` | `run-agent` |
|---|---|---|
| LLM calls | Exactly one | Loop until done |
| Tools | Optional (`:tools` in opts) | Required (`:tools` in opts) |
| Stop control | N/A | `:stop-when`, `:max-steps` |
| Returns | `{:text :usage}` or `{:structured}` or `{:tool-calls :tool-results}` | `{:text :history :steps}` |

## Raw events

```clojure
(require '[clojure.core.async :refer [<!!]])

(let [ch (llm/events ai "Count to 5")]
  (loop []
    (when-let [event (<!! ch)]
      (println (:type event) (dissoc event :type))
      (recur))))
;; :content {:content "1"}
;; :usage {:prompt-tokens 10 :completion-tokens 20}
;; :done {}
```

Event types: `:content`, `:reasoning`, `:tool-call`, `:tool-call-delta`, `:usage`, `:finish`, `:error`, `:done`.

## Error handling

Connection errors are plain Java exceptions. HTTP errors are `ex-info` with `:status` and `:body`:

```clojure
(try
  (llm/generate ai {:model "nonexistent"} "hello")
  (catch clojure.lang.ExceptionInfo e
    (let [{:keys [status body]} (ex-data e)]
      (println "HTTP" status body)))
  (catch Exception e
    (println "Connection error:" (.getMessage e))))
```

Option validation errors are `ex-info` with `:error-type :llm/invalid-request`. There are no automatic retries.

## Babashka

Works out of the box. The HTTP layer switches automatically between `java.net.http` and `babashka.http-client`.

```bash
#!/usr/bin/env bb
(require '[co.poyo.clj-llm.core :as llm]
         '[co.poyo.clj-llm.backend.openai :as openai])

(def ai (openai/backend {:api-key "sk-..."
                         :defaults {:model "gpt-4o-mini"}}))
(println (:text (llm/generate ai "Hello from Babashka!")))
```
