(require '[co.poyo.clj-llm.core :as llm]
         '[co.poyo.clj-llm.backends.openai :as openai]
         '[clojure.core.async :as a :refer [<!!]])

(println "Testing streaming in" (if (System/getProperty "babashka.version") "Babashka" "Clojure"))
(println "--------------------")

(def provider (openai/->openai {:api-key (System/getenv "OPENAI_API_KEY")}))

(let [start-time (System/currentTimeMillis)
      response (llm/prompt provider "give me a long poem please"
                          {:provider/opts {:model "gpt-4o-mini"
                                           :temperature 0}})
      chunks (:chunks response)]
  (println "Stream started at 0ms")
  (loop [chunk-count 0]
    (when-let [chunk (<!! chunks)]
      (let [elapsed (- (System/currentTimeMillis) start-time)]
        (print (format "[%4dms] " elapsed))
        (print chunk)
        (flush))
      (recur (inc chunk-count))))
  (println (format "\n[%4dms] Stream complete" (- (System/currentTimeMillis) start-time))))
