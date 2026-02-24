# P2: Anthropic tool_choice: null when "none"

## Problem

In `anthropic.clj` `build-body`, when `tool-choice` is `"none"`:

```clojure
(= tool-choice "none") nil
```

This puts `nil` as the value of `:tool_choice` in the tools-config map.
When merged into the body and serialized, it becomes `"tool_choice": null`
in JSON, which may cause Anthropic API errors.

## Location

`src/co/poyo/clj_llm/backends/anthropic.clj` — `build-body`

## Fix

Don't include `:tool_choice` in the map at all when `"none"`:

```clojure
(let [tool_choice (cond
                    (= tool-choice "auto") {:type "auto"}
                    (= tool-choice "required") {:type "any"}
                    (= tool-choice "none") ::omit
                    :else (or tool-choice {:type "auto"}))
      tools-config (cond-> {:tools (mapv ...)}
                     (not= ::omit tool_choice)
                     (assoc :tool_choice tool_choice))]
```

Or filter nils from the body before serialization.
