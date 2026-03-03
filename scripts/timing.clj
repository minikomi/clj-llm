#!/usr/bin/env bb

(require '[co.poyo.clj-llm.core :as llm]
         '[co.poyo.clj-llm.backends.openai :as openai])

(def ai
  (let [k (System/getenv "OPENROUTER_KEY")]
    (-> (if k
          (openai/backend {:api-key k :api-base "https://openrouter.ai/api/v1"})
          (openai/backend))
        (assoc :defaults {:model (or (System/getenv "LLM_MODEL") "gpt-4o-mini")}))))

(println "Timing events from llm/events...")
(println)

(let [t0 (System/currentTimeMillis)
      n  (atom 0)]
  (doseq [event (llm/events ai "Count from 1 to 10, one number per line.")]
    (let [elapsed (- (System/currentTimeMillis) t0)
          i (swap! n inc)]
      (println (format "%6dms  #%-3d  %-20s  %s"
                 elapsed i (:type event)
                 (case (:type event)
                   :content (pr-str (:content event))
                   :tool-call (pr-str (select-keys event [:name :id]))
                   :tool-call-delta (pr-str (select-keys event [:index :arguments]))
                   :usage (pr-str (dissoc event :type))
                   :finish (pr-str (:reason event))
                   :error (pr-str event)
                   :done ""
                   (pr-str event)))))))
