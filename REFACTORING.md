# clj-llm Refactoring: Decomplecting the Event Pipeline

## Motivation

After completing 19 bug-fix tasks, three areas had accumulated complexity:

1. **`consume-events` go-loop** — 5 positional `recur` args, state transitions tangled with channel I/O
2. **`event->internal` return type** — polymorphic (nil, map, or vector), forcing conditional unwrapping downstream
3. **`run-agent` loop** — two nearly-identical code paths that could drift apart

The goal: make state updates predictable (`state × event → state'`), give every function a uniform contract, and eliminate duplicated branches.

---

## 1. Pure State Machine for `consume-events`

### Before

The go-loop carried five positional accumulators and mixed state updates with side effects in every `case` branch:

```clojure
(go-loop [chunks []
          tool-calls []
          tc-index {}
          usage-acc {}
          finish-reason nil]
  (if-let [event (<! source-chan)]
    (do
      (>! events-chan event)                          ;; side effect
      (case (:type event)
        :content
        (do (>! text-chunks-chan (:content event))    ;; side effect
            (recur (conj chunks (:content event))     ;; state update
                   tool-calls tc-index
                   usage-acc finish-reason))
        :usage
        (recur chunks tool-calls tc-index
               (merge usage-acc (dissoc event :type)) ;; state update
               finish-reason)
        :done
        (finalize! (apply str chunks)                 ;; derive + side effect
                   (not-empty tool-calls)
                   (cond-> usage-acc ...))
        ;; ... every branch repeats all 5 args
```

**Problems:**
- Adding a 6th accumulator means touching every `recur` call
- Can't unit-test state transitions without channels
- Side effects and state logic are interleaved — hard to reason about ordering

### After

State transitions are a pure function. The go-loop is just plumbing.

```clojure
(def ^:private init-state
  {:chunks [] :tool-calls [] :tc-index {} :usage {} :finish-reason nil :done? false :error nil})

(defn- next-state
  "Pure: state × event → state'. No channels, no promises."
  [state event]
  (case (:type event)
    :content      (update state :chunks conj (:content event))
    :tool-call    (let [idx (or (:index event) (count (:tool-calls state)))
                        call (assoc event :arguments (or (:arguments event) ""))]
                    (-> state
                        (update :tool-calls conj call)
                        (assoc-in [:tc-index idx] (count (:tool-calls state)))))
    :tool-call-delta (let [pos (get (:tc-index state) (:index event))]
                       (if pos
                         (update-in state [:tool-calls pos :arguments] str (:arguments event))
                         state))
    :usage        (update state :usage merge (dissoc event :type))
    :finish       (assoc state :finish-reason (:reason event))
    :error        (assoc state :done? true :error (errors/error ...))
    :done         (assoc state :done? true)
    state))  ;; unknown → pass through
```

Derivation functions extract final values from state:

```clojure
(defn- state->text [{:keys [chunks error]}]
  (or error (apply str chunks)))

(defn- state->usage [{:keys [usage finish-reason]} provider-opts req-start]
  (let [u (cond-> usage finish-reason (assoc :finish-reason finish-reason))]
    (when (seq u)
      (let [now (System/currentTimeMillis)]
        (assoc u :type :usage ...)))))
```

The go-loop becomes mechanical:

```clojure
(go-loop [state init-state]
  (if-let [event (<! source-chan)]
    (let [state' (next-state state event)]
      ;; Side effects: fan out
      (>! events-chan event)
      (when (= :content (:type event))
        (>! text-chunks-chan (:content event)))
      (when (and (:error state') (not (realized? structured-promise)))
        (deliver structured-promise (Exception. (str (:error event)))))
      ;; Continue or finalize
      (if (:done? state')
        (finalize! state')
        (recur state')))
    (finalize! state)))
```

**What this buys:**
- `next-state` is a pure function — testable with plain maps, no async setup
- Adding new accumulators = add a key to `init-state` + a `case` branch. No recur args to update.
- Side effects are visually separated from state logic
- `finalize!` takes one arg (state), not three

---

## 2. Uniform `event->internal` Contract

### Before

Backend event converters returned **nil, a single map, or a vector of maps**:

```clojure
;; OpenAI — polymorphic return
(defn- data->internal-event [data schema tools]
  (cond
    (not-empty content) {:type :content :content content}       ;; map
    tool-calls          (let [events (keep convert-one tool-calls)]
                          (when (seq events)
                            (if (= 1 (count events))
                              (first events)    ;; map
                              (vec events))))   ;; vector!
    ...))
```

