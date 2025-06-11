#!/usr/bin/env bb
;;
;; Stream canonical LLM events (content / tool-call chunks / usage)
;; from the new OpenAI backend to stdout in real-time.
;;
(require '[co.poyo.clj-llm.core  :as llm]
         '[co.poyo.clj-llm.backends.openai :as openai]
         '[clojure.core.async   :refer [<!!]]
         '[clojure.edn          :as edn])

(openai/register)

(defn usage []
  (println "usage: openai_stream.clj \"your prompt here\"")
  (System/exit 1))

;; ─────────── config ───────────
(when-not (System/getenv "OPENAI_API_KEY")
  (binding [*out* *err*]
    (println "OPENAI_API_KEY env var is missing") (usage)))

;; ─────────── run with structured output ───────────
;; using malli schema

(defn run-with-structured-output [prompt schema]
  (let [model-id :openai/gpt-4.1-turbo
        opts {:schema schema}
        resp (llm/prompt model-id prompt opts)]
    (println "Structured output:" (edn/read-string @(:structured-output resp)))))

(require '[malli.core :as m])


(def weather-schema
    [:and
     {:name "weather-fn"
      :description "gets the weather for a given location"}
     [:map
      [:location {:description "The LARGE containing city name, e.g. Tokyo, San Francisco"} :string]
      [:unit {:description "Temperature unit (celsius or fahrenheit)"
              :optional false}
       [:enum "celsius" "fahrenheit"]]]])

  (m/form (m/schema weather-schema))

  (m/type [:map
           [:location {:description "The LARGE containing city name, e.g. Tokyo, San Francisco"} :string]
           [:unit {:description "Temperature unit (celsius or fahrenheit)"
                   :optional false}
            [:enum "celsius" "fahrenheit"]]])

(prn @(:structured-output
   (llm/prompt :openai/gpt-4.1-nano
               "What's the weather like where The Eifel Tower is?"
               {:schema weather-schema :validate-output? true})))
