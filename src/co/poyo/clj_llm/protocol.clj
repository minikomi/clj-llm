(ns co.poyo.clj-llm.protocol)

(defprotocol LLMBackend
  (-raw-stream [this model-id prompt-str opts])
  (-opts-schema [this model-id]))
