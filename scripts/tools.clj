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

;; Tools are functions with schema metadata.
;; llm/tool attaches a Malli schema to a function.

(def get-weather
  (llm/tool
    [:map {:name "get_weather" :description "Get weather for a city"}
     [:city {:description "City name"} :string]]
    (fn [{:keys [city]}]
      (str "Sunny, 22°C in " city))))

(def search-restaurants
  (llm/tool
    [:map {:name "search_restaurants" :description "Search restaurants in a city"}
     [:city {:description "City"} :string]
     [:cuisine {:description "Cuisine type"} :string]]
    (fn [{:keys [city cuisine]}]
      (str "Found: " cuisine " place in " city))))

;; Tools are regular functions — test them directly
(println "Direct call:" (get-weather {:city "Tokyo"}))

;; run-agent reads schemas from metadata, calls the fns when the model invokes them
(println "\n--- single tool agent ---")
(let [{:keys [text steps]} (llm/run-agent ai [get-weather] "What's the weather in Tokyo?")]
  (println "Steps:" (count steps))
  (doseq [{:keys [tool-calls]} steps]
    (doseq [tc tool-calls]
      (println "  Called:" (:name tc) (:arguments tc))))
  (println "Final:" text))

;; Multiple tools
(println "\n--- multi-tool agent ---")
(let [{:keys [text steps]} (llm/run-agent ai
                             [get-weather search-restaurants]
                             "Weather in Tokyo and find ramen there")]
  (println "Steps:" (count steps))
  (doseq [{:keys [tool-calls]} steps]
    (doseq [tc tool-calls]
      (println "  Called:" (:name tc) (:arguments tc))))
  (println "Final:" text))

;; With options
(println "\n--- with max-steps ---")
(let [{:keys [text steps truncated]} (llm/run-agent ai [get-weather]
                                       {:max-steps 2}
                                       "Weather in Tokyo?")]
  (println "Steps:" (count steps) (when truncated "(truncated)"))
  (println "Final:" text))
