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

;; Two tools that the LLM chains together:
;; 1. geocode: city name → lat/lng
;; 2. get-weather: lat/lng → current weather
;; No API key needed — powered by Open-Meteo.

(defn geocode
  {:malli/schema [:=> [:cat [:map {:name "geocode"
                                   :description "Look up latitude and longitude for a city"}
                             [:city {:description "City name"} :string]]]
                      [:map [:name :string] [:country :string]
                            [:latitude :double] [:longitude :double]]]}
  [{:keys [city]}]
  (let [geo (-> (slurp (str "https://geocoding-api.open-meteo.com/v1/search?name="
                            (java.net.URLEncoder/encode city "UTF-8") "&count=1"))
                (json/parse-string true))
        loc (first (:results geo))]
    (if-not loc
      (str "Could not find city: " city)
      {:name (:name loc) :country (:country loc)
       :latitude (:latitude loc) :longitude (:longitude loc)}))))

(def wmo-codes
  {0 "Clear sky" 1 "Mainly clear" 2 "Partly cloudy" 3 "Overcast"
   45 "Fog" 48 "Rime fog" 51 "Light drizzle" 53 "Drizzle" 55 "Dense drizzle"
   61 "Slight rain" 63 "Rain" 65 "Heavy rain" 71 "Slight snow" 73 "Snow"
   75 "Heavy snow" 80 "Slight showers" 81 "Showers" 82 "Violent showers"
   95 "Thunderstorm" 96 "Thunderstorm w/ hail" 99 "Thunderstorm w/ heavy hail"})

(defn get-weather
  {:malli/schema [:=> [:cat [:map {:name "get_weather"
                                   :description "Get current weather at a location. Call geocode first to get coordinates."}
                             [:latitude {:description "Latitude"} :double]
                             [:longitude {:description "Longitude"} :double]]]
                      :string]}
  [{:keys [latitude longitude]}]
  (let [wx (-> (slurp (str "https://api.open-meteo.com/v1/jma?latitude=" latitude
                           "&longitude=" longitude
                           "&current=temperature_2m,weather_code,wind_speed_10m"
                           "&timezone=auto"))
               (json/parse-string true))
        c  (:current wx)]
    (str (get wmo-codes (:weather_code c) "Unknown") ", "
         (:temperature_2m c) "°C, "
         "wind " (:wind_speed_10m c) " km/h")))

;; Tools are regular functions — test them directly
(println "Direct call:" (geocode {:city "Tokyo"}))
(println "Direct call:" (get-weather {:latitude 35.6895 :longitude 139.6917}))

;; run-agent chains tools automatically — geocode then get-weather
(println "\n--- agent chains geocode → get-weather ---")
(let [{:keys [text steps]} (llm/run-agent ai [#'geocode #'get-weather]
                             "What's the weather in Tokyo?")]
  (println "Steps:" (count steps))
  (doseq [{:keys [tool-calls]} steps]
    (doseq [tc tool-calls]
      (println "  Called:" (:name tc) (:arguments tc))))
  (println "Final:" text))

;; Multiple cities — the LLM can call geocode in parallel
(println "\n--- multi-city ---")
(let [{:keys [text steps]} (llm/run-agent ai [#'geocode #'get-weather]
                             "Compare weather in Tokyo and Paris right now")]
  (println "Steps:" (count steps))
  (doseq [{:keys [tool-calls]} steps]
    (doseq [tc tool-calls]
      (println "  Called:" (:name tc) (:arguments tc))))
  (println "Final:" text))

;; With options
(println "\n--- with max-steps ---")
(let [{:keys [text steps truncated]} (llm/run-agent ai [#'geocode #'get-weather]
                                       {:max-steps 3}
                                       "Weather in Tokyo?")]
  (println "Steps:" (count steps) (when truncated "(truncated)"))
  (println "Final:" text))
