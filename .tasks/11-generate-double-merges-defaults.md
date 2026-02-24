# P2: generate double-merges defaults

## Problem

`generate` does `(deep-merge (:defaults provider) opts)` to extract
tools, then passes `api-opts` (derived from raw `opts`, not `merged`)
to `request`. But `request` *also* merges defaults. So defaults are
merged twice.

This works accidentally because `api-opts` overrides the re-merged
defaults, but it's fragile and wasteful.

## Location

`src/co/poyo/clj_llm/core.clj` — `generate` function

## Fix

Either:
1. Have `generate` pass already-merged opts and use a lower-level
   internal request function that skips re-merging
2. Have `generate` only read from `opts` (not merged) and let `request`
   handle all merging
3. Extract a `request*` that takes pre-merged opts
