(ns co.poyo.clj-llm.protocol
  "Protocol definition for LLM providers.")

(defprotocol Backend
  (-prompt           [this model-id prompt opts]
    "Execute a non-streaming prompt and return the result text.")

  (-stream           [this model-id prompt opts]
    "Execute a streaming prompt and return a Manifold stream that emits content chunks.")

  (-attachment-types [this model-id]
    "Return a set of MIME types supported as attachments for this model.")

  (-opts-schema      [this model-id]
    "Return a Malli schema for model-specific options, or nil."))
