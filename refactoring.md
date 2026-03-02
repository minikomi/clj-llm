# Refactoring Notes

## What we did (inside-out)

Started at 1485 lines across 10 files. Now 1315 lines across 8 files.

| File | Before | After | Change |
|------|--------|-------|--------|
| helpers.clj | 12 | 0 | deleted — `deep-merge` was `merge` |
| errors.clj | 56 | 0 | deleted — was wrapping `ex-info` |
| sse.clj | 78 | 21 | pure `parse-line` fn, no async/IO |
| net.cljc | 50 | 44 | synchronous blocking POST, no callback |
| protocol.clj | 19 | 18 | single map arg instead of 8 positional |
| backend_helpers.clj | 125 | 115 | absorbed SSE streaming, flattened nesting |
| anthropic.clj | 156 | 138 | simpler constructor |
| openai.clj | 146 | 129 | simpler constructor |
| schema.clj | 126 | 127 | data table for simple types |
| core.clj | 614 | 614 | untouched so far |

### Key simplifications

1. **errors.clj deleted** — `(errors/error msg data)` was just `(ex-info msg data)` with a default `:error-type`. Callers already set `:error-type`. Two accessor fns (`error-type`, `retry-after`) were `(:k (ex-data e))`. Status table + `parse-http-error` moved to backend_helpers where it's actually used.

2. **helpers.clj deleted** — single fn (`deep-merge`), used once, on a flat map. `merge` is sufficient.

3. **sse.clj is now pure parsing** — `parse-line` takes a string, returns `{:data m}`, `{:done true}`, or `nil`. No channels, no IO, no async. backend_helpers owns the InputStream reading and channel writing.

4. **net.cljc is synchronous** — `post-stream` blocks and returns `{:status :body :error}`. No callback, no thread spawning. Caller threads it. Eliminated the `#?(:bb future :clj a/thread)` platform split — both paths are just try/catch now.

5. **Protocol takes a map** — `request-stream [this request]` instead of 8 positional args. Data over position.

6. **Backend constructors use destructuring** — removed unknown-key validation (open maps).

## What we complected

**backend_helpers.clj is accumulating concerns.** It now does:
- HTTP error classification (status table + parse-http-error)
- Message key normalization (kebab → snake)
- Option key conversion (kebab → snake)
- SSE stream reading (IO + line parsing)
- Channel management (future + chan + close!)
- Event conversion dispatch

That's too many things. The SSE streaming + channel management could be its own thing, or the backends could just own it directly since `create-event-stream` is the only fn they call.

## What's left

### schema.clj
- `malli->json-schema` recursive fn is fine but verbose
- `malli->tool-definition` auto-generates names from field names — clever but magic
- Consider: is the auto-name inference worth the complexity?

### backend_helpers.clj
- Split concerns or push `create-event-stream` into the backends
- `convert-options-for-api` does a full postwalk — heavy for renaming top-level keys
- `normalize-messages` renames 2 keys — could just be inline

### anthropic.clj / openai.clj
- `build-body` fns are similar but not identical — fine, providers differ
- `data->internal-events` is the real complexity — nested cond/case for each SSE chunk type
- Both backends are ~130 lines, reasonable

### core.clj (614 lines — the big one)
- **`parse-opts`** — malli closed-map validation + provider-opts extraction in one fn. The closed-map schema fights open-map philosophy. Consider: just destructure what you need, pass the rest through.
- **`Response` record** — 6 fields, all promises/channels. The IDeref impl is nice but the record adds ceremony. Could be a plain map.
- **`consume-events`** — go-loop with pure `next-state` reducer is clean. Keep.
- **`resolve-tool-schema`** — checks 3 places for malli schema (metadata `:malli/schema`, metadata `:schema`, global registry). Is the 3-way lookup worth the complexity?
- **`extract-input-schema`** — handles `:=>` and `:->` syntax normalization. Necessary complexity.
- **`generate`** — clean, but the tools path returns a different shape (map vs string). Mixed return types.
- **`run-agent`** — 60-line let-heavy loop. Could extract `execute-step` to separate "run one iteration" from "loop control".
- **`stream`, `stream-print`, `events`** — thin wrappers, fine.

## Style principles

