# P1: Comparison schemas lose constraints

## Problem

In `schema.clj`, comparison operators just recurse into the child,
dropping the actual constraint:

```clojure
(:> :>= :< :<= := :not=)
(malli->json-schema (m/form (first (m/children compiled-schema))) (inc depth))
```

`[:> 0]` becomes `{"type": "integer"}` with no `minimum` or
`exclusiveMinimum`. The constraint is silently lost.

## Location

`src/co/poyo/clj_llm/schema.clj` — comparison operators case

## Fix

Map to proper JSON Schema constraints:

- `:>` → `{"exclusiveMinimum": value}`
- `:>=` → `{"minimum": value}`
- `:<` → `{"exclusiveMaximum": value}`
- `:<=` → `{"maximum": value}`
- `:=` → `{"const": value}`

The child of these schemas is the literal value, not a sub-schema.
You'll need to determine the base type (integer/number) from the value.
