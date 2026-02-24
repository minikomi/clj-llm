# P2: System/currentTimeMillis called twice in :usage handler

## Problem

In `consume-events`, the `:usage` event handler calls
`System/currentTimeMillis` twice:

```clojure
:clj-llm/req-end (System/currentTimeMillis)
:clj-llm/duration (- (System/currentTimeMillis) req-start)
```

The two calls can return different values, making `:req-end` and
`:duration` inconsistent.

## Location

`src/co/poyo/clj_llm/core.clj` — `consume-events`, `:usage` case

## Fix

Capture once in a `let`:

```clojure
:usage
(let [now (System/currentTimeMillis)]
  (deliver usage-promise
           (assoc event
                  :clj-llm/provider-opts provider-opts
                  :clj-llm/req-start req-start
                  :clj-llm/req-end now
                  :clj-llm/duration (- now req-start)))
  (recur chunks tool-calls tc-index))
```
