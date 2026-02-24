# P1: structured-promise never delivered when no schema

## Problem

In `consume-events`, if no `:schema` is provided, `structured-promise`
is never delivered. Any code that derefs `@(:structured response)`
without a schema will block forever.

## Location

`src/co/poyo/clj_llm/core.clj` — `consume-events`, `finalize!` function

## Fix

Deliver `nil` to `structured-promise` in `finalize!` when no schema:

```clojure
(when-not (realized? structured-promise)
  (deliver structured-promise nil))
```

Or deliver in `finalize!` unconditionally (deliver is a no-op on
already-delivered promises).
