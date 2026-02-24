(ns co.poyo.clj-llm.protocol
  "Core protocol for LLM providers")

(defprotocol LLMProvider
  "Protocol that all LLM providers must implement"
  (request-stream [this model system-prompt messages output-schema tools tool-choice provider-opts]
    "Make a streaming request to the LLM provider.

     Arguments:
     - this: The provider instance
     - model: Model name string (required)
     - system-prompt: Optional system prompt string (or nil). Providers handle this differently:
                      - Anthropic: Uses top-level 'system' parameter
                      - OpenAI: Prepends as system role message
     - messages: Vector of message maps with :role and :content (no system messages)
     - output-schema: Optional malli schema for structured output (or nil)
     - tools: Optional vector of malli schemas for multi-tool calling (or nil)
     - tool-choice: Optional tool choice strategy: 'auto', 'required', 'none', or specific tool (or nil)
     - provider-opts: Map of provider-specific options (passed through unchanged to underlying API)"))
