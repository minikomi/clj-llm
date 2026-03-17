#!/usr/bin/env bb

(require '[co.poyo.clj-llm.core :as llm]
         '[co.poyo.clj-llm.content :as content]
         '[co.poyo.clj-llm.backend.openai :as openai])

(def ai
  (-> (openai/backend {:api-key  (System/getenv "OPENROUTER_KEY")
                       :api-base "https://openrouter.ai/api/v1"})
      (assoc :defaults {:model "gpt-4o-mini"})))

;; A trivial tool so run-agent has something to work with
(defn guess-age
  "Estimate a person's age range from a description."
  {:malli/schema [:=> [:cat [:map {:name "guess_age"
                                   :description "Estimate a person's age range from a description"}
                             [:description {:description "Physical description of the person"} :string]]]
                  :string]}
  [{:keys [description]}]
  (str "Based on the description: " description " — estimated age range: 25-35"))

(def img (content/image "/tmp/person.jpg" {:max-edge 512}))

(println "Running agent with person image...\n")

(let [result (llm/run-agent ai [#'guess-age]
               {:max-steps 3
                :on-tool-calls (fn [{:keys [tool-calls]}]
                                 (doseq [tc tool-calls]
                                   (println "🛠️ " (:name tc) (:arguments tc))))
                :on-tool-result (fn [{:keys [tool-call result]}]
                                  (println "  →" result))}
               ["Describe this person and estimate their age." img])]
  (println "\n--- Final ---")
  (println (:text result))
  (println "\nSteps:" (count (:steps result))))
