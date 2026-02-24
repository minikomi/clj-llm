# P1 (Tests): schema.clj has zero test coverage

## Problem

`schema.clj` is the most complex module (~106 lines, ~15 type branches,
recursive) and has zero dedicated tests. It's the most likely place for
bugs — and several have already been found (`:maybe`, comparison
operators, auto-generated names).

## Location

`src/co/poyo/clj_llm/schema.clj`

## Fix

Add `test/co/poyo/clj_llm/schema_test.clj` covering:

- Primitive types: `:string`, `:int`, `:double`, `:boolean`
- `:map` at depth 0 (wraps in function envelope) vs depth > 0 (plain object)
- `:vector`, `:sequential`, `:tuple`
- `:enum` with strings, numbers, mixed
- `:maybe` (currently broken)
- `:re` regex patterns
- `:uuid`
- Nested maps
- Map with `:name`/`:description` properties
- Auto-generated function name/description
- Optional vs required fields
- Comparison operators (currently broken)
