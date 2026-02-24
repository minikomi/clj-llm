# P1: :maybe schema loses nullability

## Problem

In `schema.clj`, `malli->json-schema` handles `:maybe` by just
unwrapping and converting the inner type:

```clojure
:maybe (malli->json-schema (m/form (first (m/children compiled-schema))) (inc depth))
```

`[:maybe :string]` produces `{"type": "string"}` instead of the correct
JSON Schema representation.

## Location

`src/co/poyo/clj_llm/schema.clj` — `:maybe` case in `malli->json-schema`

## Fix

Produce a proper nullable JSON Schema:

```clojure
:maybe
(let [inner (malli->json-schema (m/form (first (m/children compiled-schema))) (inc depth))]
  (assoc inner :nullable true))  ;; OpenAPI style, widely supported
;; OR
  {"oneOf" [{inner} {"type" "null"}]}  ;; strict JSON Schema
```

Note: many LLM APIs support `"nullable": true` on a type. Check what
OpenAI and Anthropic actually accept.
