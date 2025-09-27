(ns co.poyo.clj-llm.protocol
  "Core protocol for LLM providers")

(defprotocol LLMProvider
  "Protocol that all LLM providers must implement"
  (request-stream [this
                   model
                   messages
                   schema
                   api-opts]
    "Make a streaming request to the LLM provider"))
