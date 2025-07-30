(require '[co.poyo.clj-llm.core :as llm]
         '[co.poyo.clj-llm.backends.openai :as openai]
         '[clojure.core.async :as a :refer [<!!]])

(println "Testing streaming in" (if (System/getProperty "babashka.version") "Babashka" "Clojure"))
(println "--------------------")

(def backend (openai/backend {:api-key (System/getenv "OPENAI_API_KEY")}))

(let [start-time (System/currentTimeMillis)
      chunks (llm/stream backend "give me a long poem please" 
                        {:model "gpt-4.1-nano"
                         :temperature 0})]
  (println "Stream started at 0ms")
  (loop [chunk-count 0]
    (when-let [chunk (<!! chunks)]
      (let [elapsed (- (System/currentTimeMillis) start-time)]
        (print (format "[%4dms] " elapsed))
        (print chunk)
        (flush))
      (recur (inc chunk-count))))
  (println (format "\n[%4dms] Stream complete" (- (System/currentTimeMillis) start-time))))
