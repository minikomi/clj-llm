# P1 (Tests): Anthropic backend has zero test coverage

## Problem

The Anthropic backend (`anthropic.clj`) has no tests at all — no event
parsing fixtures, no `build-body` tests, no `data->internal-event` tests.
Anthropic has a completely different SSE format (`content_block_delta`,
`message_stop`, etc.) from OpenAI.

## Location

`src/co/poyo/clj_llm/backends/anthropic.clj`

## Fix

1. Capture real Anthropic SSE fixtures (simple text, tool calls,
   structured output)
2. Add event parsing tests similar to the OpenAI fixture tests
3. Test `build-body` — especially system prompt handling (top-level
   `:system` key) and tool_choice mapping
4. Test the `message_start` → `message_delta` usage aggregation
