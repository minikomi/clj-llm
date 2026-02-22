#!/usr/bin/env bb

(require '[co.poyo.clj-llm.core :as llm]
         '[co.poyo.clj-llm.backends.openai :as openai])

;; Works with OPENAI_API_KEY or OPENROUTER_KEY
(def provider
  (let [openrouter-key (System/getenv "OPENROUTER_KEY")]
    (if openrouter-key
      (openai/->openai {:api-key openrouter-key
                        :api-base "https://openrouter.ai/api/v1"})
      (openai/->openai))))

(def ai (assoc provider :defaults {:model (or (System/getenv "LLM_MODEL") "gpt-4o-mini")}))

;; Define tools as Malli schemas
(def get-weather
  [:map {:name "get_weather" :description "Get weather for a city"}
   [:city {:description "City name"} :string]])

(def search-restaurants
  [:map {:name "search_restaurants" :description "Search restaurants in a city"}
   [:city {:description "City"} :string]
   [:cuisine {:description "Cuisine type"} :string]])

;; Single tool call — returns a vector of tool calls
(println "--- single tool ---")
(let [result (llm/generate ai {:tools [get-weather]} "What's the weather in Tokyo?")]
  (println result))

;; Multiple tools — model picks which to call
(println "\n--- multiple tools ---")
(let [result (llm/generate ai {:tools [get-weather search-restaurants]}
                           "Weather in Paris and find Italian restaurants there")]
  (println result))

;; Agentic loop — call tools and feed results back
(println "\n--- agentic loop ---")
(defn execute-tool [{:keys [name arguments]}]
  (case name
    "get_weather"        (str "Sunny, 22°C in " (:city arguments))
    "search_restaurants" (str "Found: " (:cuisine arguments) " place in " (:city arguments))
    (str "Unknown tool: " name)))

;; Using run-agent for the full loop
(let [{:keys [text steps]} (llm/run-agent ai {:tools [get-weather search-restaurants]
                       :execute execute-tool}
                   "Weather in Tokyo and find ramen there")]
  (println "Steps:" (count steps))
  (doseq [{:keys [tool-calls tool-results]} steps]
    (doseq [tc tool-calls]
      (println "  Called:" (:name tc) (:arguments tc))))
  (println "Final:" text))
