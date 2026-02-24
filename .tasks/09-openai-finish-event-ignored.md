# P1: OpenAI :finish event silently ignored

## Problem

OpenAI emits `{:type :finish :reason "stop"}` (or `"length"` for
truncation) but `consume-events` in core.clj doesn't handle `:finish` —
it falls through to the default `recur`. The finish reason is never
surfaced to users.

This means users can't detect when a response was truncated due to
`max_tokens` being hit.

## Location

`src/co/poyo/clj_llm/core.clj` — `consume-events` go-loop
`src/co/poyo/clj_llm/backends/openai.clj` — emits `{:type :finish}`

## Fix

Either:
1. Store the finish reason and expose it on the Response record
2. Map `:finish` to `:done` in the OpenAI backend (losing the reason)
3. Handle `:finish` in `consume-events` — store reason, continue loop

Option 1 is most useful. Add a `:finish-reason` promise to Response.
