(ns co.poyo.clj-llm.protocol
  "Core protocol for LLM providers")

(defprotocol LLMProvider
  "Protocol that all LLM providers must implement"
  (request-stream [this messages schema provider-opts]
    "Make a streaming request to the LLM provider.
     
     Arguments:
     - this: The provider instance
     - messages: Vector of message maps with :role and :content
     - provider-opts: Map of provider-specific options (passed through unchanged)"))
