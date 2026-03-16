#!/usr/bin/env bb

(require '[co.poyo.clj-llm.core :as llm]
         '[co.poyo.clj-llm.content :as content]
         '[co.poyo.clj-llm.backends.openai :as openai])


;; use first argument as image path

(def image-path (first *command-line-args*))
(when (not image-path)
  (println "Usage: describe-person.clj <image-path>")
  (System/exit 1))

(def openrouter-key (System/getenv "OPENROUTER_KEY"))

(when (not openrouter-key)
  (println "Error: OPENROUTER_KEY environment variable not set.")
  (System/exit 1))

(def ai
  (openai/backend {:api-key openrouter-key
                   :api-base "https://openrouter.ai/api/v1"
                   :defaults {:model "gpt-4o-mini"}}))

(def img (content/image image-path {:max-edge 512}))

(println "Image part keys:" (keys img))
(println "Media type:" (:media-type img))
(println "Data length:" (count (:data img)) "chars base64")
(println)

(println "--- Describing image ---")
(println (:text (llm/generate ai ["Describe this image in 2-3 sentences." img])))
