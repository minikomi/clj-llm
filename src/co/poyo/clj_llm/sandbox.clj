(ns co.poyo.clj-llm.sandbox
  (:require [co.poyo.clj-llm.core :as llm]
            [co.poyo.clj-llm.registry :as reg]
            [co.poyo.clj-llm.backends.openai :as openai]
            [co.poyo.clj-llm.schema :as sch]
            [co.poyo.clj-llm.stream :as stream]
            [clojure.core.async :as async :refer [chan go go-loop <! >! close! <!]]
            [malli.core :as m]
            [malli.util :as mu]
            [malli.transform :as mt]
            [cheshire.core :as json])
  (:import [java.util UUID])
  )


(comment
  (openai/register-backend!)
  (malli.instrument/instrument!)
  @(:text (llm/prompt :openai/gpt-4.1-nano "hi there"))


  (def weather-schema
    [:map
     [:location {:description "The LARGE containing city name, e.g. Tokyo, San Francisco"} :string]
     [:unit {:description "Temperature unit (celsius or fahrenheit)"
             :optional false}
      [:enum "celsius" "fahrenheit"]]])

  (def weather-tool
    (sch/function-schema->openai-tool-spec
     "get_weather"
     "Get the current weather in a location"
     weather-schema))

  (m/validate
   weather-schema
   @(:structured-output
     (llm/prompt :openai/gpt-4.1-mini
                 "What's the weather like where The Eifel Tower is?"
                 :tools [weather-tool]
                 :tool-choice "auto")))

  @(:text (llm/prompt :openai/gpt-4.1-mini
                      "what is this picture of?"
                      {:attachments  [{:type :image
                                       :url "https://images.vexels.com/media/users/3/128011/isolated/lists/527067b3541bd657cae7ce720cc3d301-hand-drawn-sitting-cat.png"
                                       }]}))

  (reverse (:chunks (llm/prompt :openai/gpt-4.1-nano "hi there")))


  (do


    (defn transfer-money
      {:malli/schema transfer-money-schema}
      [{:keys [from to amount]}]
      {:transaction-id (UUID/randomUUID)
       :details {:from from
                 :to to
                 :amount amount}
       :status "completed"})
    (m/=>
     transfer-money
     [:->
      [:map [:from :string] [:to :string] [:amount :int]]
      [:map
       [:transaction-id :uuid]
       [:details
        [:map
         [:from :string]
         [:to :string]
         [:amount :int]]]
       [:status {:description "Transaction status"} :string]]]
     )

    (malli.instrument/collect!)

    (llm/call-function-with-llm
     transfer-money
     :openai/gpt-4.1-nano
     "Transfer $50 from my savings account to my checking account")

    )


  (m/function-schemas)
  )
