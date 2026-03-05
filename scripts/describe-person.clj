#!/usr/bin/env bb

(require '[co.poyo.clj-llm.core :as llm]
         '[co.poyo.clj-llm.content :as content]
         '[co.poyo.clj-llm.backends.openai :as openai])

(def ai
  (let [key (System/getenv "OPENROUTER_KEY")]
    (-> (openai/backend {:api-key key
                         :api-base "https://openrouter.ai/api/v1"})
        (assoc :defaults {:model "gpt-4o-mini"}))))

(def img (content/image "/tmp/person.jpg" {:max-edge 512}))

(println "Image part keys:" (keys img))
(println "Media type:" (:media-type img))
(println "Data length:" (count (:data img)) "chars base64")
(println)

(println "--- Describing person ---")
(println (llm/generate ai ["Describe this person in 2-3 sentences." img]))
