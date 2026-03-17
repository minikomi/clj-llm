(ns co.poyo.clj-llm.sandbox
  "REPL-friendly examples for clj-llm"
  (:require [clojure.core.async :as a]
            [co.poyo.clj-llm.core :as llm]
            [co.poyo.clj-llm.backend.openai :as openai]
            [cheshire.core :as json]
            [clojure.string :as str]))

(comment

  ;; ======================================
  ;; Setup
  ;; ======================================

  ;; Provider = connection
  (def provider (openai/backend))

  ;; Defaults = just data on the map
  (def ai (assoc provider :defaults {:model "gpt-4o-mini"}))

  ;; Layer more config with update+merge
  (def extractor (update ai :defaults merge
                        {:schema [:map [:name :string] [:age :int] [:occupation :string]]
                         :system-prompt "Extract structured data"}))

  ;; ======================================
  ;; Basic text -- returns a result map
  ;; ======================================

  (llm/generate ai "What is 2+2?")
  ;; => {:text "2+2 equals 4." :usage {:prompt-tokens 10 :completion-tokens 8}}

  (:text (llm/generate ai {:system-prompt "You are a poet"} "Write a haiku"))

  ;; ======================================
  ;; Structured output -- :structured key in result
  ;; ======================================

  (llm/generate ai {:schema [:map [:name :string] [:age :int] [:occupation :string]]}
                "Extract: Marie Curie was a 66 year old physicist")
  ;; => {:text "..." :structured {:name "Marie Curie" :age 66 :occupation "physicist"} :usage {...}}

  ;; Or use a pre-configured extractor
  (:structured (llm/generate extractor "Marie Curie was a 66 year old physicist"))
  ;; => {:name "Marie Curie" :age 66 :occupation "physicist"}

  ;; ======================================
  ;; Streaming -- :on-text callback
  ;; ======================================

  ;; Stream to terminal while getting the full result back
  (llm/generate ai
    {:on-text (fn [chunk] (print chunk) (flush))}
    "Tell me a story about a robot.")
  ;; prints chunks live, returns {:text "..." :usage {...}}

  ;; ======================================
  ;; Tool calling -- defns with Malli schemas
  ;; ======================================

  ;; Two tools that the LLM chains: geocode → get-weather
  ;; Powered by free Open-Meteo API — no key needed!

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
        (str "Unknown city: " city)
        {:name (:name loc) :country (:country loc)
         :latitude (:latitude loc) :longitude (:longitude loc)}))))

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
      (str (:temperature_2m c) "°C, wind " (:wind_speed_10m c) " km/h")))

  ;; They're regular functions -- test them directly
  (geocode {:city "Tokyo"})
  ;; => "{\"name\":\"Tokyo\",\"country\":\"Japan\",\"latitude\":35.6895,\"longitude\":139.69171}"

  (get-weather {:latitude 35.6895 :longitude 139.6917})
  ;; => "20.1°C, wind 7.6 km/h"

  ;; run-agent chains them: geocode → get-weather → answer
  (llm/run-agent ai [#'geocode #'get-weather] "What's the weather in Tokyo?")
  ;; => {:text "It's currently 20.1°C in Tokyo with light wind."
  ;;     :history [...]
  ;;     :steps [{:tool-calls [...] :tool-results [...]} ...]
  ;;     :usage {:prompt-tokens ... :completion-tokens ...}}

  ;; ======================================
  ;; Raw event stream
  ;; ======================================

  ;; events returns a core.async channel of event maps
  (let [ch (llm/events ai "Explain AI briefly")]
    (loop []
      (when-let [event (a/<!! ch)]
        (println (:type event) (dissoc event :type))
        (recur))))

  ;; ======================================
  ;; Composition -- results auto-unwrap
  ;; ======================================

  (->> "Raw technical document with some errors"
       (llm/generate ai {:system-prompt "Fix grammar"})
       (llm/generate ai {:system-prompt "Translate to French"}))

  ;; ======================================
  ;; Conversations -- history is just a vector
  ;; ======================================

  (def conversation
    (atom [{:role :system :content "You are a helpful coding assistant"}]))

  (defn chat! [msg]
    (swap! conversation conj {:role :user :content msg})
    (let [{:keys [text]} (llm/generate ai @conversation)]
      (swap! conversation conj {:role :assistant :content text})
      text))

  (chat! "How do I reverse a list in Clojure?")
  (chat! "What about in Python?") ;; remembers context

  ;; ======================================
  ;; Error handling
  ;; ======================================

  (try
    (llm/generate ai {:model "fake-model"} "test")
    (catch Exception e
      (println "Error:" (.getMessage e))
      (println "Data:" (ex-data e))))
