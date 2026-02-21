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
(let [result (llm/generate ai "What's the weather in Tokyo?"
                           {:tools [get-weather]})]
  (println (:tool-calls result)))

;; Multiple tools — model picks which to call
(println "\n--- multiple tools ---")
(let [result (llm/generate ai "Weather in Paris and find Italian restaurants there"
                           {:tools [get-weather search-restaurants]})]
  (println (:tool-calls result)))

;; Agentic loop — call tools and feed results back
(println "\n--- agentic loop ---")
(defn execute-tool [{:keys [id name arguments]}]
  (llm/tool-result id
    (case name
      "get_weather"        (str "Sunny, 22°C in " (:city arguments))
      "search_restaurants" (str "Found: " (:cuisine arguments) " place in " (:city arguments))
      (str "Unknown tool: " name))))

(let [question "Weather in Tokyo and find ramen there"
      {:keys [tool-calls message]} (llm/generate ai question {:tools [get-weather search-restaurants]})
      tool-results (mapv execute-tool tool-calls)
      ;; Feed results back — :message is pre-formatted for history
      history (into [{:role :user :content question} message] tool-results)]
  (println (:text (llm/generate ai nil {:message-history history}))))
