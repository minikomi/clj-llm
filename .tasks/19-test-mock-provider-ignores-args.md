# P2 (Tests): MockProvider ignores all arguments

## Problem

The `MockProvider` in `core_test.clj` discards all arguments passed to
`request-stream` (model, system-prompt, schema, tools, provider-opts).
No test verifies that the core layer forwards these correctly to the
provider.

## Location

`test/co/poyo/clj_llm/core_test.clj` — `MockProvider` record

## Fix

Record calls in an atom and assert on them:

```clojure
(defrecord MockProvider [responses defaults calls]
  proto/LLMProvider
  (request-stream [_ model system-prompt messages schema tools tool-choice provider-opts]
    (swap! calls conj {:model model
                       :system-prompt system-prompt
                       :messages messages
                       :schema schema
                       :tools tools
                       :tool-choice tool-choice
                       :provider-opts provider-opts})
    ;; ... existing channel logic ...))
```

Then add tests that verify:
- `:model` from defaults is forwarded
- `:system-prompt` is forwarded
- `:temperature`, `:max-tokens` arrive in `provider-opts`
- `:schema` is passed through
- Tool schemas are converted before forwarding
