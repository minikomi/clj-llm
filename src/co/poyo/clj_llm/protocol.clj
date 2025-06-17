(ns co.poyo.clj-llm.protocol
  "Core protocol for LLM providers")

(defprotocol LLMProvider
  "Protocol that all LLM providers must implement"
  (request-stream [this model messages opts]
    "Make a request to the LLM provider.
     
     Args:
       model    - Model identifier string (e.g. \"gpt-4\", \"claude-3-opus-20240229\")
       messages - Vector of message maps with :role and :content
       opts     - Provider-specific options map
       
     Returns:
       Channel of events with format:
       {:type :content :content \"...\"}
       {:type :usage :prompt-tokens N :completion-tokens N :total-tokens N}
       {:type :error :error \"...\"}
       {:type :done}"))