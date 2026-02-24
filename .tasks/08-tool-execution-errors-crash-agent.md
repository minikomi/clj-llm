# P1: Tool execution errors crash the agent

## Problem

In both `generate` and `run-agent`, if a tool function throws an
exception, the entire operation crashes. Idiomatic agent loops should
catch tool errors and feed them back to the model as error messages
so it can retry or adjust its approach.

## Location

`src/co/poyo/clj_llm/core.clj` — `execute-tool-call`, called from
`generate` and `run-agent`

## Fix

Wrap tool execution in try/catch and return error information:

```clojure
;; In run-agent's loop:
(let [results (mapv (fn [t]
                      (try
                        {:call t :result (execute-tool-call name->fn t)}
                        (catch Exception e
                          {:call t :result (str "Error: " (.getMessage e)) :error e})))
                    tc)]
  ;; feed error messages back to model as tool results
  ...)
```

For `generate` (single-shot), crashing may be acceptable — but
`run-agent` should definitely feed errors back to the model.
