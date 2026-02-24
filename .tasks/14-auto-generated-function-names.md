# P2: auto-generate-function-info produces potentially invalid names

## Problem

In `schema.clj`, `auto-generate-function-info` does:

```clojure
(str "extract_" (str/join "_" field-names))
```

For `[:map [:full-name :string] [:age :int]]` this produces
`"extract_full-name_age"` — hyphens in function names may be rejected
by some LLM APIs. Also, very long schemas produce very long names.

## Location

`src/co/poyo/clj_llm/schema.clj` — `auto-generate-function-info`

## Fix

Sanitize: replace non-alphanumeric chars with `_`, truncate to 64 chars:

```clojure
(-> (str "extract_" (str/join "_" field-names))
    (str/replace #"[^a-zA-Z0-9_]" "_")
    (subs 0 (min 64 (count ...))))
```