### Flat early-exit with `cond`
Prefer top-level `cond` over nested `when`/`let`/`if`. Each branch is an early exit — reads top to bottom.

```clojure
;; yes
(cond
  (not (str/starts-with? line "data:")) nil
  (str/ends-with? line "[DONE]")        {:done true}
  :else (try ...))

;; no
(when (str/starts-with? line "data:")
  (let [raw ...]
    (if (= raw "[DONE]") {:done true} ...)))
```

### `let` inside `try`, name each step
When work can fail, put the `let` inside the `try` so all fallible steps are covered by one catch. Name intermediate values — don't nest calls.

```clojure
;; yes
(try
  (let [raw (str/trim (subs line 5))
        parsed (json/parse-string raw)]
    {:data (transform parsed)})
  (catch Exception _ nil))

;; no
(let [raw (str/trim (subs line 5))]
  (try {:data (transform (json/parse-string raw))}
       (catch Exception _ nil)))
```

### Priority order for core.clj
1. Simplify `parse-opts` — stop fighting open maps
2. Extract agent step logic from `run-agent` loop
3. Consider if `Response` record is worth it vs plain map
4. Trim tool schema resolution (do we need 3 lookup paths?)

---

## Phase 3: Streaming Architecture (channel elimination)

The streaming pipeline had three async hops with channels threading through
every layer. Collapsed it to: blocking reduce all the way down, one daemon
thread at the top.

### The problem

```
Before: future → chan(1024) → go-loop → chan(1024) → go-loop → chan/promises
         sse.clj              backend             core.clj
```

Three channels, two go-loops, one `future`. The `future` used
`Agent/soloExecutor` (non-daemon threads, 60s keep-alive), so
`clj -M scripts/streaming.clj` would hang for 60s after completing.
Babashka was fine because it exits on main thread completion regardless.

### What changed

**sse.clj: 78 → 24 lines.** Now exports a single transducer `sse/xf`
(SSE lines → parsed data maps). No IO, no channels, no HTTP. Just:

```clojure
(def xf
  (comp
    (remove #(str/ends-with? % "[DONE]"))
    (keep parse-sse-line)))
```

**Backends: pure transducer composition.** Each backend composes `sse/xf`
with its own event transform, then wraps in a `reify IReduceInit` that
owns the HTTP lifecycle (`with-open`, error handling). No channels, no
threads, no core.async dependency.

```clojure
;; openai.clj — the entire request-stream impl
(let [xf (comp sse/xf (mapcat #(data->events % schema tools)))]
  (reify clojure.lang.IReduceInit
    (reduce [_ f init]
      (with-open [reader (io/reader body)]
        (transduce xf f init (line-seq reader))))))
```

OpenAI uses `mapcat` (one SSE chunk can carry multiple tool-call entries).
Anthropic uses `keep` (always 1:1).

**core.clj: one daemon thread.** `request` spawns a single daemon thread
that does a blocking `reduce` over the provider's reducible. The reducing
function updates state, pushes to dropping-buffer channels, and delivers
promises when done. No go-loop.

```clojure
(util/run-daemon!
  (fn []
    (let [state (reduce step-fn init-state event-source)]
      (finalize! state))))
```

Daemon threads (vs `future`) so the JVM exits immediately when the main
thread finishes.

**util.clj: 8 lines.** Just `run-daemon!` — shared by core.clj and
available for future use.

### After

```
After: daemon thread → blocking reduce → backend reducible → sse/xf → HTTP IO
       core.clj        core.clj          backend (pure)     sse (pure)
```

One thread, zero intermediate channels, zero go-loops. Backends are pure
(no async, no IO management). SSE is a transducer definition.

### Line counts

| File | Before | After | Change |
|------|--------|-------|--------|
| sse.clj | 78 | 24 | -54 (transducer only) |
| net.cljc | 42 | 42 | unchanged |
| util.clj | — | 8 | new (run-daemon!) |
| openai.clj | 129 | 149 | +20 (owns HTTP lifecycle) |
| anthropic.clj | 138 | 161 | +23 (owns HTTP lifecycle) |
| core.clj | 614 | 614 | ~0 (reduce instead of go-loop) |
| protocol.clj | 18 | 18 | updated docstring |
| **Total** | **1452** | **1296** | **-156** |

72 tests, 216 assertions, 0 failures.
