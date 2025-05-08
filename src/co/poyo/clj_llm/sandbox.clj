(ns co.poyo.clj-llm.sandbox
  (:require [co.poyo.clj-llm.core :as llm]
            [co.poyo.clj-llm.registry :as reg]
            [co.poyo.clj-llm.backends.openai :as openai]
            [co.poyo.clj-llm.stream :as stream]
            [clojure.core.async :as async :refer [chan go go-loop <! >! close! <!]]
            [malli.core :as m]
            [malli.util :as mu]
            [malli.transform :as mt]
            [cheshire.core :as json])

  )


(comment
  (openai/register-openai)
a
@(:usage (llm/prompt :openai/gpt-4.1-nano "hi there"))
)
