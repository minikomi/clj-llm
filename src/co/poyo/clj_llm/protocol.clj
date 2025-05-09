(ns co.poyo.clj-llm.protocol
  "Protocol definitions for LLM backends")

(defprotocol LLMBackend
  "Protocol for LLM backends"
  (-prompt [this model-id prompt-str opts]
    "Legacy method - execute a prompt and return the result as text.
     Not used by the new interface but kept for compatibility.")
  (-stream [this model-id prompt-str opts]
    "Execute a prompt and return a map containing:
     :channel - core.async channel that emits text chunks
     :metadata - atom containing response metadata that gets updated during streaming")
  (-opts-schema [this model-id]
    "Return a Malli schema for backend-specific options")
  (-get-usage [this model-id metadata-atom]
    "Get token usage statistics from the metadata atom")
  (-get-raw-json [this model-id metadata-atom]
    "Get the raw JSON response from the metadata atom")
  (-get-structured-output [this model-id metadata-atom]
    "Get the structured output from the metadata atom"))