`backend_helpers` had to handle all three cases:

```clojure
(if-let [result (event->internal ...)]
  (let [evts (if (sequential? result) result [result])]  ;; unwrap
    (loop [es (seq evts)]                                 ;; inner loop
      (when es
        (let [e (first es)]
          (>! events-chan e)
          (when (not= :done (:type e))
            (recur (next es))))))
    (when-not (some #(= :done (:type %)) evts)
      (recur)))
  (recur))
```

**Problems:**
- Caller must handle three return shapes
- `(if (sequential? ...) ...)` is a code smell — the producer should decide the shape
- Nested `loop` inside a `go` block is fragile (core.async macro limitations)

### After

Every backend returns **seq or nil**. Always.

```clojure
;; OpenAI
(defn- data->internal-events [data schema tools]
  (cond
    (not-empty content) [{:type :content :content content}]
    tool-calls          (not-empty (keep convert-one tool-calls))
    finish-reason       [{:type :finish :reason finish-reason}]
    usage               [(into {:type :usage} usage)]
    :else nil))

;; Anthropic — same contract
(defn- data->internal-events [data schema tools]
  (case (:type data)
    "content_block_delta" [{:type :content :content ...}]
    "message_stop"        [{:type :done}]
    ...))
```

`backend_helpers` simplifies — no conditional wrapping:

```clojure
(if-let [evts (seq (event->internal ...))]
  (let [done? (loop [es evts]
                (if es
                  (let [e (first es)]
                    (>! events-chan e)
                    (if (= :done (:type e)) true (recur (next es))))
                  false))]
    (when-not done? (recur)))
  (recur))
```

**What this buys:**
- One return shape to handle
- No `(if (sequential?) ...)` at the call site
- The `done?` flag replaces `(some #(= :done ...) evts)` — computed during the loop, not after

---

## 3. Single Code Path in `run-agent`

### Before

Two parallel branches — one for "has tool calls", one for "no tool calls (reasoning)":

```clojure
(if (empty? (or tc []))
  ;; No tool calls — model is reasoning
  (if (>= (inc n) max-steps)
    {:text text :history (conj history {:role :assistant :content (or text "")}) :steps steps :truncated true}
    (recur (conj history {:role :assistant :content (or text "")})
           steps (inc n)))
  ;; Has tool calls — execute them
  (let [msg (tool-calls->assistant-message tc text)]
    (if (>= (inc n) max-steps)
      {:text text :history (conj history msg) :steps steps :truncated true}
      (let [results    (mapv ... tc)
            result-msgs (mapv ... results)
            new-history (into (conj history msg) result-msgs)]
        (recur new-history
               (conj steps {:tool-calls ... :tool-results ...})
               (inc n))))))
```

**Problems:**
- `max-steps` check duplicated (2×)
- History construction duplicated with slightly different shapes
- Adding a new field to the return map means editing both branches

### After

One `let` computes everything. Branches only where values differ:

```clojure
(let [has-tc?  (seq tc)
      results  (when has-tc? (mapv ... tc))
      msg      (if has-tc?
                 (tool-calls->assistant-message tc text)
                 {:role :assistant :content (or text "")})
      msgs     (if has-tc?
                 (into [msg] (mapv ... results))
                 [msg])
      next-history (into history msgs)
      next-steps   (if has-tc?
                     (conj steps {:tool-calls ... :tool-results ...})
                     steps)]
  (if (>= (inc n) max-steps)
    {:text text :history next-history :steps next-steps :truncated true}
    (recur next-history next-steps (inc n))))
```

**What this buys:**
- One `max-steps` check
- One `recur`
- New return fields go in one place
- `has-tc?` is a value, not a control-flow fork

---

## Summary

| Area | Before | After | Key win |
|------|--------|-------|---------|
| `consume-events` | 5 positional recur args, tangled state+I/O | Pure `next-state` fn + mechanical go-loop | Testable state transitions |
| `event->internal` | nil \| map \| vector | nil \| seq (always) | One shape, no conditional unwrapping |
| `run-agent` | Two parallel branches, 2× max-steps check | One `let`, one check, one recur | No drifting duplicates |

**Tests:** 73 tests, 234 assertions, 0 failures. Net change: +11 lines of source, -2 assertions (removed polymorphic-return-type checks that no longer apply).
