#!/usr/bin/env bb

(require '[co.poyo.clj-llm.core :as llm]
         '[co.poyo.clj-llm.backends.openai :as openai]
         '[cheshire.core :as json])

;; Works with OPENAI_API_KEY or OPENROUTER_KEY
(def provider
  (let [openrouter-key (System/getenv "OPENROUTER_KEY")]
    (if openrouter-key
      (openai/backend {:api-key openrouter-key
                        :api-base "https://openrouter.ai/api/v1"})
      (openai/backend))))

(def ai (assoc provider :defaults {:model (or (System/getenv "LLM_MODEL") "gpt-4o-mini")}))

;; Tools are plain functions with standard Malli function schemas.
;; The :malli/schema on the var tells run-agent what the LLM sees.

(def wmo-codes
  {0 "Clear sky" 1 "Mainly clear" 2 "Partly cloudy" 3 "Overcast"
   45 "Fog" 48 "Rime fog" 51 "Light drizzle" 53 "Drizzle" 55 "Dense drizzle"
   61 "Slight rain" 63 "Rain" 65 "Heavy rain" 71 "Slight snow" 73 "Snow"
   75 "Heavy snow" 80 "Slight showers" 81 "Showers" 82 "Violent showers"
   95 "Thunderstorm" 96 "Thunderstorm w/ hail" 99 "Thunderstorm w/ heavy hail"})

(defn get-weather
  {:malli/schema [:=> [:cat [:map {:name "get_weather"
                                   :description "Get current weather for a city using Open-Meteo"}
                             [:city {:description "City name"} :string]]]
                      :string]}
  [{:keys [city]}]
  (let [geo  (-> (slurp (str "https://geocoding-api.open-meteo.com/v1/search?name=" (java.net.URLEncoder/encode city "UTF-8") "&count=1"))
                 (json/parse-string true))
        loc  (first (:results geo))]
    (if-not loc
      (str "Could not find city: " city)
      (let [wx (-> (slurp (str "https://api.open-meteo.com/v1/jma?latitude=" (:latitude loc)
                               "&longitude=" (:longitude loc)
                               "&current=temperature_2m,weather_code,wind_speed_10m"
                               "&timezone=auto"))
                   (json/parse-string true))
            current (:current wx)]
        (str (:name loc) ", " (:country loc) ": "
             (get wmo-codes (:weather_code current) "Unknown") ", "
             (:temperature_2m current) "°C, "
             "wind " (:wind_speed_10m current) " km/h")))))

(defn search-restaurants
  {:malli/schema [:=> [:cat [:map {:name "search_restaurants"
                                   :description "Search restaurants in a city"}
                             [:city {:description "City"} :string]
                             [:cuisine {:description "Cuisine type"} :string]]]
                      :string]}
  [{:keys [city cuisine]}]
  (str "Found: " cuisine " place in " city))

;; Tools are regular functions — test them directly
(println "Direct call:" (get-weather {:city "Tokyo"}))

;; run-agent reads schemas from var metadata, calls the fns when the model invokes them
(println "\n--- single tool agent ---")
(let [{:keys [text steps]} (llm/run-agent ai [#'get-weather] "What's the weather in Tokyo?")]
  (println "Steps:" (count steps))
  (doseq [{:keys [tool-calls]} steps]
    (doseq [tc tool-calls]
      (println "  Called:" (:name tc) (:arguments tc))))
  (println "Final:" text))

;; Multiple tools
(println "\n--- multi-tool agent ---")
(let [{:keys [text steps]} (llm/run-agent ai
                             [#'get-weather #'search-restaurants]
                             "Weather in Tokyo and find ramen there")]
  (println "Steps:" (count steps))
  (doseq [{:keys [tool-calls]} steps]
    (doseq [tc tool-calls]
      (println "  Called:" (:name tc) (:arguments tc))))
  (println "Final:" text))

;; With options
(println "\n--- with max-steps ---")
(let [{:keys [text steps truncated]} (llm/run-agent ai [#'get-weather]
                                       {:max-steps 2}
                                       "Weather in Tokyo?")]
  (println "Steps:" (count steps) (when truncated "(truncated)"))
  (println "Final:" text))
