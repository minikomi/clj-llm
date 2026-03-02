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

### Priority order for core.clj
1. Simplify `parse-opts` — stop fighting open maps
2. Extract agent step logic from `run-agent` loop
3. Consider if `Response` record is worth it vs plain map
4. Trim tool schema resolution (do we need 3 lookup paths?)
