# P0: OpenAI backend drops concurrent tool calls in a single chunk

## Problem

In `openai.clj` `data->internal-event`:

```clojure
(let [tool-call (first tool-calls)]
```

Only the first tool call in each SSE chunk is processed. The OpenAI API
can send multiple tool calls per delta chunk (multiple indices). Second+
tool calls are silently dropped.

## Location

`src/co/poyo/clj_llm/backends/openai.clj` — `data->internal-event`

## Fix

Return a vector of events (or emit multiple events) when `tool-calls`
contains more than one entry. The caller in `create-event-stream` needs
to handle the possibility of multiple events per SSE chunk — either
flatten before putting on the channel, or have `data->internal-event`
return a seq.
