# clj-llm Examples

Progressive examples showing how to use clj-llm—from simple prompts to advanced patterns.

---

## Setup

```clojure
(require '[co.poyo.clj-llm.core :as llm]
         '[co.poyo.clj-llm.backends.openai :as openai])

(def ai (openai/backend {:api-key-env "OPENAI_API_KEY"}))
```

Backends are just data. No side effects, no hidden state.

---

## Your First Prompt

```clojure
@(:text (llm/request ai "What is 2+2?"))
;; => "2 + 2 equals 4."
```

`llm/prompt` returns a response object. Dereference `:text` to get the complete generated text.

---

## Adding Context

### System Prompts

Guide the AI's behavior:

```clojure
@(:text (llm/request ai
                    "Explain recursion"
                    {:llm/system-prompt "You are a patient teacher. Use simple analogies."}))
```

### Provider Options

Control model parameters:

```clojure
@(:text (llm/request ai
                    "Invent a programming language"
                    {:provider/opts {:temperature 0.9
                                     :max_tokens 200
                                     :model "gpt-4o"}}))
```

**The boundary:** `:llm/*` options control the library. `:provider/opts` passes through to the provider API. This keeps things explicit and future-proof.

---

## Provider Flexibility

Same interface, any provider:

```clojure
;; OpenAI
(def openai (openai/backend {:api-key-env "OPENAI_API_KEY"}))

;; Local Ollama
(def local (openai/backend
             {:api-base "http://localhost:11434/v1"
              :defaults {:provider/opts {:model "llama2"}}}))

;; OpenRouter (100+ models)
(def router (openai/backend
              {:api-key-env "OPENROUTER_API_KEY"
               :api-base "https://openrouter.ai/api/v1"}))
```

Your code doesn't change:

```clojure
(defn explain [backend concept]
  @(:text (llm/request backend (str "Explain " concept))))

(explain openai "closures")
(explain local "closures")
(explain router "closures")
```

Use local models in development, cloud in production. Route tasks to different models. Never get locked in.

---

## Structured Output

Extract validated, typed data using Malli schemas:

```clojure
(def person-schema
  [:map
   [:name :string]
   [:age pos-int?]
   [:occupation :string]])

@(:structured (llm/request ai
                          "Extract: Marie Curie was a 66 year old physicist"
                          {:llm/schema person-schema}))
;; => {:name "Marie Curie", :age 66, :occupation "physicist"}
```

No string parsing. No hoping the format is right. Just validated data.

### Complex Structures

Schemas compose:

```clojure
(def invoice-schema
  [:map
   [:invoice-number :string]
   [:total [:double {:min 0}]]
   [:items [:vector [:map
                     [:description :string]
                     [:quantity pos-int?]
                     [:price [:double {:min 0}]]]]]])

@(:structured (llm/request ai invoice-text {:llm/schema invoice-schema}))
;; => {:invoice-number "INV-2024-001"
;;     :total 150.0
;;     :items [{:description "Widget A", :quantity 2, :price 25.0}
;;             {:description "Service B", :quantity 1, :price 100.0}]}
```

### Enums and Constraints

```clojure
(def sentiment-schema
  [:map
   [:sentiment [:enum "positive" "negative" "neutral"]]
   [:confidence [:double {:min 0 :max 1}]]
   [:keywords [:vector :string]]])

@(:structured (llm/request ai
                          "Analyze: This product exceeded expectations!"
                          {:llm/schema sentiment-schema}))
;; => {:sentiment "positive"
;;     :confidence 0.95
;;     :keywords ["exceeded" "expectations"]}
```

---

## Streaming

Get text as it arrives:

```clojure
(require '[clojure.core.async :refer [<!!]])

(let [response (llm/request ai "Write a short story about a robot")
      chunks (:chunks response)]
  (loop []
    (when-let [chunk (<!! chunks)]
      (print chunk)
      (flush)
      (recur))))
```

### Complete Text After Streaming

The `:text` promise resolves even while streaming:

```clojure
(let [response (llm/request ai "Explain neural networks")
      chunks (:chunks response)]
  ;; Start streaming - consume a few chunks
  (dotimes [_ 5]
    (when-let [chunk (<!! chunks)]
      (print chunk)
      (flush)))

  ;; Later, get complete text (includes everything)
  (def full-text @(:text response)))
```

### Streaming with Options

```clojure
(let [response (llm/request ai
                           "Write a creative story"
                           {:provider/opts {:temperature 0.9}
                            :llm/system-prompt "You are a novelist."})
      chunks (:chunks response)]
  (loop []
    (when-let [chunk (<!! chunks)]
      (print chunk)
      (flush)
      (recur))))
```

---

## Conversations

Build interactive experiences with message history:

```clojure
(def messages
  [{:role :system :content "You are a math tutor"}
   {:role :user :content "What is 2+2?"}
   {:role :assistant :content "2+2 equals 4."}
   {:role :user :content "What about 2+3?"}])

@(:text (llm/request ai nil {:llm/message-history messages}))
;; => "2+3 equals 5."
```

