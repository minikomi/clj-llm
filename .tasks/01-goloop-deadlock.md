# P0: Go-loop deadlock when events channel isn't drained

## Problem

In `consume-events` (core.clj), a single go-loop fans out to both
`text-chunks-chan` and `events-chan` via `>!`. If a user calls
`(stream ai "foo")` and only reads `:chunks` but never drains `:events`,
the go-loop parks once the 1024-event buffer fills. The `text-promise`
is never delivered. `@response` hangs forever.

The same deadlock occurs in reverse if someone reads `:events` but not
`:chunks`.

## Location

`src/co/poyo/clj_llm/core.clj` — `consume-events` function

## Fix options

1. Use `a/put!` (non-blocking, drops on full) instead of `>!` for the
   events channel
2. Use separate go-loops per output channel (fan-out via a shared
   intermediate channel)
3. Use sliding/dropping buffers for `events-chan`

Option 1 is simplest — events are supplementary and dropping them is
acceptable. Option 3 (dropping buffer) is also clean.
