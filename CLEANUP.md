# Cleanup Plan: Making clj-llm a Joy to Read

**Status: COMPLETE** — All items addressed. Two were reverted as over-engineering:
`:schema` → `:output-schema` (verbose for no gain) and `build-agent-step` extraction
(one-call-site function with made-up return keys).

Audit of all 1,468 lines of source across 11 files. Grouped by theme, ordered by impact.

---

## A. Naming (cheap, high readability payoff)

| Current | Problem | Proposed |
|---------|---------|----------|
| `tc` (core.clj, 6 uses) | Cryptic 2-letter binding for parsed tool calls | `tool-calls` |
| `:tc-index` in init-state | Abbreviation, unclear what it maps | `:tool-call-positions` |
| `merged` in `request` | Describes *how*, not *what* | `resolved-opts` |
| `base-opts` in `run-agent` | Base relative to what? | `request-opts` |
| `s`, `t` in `extract-input-schema` | Single-letter variables through 4 conditionals | `fn-schema`, `schema-type` |
| `has-name?` / `has-args?` (openai.clj) | Bound to the *value*, not a boolean | `fn-name` / `fn-args` |
| `cb` in `net.cljc` | Generic callback name | `on-response` |
| `bh` alias (both backends) | Non-obvious abbreviation | `backend` |
| `auto-generate-function-info` (schema.clj) | "Info" is vague | `infer-tool-metadata` |
| `event->internal` parameter in backend_helpers | Input isn't an "event" | `parse-sse-data` |
| `v1`/`v2` in `deep-merge` | No precedence semantics | `existing`/`override` |
| `known-keys` | Reads as "all known keys" but it's request-specific | `request-keys` |
| `schema` parameter everywhere | Shadows the `schema` ns alias | `output-schema` |

## B. Consistency fixes (same thing done the same way)

1. **Unify option validation.** `request` uses `validate-opts` with `known-keys`. `run-agent` does inline validation with `(into known-keys agent-keys)` and a different error shape. → Parameterize `validate-opts` to accept the key set.

2. **Use `errors/error` consistently.** Line 189 in core.clj creates a raw `(Exception. ...)` for `structured-promise`. Every other error uses `errors/error`. → Use `errors/error` everywhere.

3. **Extract `unwrap!` helper.** The "check-if-exception-and-throw" pattern appears 4× with slight variations (`deref`, `generate` 2×, `run-agent`). → `(defn- unwrap! [v] (if (instance? Exception v) (throw v) v))`.

4. **Use `map->Response` instead of positional `->Response`.** 6-field positional construction is error-prone. One swap and everything silently breaks.

5. **Eliminate duplicate status→message mapping in errors.clj.** `status->error-type` and `parse-http-error` both `case` on the same status codes independently. → Single data table.

## C. Missing "why" comments (cheap, prevents future confusion)

| Location | What it does | Why it needs a comment |
|----------|-------------|------------------------|
| `(catch ExceptionInfo e (throw e))` in parse-structured-output | Re-throw our errors | Looks like dead code without context |
| `(dropping-buffer 1024)` in request | Dropping buffer for chunks/events | Why dropping? Why 1024? |
| Unknown event type → `state` in next-state | Ignore unknown events | Forward-compat with new provider events |
| `::unparsed` path in backend_helpers | Silently skips unparsed SSE data | Why is this safe? What data comes through? |
| `(Duration/ofSeconds 30)` in net.cljc | HTTP request timeout | Does this cover the full stream or just connect? Latent bug? |
| `"required" → {:type "any"}` in anthropic.clj | Anthropic uses different vocabulary | Without this, readers think it's a bug |
| `(:throw false)` in net.cljc bb branch | Suppress HTTP exceptions | Errors handled via callback |
| `event:` lines ignored in sse.clj | Type is in the JSON payload | Provider-specific design choice |
| Anthropic reads `System/getProperty` too | OpenAI doesn't | Intentional or oversight? |

## D. Structural improvements (higher effort, high clarity payoff)

### D1. Split `malli->json-schema` dual personality

At depth=0, it returns a **tool definition** `{:type "function" :function {...}}`. At depth>0, it returns a **JSON Schema** `{:type "object" ...}`. The function name promises JSON Schema but delivers tool wrappers. This is the single most misleading thing in the codebase.

→ Split into `malli->json-schema` (pure JSON Schema at any depth) and `malli->tool-definition` (wraps for tool calling). Backends call the one they need.

### D2. Decouple structured output from `consume-events`

`consume-events` knows about `structured-promise` and `schema` — it delivers errors to the structured promise on `:error` events, and spawns a future for parsing. This couples the event consumer to a feature.

→ Move the structured-output future to `request`. `consume-events` only knows about text, tool-calls, usage, and events.

### D3. Extract `run-agent` step-building

The `let` block inside the loop (7 bindings, conditional message construction, history assembly) is the densest code in the file.

→ Extract `build-agent-step` helper: `(tc, text, name->fn) → {:msg :result-msgs :step-record}`.

### D4. Fix `request` indentation / let nesting

The inner `let` at line 237 is indented incorrectly relative to the outer `let` at line 232. The `(->Response ...)` appears at a different indentation level from the `let` that binds its arguments. This looks like a formatting bug.

### D5. Clean up `create-event-stream` inner loop

The inner `loop` for iterating events from a single SSE chunk exists because we need to stop the outer SSE loop when a `:done` event arrives mid-batch. This is non-obvious. The `let [done? (loop ...)]` pattern works but deserves a comment and possibly extraction.

## E. Dead code / unnecessary complexity

1. **`::type :stream-error` in sse.clj** — Written to the error map but never read by any consumer. Remove.
2. **`retry-after` in errors.clj** — Reads `[:error :retry_after]` from body, but `Retry-After` is an HTTP *header*. This function likely never returns a value in practice. Investigate or remove.
3. **`helpers.clj` as a namespace** — Contains exactly one function (`deep-merge`), called from one place. Either inline it or rename to `util`.
4. **`description ""` default in schema.clj** — `(get props :description "")` followed by `(not (str/blank? description))`. The default exists only to be discarded.

---

## Suggested commit order

1. **Naming pass** — all renames in one commit (A)
2. **Consistency pass** — unwrap!, validate-opts, errors/error, map->Response (B)
3. **Comments pass** — all "why" comments (C)
4. **Split schema dual personality** (D1)
5. **Decouple structured output** (D2)
6. **Extract agent step-building** (D3)
7. **Fix formatting + dead code** (D4, D5, E)
