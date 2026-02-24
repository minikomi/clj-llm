# P1 (Tests): event_parsing_test duplicates real converter logic

## Problem

`event_parsing_test.clj` contains a hand-copied `openai-event` function
that duplicates `openai/data->internal-event`. Same for
`assemble-tool-calls` duplicating `consume-events` assembly logic.

If the real implementation changes, this copy silently diverges and
tests pass against stale logic.

The comment says "inline to avoid coupling to backend ns" but this
creates a worse problem: silent divergence.

## Location

`test/co/poyo/clj_llm/event_parsing_test.clj`

## Fix

Import and test the real functions. The backend functions are private,
so either:
1. Make them package-private / test-accessible via `#'ns/fn`
2. Use `(var openai/data->internal-event)` to access privates
3. Promote them to public API (they're stable enough)