Pass `nil` as the prompt when using message history.

### Stateful Conversations

```clojure
(def conversation
  (atom [{:role :system :content "You are a pirate."}]))

(defn chat! [message]
  (swap! conversation conj {:role :user :content message})
  (let [response @(:text (llm/request ai nil
                                     {:llm/message-history @conversation}))]
    (swap! conversation conj {:role :assistant :content response})
    response))

(chat! "Hello!")
;; => "Ahoy there, matey!"

(chat! "What's your favorite treasure?")
;; => "Arr, me favorite be a fine chest o' gold doubloons!"
```

### Managing Long Conversations

Keep recent messages to control token usage:

```clojure
(defn keep-recent [messages n]
  (let [system-msg (first messages)
        recent (take-last n (rest messages))]
    (into [system-msg] recent)))

(swap! conversation keep-recent 10)
```

---

## Response Control

The response object gives you complete control:

```clojure
(let [response (llm/request ai "Explain quantum computing")]
  ;; Available fields:
  (:text response)       ; Promise - complete text
  (:structured response) ; Promise - structured data (if schema provided)
  (:usage response)      ; Promise - token usage
  (:chunks response)     ; Channel - text chunks
  (:events response))    ; Channel - all events
```

### Token Usage

```clojure
(let [response (llm/request ai "Write an analysis")
      usage @(:usage response)]
  (println "Prompt tokens:" (:prompt-tokens usage))
  (println "Completion tokens:" (:completion-tokens usage))
  (println "Total:" (:total-tokens usage)))
```

### Raw Events

```clojure
(let [response (llm/request ai "Test")
      events (:events response)]
  (loop []
    (when-let [event (<!! events)]
      (case (:type event)
        :content (print (:content event))
        :usage   (println "\nTokens:" (:total-tokens event))
        :error   (println "Error:" (:error event))
        :done    (println "Complete"))
      (when-not (= :done (:type event))
        (recur)))))
```

### IDeref Convenience

```clojure
;; These are equivalent:
@response
@(:text response)
```

---

## Error Handling

```clojure
(try
  @(:text (llm/request ai "Hello" {:provider/opts {:model "invalid"}}))
  (catch Exception e
    (println "Error:" (ex-message e))
    (println "Data:" (ex-data e))))
```

Streaming errors come through events:

```clojure
(let [response (llm/request ai "Test" {:provider/opts {:model "bad"}})
      events (:events response)]
  (loop []
    (when-let [event (<!! events)]
      (when (= :error (:type event))
        (println "Error:" (:error event)))
      (recur))))
```

---

## Patterns

### Document Analysis

```clojure
(def analysis-schema
  [:map
   [:key-points [:vector :string]]
   [:sentiment [:enum "positive" "negative" "neutral"]]
   [:action-items [:vector :string]]
   [:confidence [:double {:min 0 :max 1}]]])

(defn analyze [backend doc]
  @(:structured (llm/request backend doc
                            {:llm/schema analysis-schema
                             :llm/system-prompt "Extract key information."})))

(analyze ai meeting-notes)
```

### Provider Fallback

```clojure
(defn prompt-with-fallback [providers prompt opts]
  (loop [remaining providers]
    (when-let [provider (first remaining)]
      (try
        @(:text (llm/request provider prompt opts))
        (catch Exception e
          (if-let [next-providers (seq (rest remaining))]
            (recur next-providers)
            (throw e)))))))

(prompt-with-fallback [local openai router] "Explain this" {})
```

### Function Calling

```clojure
(def send-email-schema
  [:map {:name "send_email"}
   [:to :string]
   [:subject :string]
   [:body :string]
   [:priority {:optional true} [:enum "high" "normal"]]])

(defn send-email [params]
  (println "Sending to" (:to params))
  {:status :sent})

(def cmd "Send urgent email to john@example.com about Q4 report")
(def params @(:structured (llm/request ai cmd {:llm/schema send-email-schema})))

(send-email params)
```

### Streaming Chatbot

```clojure
(defn create-chatbot [backend system-prompt]
  (let [msgs (atom [{:role :system :content system-prompt}])]
    {:send! (fn [user-msg callback]
              (swap! msgs conj {:role :user :content user-msg})
              (let [response (llm/request backend nil {:llm/message-history @msgs})
                    collected (atom "")]
                (loop []
                  (when-let [chunk (<!! (:chunks response))]
                    (swap! collected str chunk)
                    (callback chunk)
                    (recur)))
                (swap! msgs conj {:role :assistant :content @collected})
                @collected))
     :clear! (fn [] (reset! msgs [{:role :system :content system-prompt}]))}))

(def bot (create-chatbot ai "You are a helpful assistant."))
((:send! bot) "How do I reverse a list?" #(print %))
```

---

Check `/scripts` for runnable examples.