# P1: run-agent infinite loop when model returns no tools but stop-when returns false

## Problem

If a user provides a custom `stop-when` that doesn't check for empty
tool-calls, and the model returns pure text (no tool calls), the code
proceeds to `tool-calls->assistant-message` with nil/empty tool-calls,
then loops with identical history until `max-steps`. Burns API budget
retrying the same request.

## Location

`src/co/poyo/clj_llm/core.clj` — `run-agent` loop body

## Fix

After checking `stop?`, also check if `tc` is empty. If the model
returned no tool calls and `stop-when` didn't fire, the loop should
still terminate (there's nothing to execute):

```clojure
(if (or stop? (empty? (or tc [])))
  ;; return result
  ...)
```

Or: always stop when there are no tool calls, regardless of `stop-when`.
The `stop-when` predicate is for *early* stopping (before executing
tools), not for *continuing* when there's nothing to do.
