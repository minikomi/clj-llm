(ns co.poyo.clj-llm.protocol
  "Core protocol for LLM providers")

(defprotocol LLMProvider
  "Protocol that all LLM providers must implement"
  (request-stream [this request]
    "Make a streaming request to the LLM provider.

     request is a map with keys:
       :model          - model name string (required)
       :system-prompt   - system prompt string (or nil)
       :messages        - vector of message maps with :role and :content
       :schema          - malli schema for structured output (or nil)
       :tools           - vector of malli tool schemas (or nil)
       :tool-choice     - tool choice strategy (or nil)
       :provider-opts   - map of provider-specific options

     Returns a blocking reducible (IReduceInit) of events."))
