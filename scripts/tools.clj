#!/usr/bin/env bb

(require '[co.poyo.clj-llm.core :as llm]
         '[co.poyo.clj-llm.backends.openai :as openai])

;; Works with OPENAI_API_KEY or OPENROUTER_KEY
(def ai
  (let [openrouter-key (System/getenv "OPENROUTER_KEY")]
    (-> (if openrouter-key
          (openai/->openai {:api-key openrouter-key
                            :api-base "https://openrouter.ai/api/v1"})
          (openai/->openai))
        (llm/with-defaults {:model (or (System/getenv "LLM_MODEL") "gpt-4o-mini")}))))

;; Define tools as Malli schemas
(def get-weather
  [:map {:name "get_weather" :description "Get weather for a city"}
   [:city {:description "City name"} :string]])

(def search-restaurants
  [:map {:name "search_restaurants" :description "Search restaurants in a city"}
   [:city {:description "City"} :string]
   [:cuisine {:description "Cuisine type"} :string]])

;; Single tool call
(println "--- single tool ---")
(println (llm/generate ai "What's the weather in Tokyo?"
                       {:tools [get-weather]}))

;; Multiple tools — model picks which to call
(println "\n--- multiple tools ---")
(println (llm/generate ai "Weather in Paris and find Italian restaurants there"
                       {:tools [get-weather search-restaurants]}))

;; Agentic loop — call tools and feed results back
(println "\n--- agentic loop ---")
(defn fake-weather [city] (str "Sunny, 22°C in " city))
(defn fake-restaurants [city cuisine] (str "Found: " cuisine " place in " city))

(defn run-tools [tool-calls]
  (mapv (fn [{:keys [id name arguments]}]
          {:role :tool
           :tool_call_id id
           :content (case name
                      "get_weather" (fake-weather (:city arguments))
                      "search_restaurants" (fake-restaurants (:city arguments) (:cuisine arguments))
                      (str "Unknown tool: " name))})
        tool-calls))

(let [question "Weather in Tokyo and find ramen there"
      tool-calls (llm/generate ai question {:tools [get-weather search-restaurants]})
      tool-results (run-tools tool-calls)
      ;; Feed results back for final answer
      history (into [{:role :user :content question}
                     {:role :assistant :tool_calls
                      (mapv (fn [tc] {:id (:id tc) :type "function"
                                       :function {:name (:name tc)
                                                  :arguments (pr-str (:arguments tc))}}) tool-calls)}]
                    tool-results)]
  (println (llm/generate ai nil {:message-history history})))
