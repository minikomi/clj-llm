# clj-llm Refactoring: From Bug Fixes to Joy

After completing 19 bug-fix tasks, the codebase worked correctly but had accumulated structural complexity. This documents two phases of refactoring: a **structural decomplection** and a **readability cleanup** across all 11 source files.

**Final state:** 74 tests, 237 assertions, 0 failures. 1,452 lines of source across 11 files.

---

## Phase 1: Structural Decomplection

Three areas had accumulated complexity that made the code harder to reason about and easier to break.

### 1.1 Pure State Machine for `consume-events`

The go-loop carried **five positional `recur` arguments**, with state updates and channel I/O interleaved in every `case` branch.

**Before** — tangled state + I/O:

```clojure
(go-loop [chunks []
          tool-calls []
          tc-index {}
          usage-acc {}
          finish-reason nil]
  (case (:type event)
    :content
    (do (>! text-chunks-chan (:content event))
        (recur (conj chunks (:content event))
               tool-calls tc-index
               usage-acc finish-reason))
    ;; every branch repeats all 5 args
```

**After** — pure transitions + thin loop:

```clojure
(defn- next-state [state event]
  (case (:type event)
    :content      (update state :chunks conj (:content event))
    :tool-call    (-> state (update :tool-calls conj call) (assoc-in ...))
    :usage        (update state :usage merge (dissoc event :type))
    :done         (assoc state :done? true)
    state))  ;; unknown → pass through

(go-loop [state init-state]
  (if-let [event (<! source-chan)]
    (let [state' (next-state state event)]
      (>! events-chan event)
      (when (= :content (:type event))
        (>! text-chunks-chan (:content event)))
      (if (:done? state') (finalize! state') (recur state')))
    (finalize! state)))
```

**Key win:** `next-state` is a pure function — testable with plain maps, no async setup. Adding accumulators means adding a key to `init-state` + a `case` branch. No recur args to update.

### 1.2 Uniform `data->internal-events` Contract

Backend event converters returned **nil, a single map, or a vector of maps**, forcing conditional unwrapping downstream.

**Before:**
```clojure
;; returns map OR vector
(cond
  content     {:type :content ...}        ;; map
  tool-calls  (if (= 1 (count events))
                (first events)            ;; map
                (vec events))             ;; vector!

;; caller must unwrap
(let [evts (if (sequential? result) result [result])] ...)
```

**After:** always seq or nil.
```clojure
(cond
  content     [{:type :content ...}]
  tool-calls  (not-empty (keep convert-one tool-calls))
  usage       [(into {:type :usage} usage)]
  :else nil)
```

**Key win:** One return shape. No `(if (sequential?) ...)` at call sites. Both backends follow the same contract.

### 1.3 Single Code Path in `run-agent`

The agent loop had two nearly-identical branches (tool calls vs. no tool calls), each with its own `max-steps` check, history construction, and `recur`.

**After:** One `let` computes values, branches only where they differ, one `max-steps` check, one `recur`.

---

## Phase 2: Readability Cleanup

Systematic pass across all 11 source files in 8 commits, touching 16 files.

### 2.1 Naming Pass

Replaced cryptic abbreviations with clear names across 6 files:

| Before | After | Why |
|--------|-------|-----|
| `bh` (backend alias) | `backend` | Non-obvious abbreviation |
| `tc` (6 uses) | `parsed-calls` | Cryptic 2-letter binding |
| `:tc-index` | `:tool-call-positions` | Unclear what it maps |
| `merged` | `resolved` | Describes *how*, not *what* |
| `base-opts` | `request-opts` | Base relative to what? |
| `cb` | `on-response` | Generic callback name |
| `s`, `t` | `fn-schema`, `schema-type` | Single letters through 4 conditionals |
| `event->internal` | `parse-sse-data` | Input isn't an "event" |

### 2.2 Consistency Pass

Same patterns done the same way everywhere:

- **`unwrap!` helper** — the check-if-exception-and-throw pattern appeared at 5 call sites with slight variations. Now one function.
- **Parameterized `validate-opts`** — `request` and `run-agent` both validate option keys, but used different code paths. Now one function with an optional key set argument.
- **`map->Response`** — replaced 6-field positional `->Response` construction. One swap in positional args silently breaks; named fields don't.
- **Single `status-errors` table** — `errors.clj` had `status->error-type` and `parse-http-error` both independently casing on HTTP status codes. Now one data table drives both.

### 2.3 Comments Pass

Added "why" comments at 10 sites where intent was non-obvious:

- `(dropping-buffer 1024)` — why dropping? why 1024? (slow consumers must not block promise delivery)
- `(catch ExceptionInfo e (throw e))` — looks like dead code (re-throws our errors, only catches parse failures)
- Unknown event type → `state` — forward-compat with new provider events
- `"required" → {:type "any"}` in Anthropic — different vocabulary, not a bug
- `(:throw false)` in net.cljc — errors handled via callback

Also: removed dead `::type :stream-error` key, fixed `description ""` default that existed only to be discarded.

### 2.4 Split `malli->json-schema` Dual Personality

At depth=0, `malli->json-schema` returned a **tool definition** `{:type "function" :function {...}}`. At depth>0, it returned a **JSON Schema** `{:type "object" ...}`. The function name promised JSON Schema but delivered tool wrappers.

**After:** `malli->json-schema` always returns plain JSON Schema. New `malli->tool-definition` wraps for tool calling. Backends call the one they need.

### 2.5 Flatten `request` Indentation

The `request` function had a nested `let` inside a `let`, with the `Response` construction at a confusing indentation level. Flattened into a single `let` block.

### 2.6 Decouple Structured Output from `consume-events`

`consume-events` knew about `structured-promise` and `schema` — it delivered errors to the structured promise on `:error` events and spawned a future for parsing.

**After:** `consume-events` only knows about text, tool-calls, usage, and channel fan-out. The structured-output future moved to `request`, where it belongs.

### 2.7 Extract `build-agent-step` Helper

The densest block in `run-agent` — 7 bindings, conditional message construction, history assembly — is now a named function. The loop body reads as: get response → check stop → build step → recur.

### 2.8 Rename `:schema` → `:output-schema`

The `:schema` option key shadowed the `schema` namespace alias and was ambiguous — it could mean a Malli schema, a JSON Schema, or the structured output option.

Renamed across the entire API surface: protocol, both backends, core.clj (option key, destructuring, docstrings), tests, scripts, sandbox, and README. Internal uses of `:schema` in error data and Malli metadata lookups are unchanged.

---

## Commit Log

| Commit | Description |
|--------|-------------|
| `9a5fe75` | Decomplect: pure state machine, uniform event contract, single agent path |
| `256ce54` | Naming pass: replace cryptic abbreviations with clear names |
| `7dedabd` | Consistency pass: unwrap!, unified validation, map->Response, error table |
| `b9b35e9` | Comments pass: add 'why' comments, remove dead code, fix description default |
| `98dbe35` | Split schema dual personality: malli->json-schema vs malli->tool-definition |
| `f264d52` | Fix request indentation: flatten nested let into single let block |
| `99086a3` | Decouple structured output from consume-events |
| `409aeb9` | Extract build-agent-step helper from run-agent loop |
| `497d04b` | Rename :schema option to :output-schema across the entire API |

## Stats

- **74 tests, 237 assertions, 0 failures**
- 16 source/test files touched in the readability phase
- 264 lines added, 225 removed (net +39)
- 1,452 lines of source across 11 files
