(ns co.poyo.clj-llm.protocol
  "Core protocol for LLM providers")

(defprotocol LLMProvider
  "Core protocol for LLM providers - behavior only"
  (api-key [this] "Get API key (may be nil)")
  (build-url [this model] "Construct full request URL")
  (build-headers [this] "Construct all request headers")
  (build-body [this model system-prompt messages schema tools tool-choice provider-opts]
    "Construct request body as a map")
  (parse-chunk [this chunk schema tools]
    "Return vector of events (may be empty)")
  (stream-events [this url headers body]
    "Open an event stream from URL with headers and body.
     Returns a core.async channel of raw chunk maps."))