# P0: Anthropic input_tokens never captured

## Problem

Anthropic sends input token count in the `message_start` event, but the
backend returns `nil` for it. Only `message_delta` usage (output tokens)
is captured. So `:usage` is incomplete for Anthropic — `input_tokens`
is always missing.

## Location

`src/co/poyo/clj_llm/backends/anthropic.clj` — `data->internal-event`

The `"message_start"` case currently returns `nil`.

## Fix

Extract usage from `message_start`:

```clojure
"message_start"
(when-let [usage (get-in data [:message :usage])]
  (into {:type :usage} usage))
```

You may also need to merge the two usage events (input from
`message_start`, output from `message_delta`) in the consumer, or
emit them as separate `:usage` events and let `consume-events` merge.
Currently `consume-events` delivers the first `:usage` and ignores
subsequent ones.
