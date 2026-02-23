(ns co.poyo.clj-llm.helpers)

(defn deep-merge
  [& maps]
  (apply merge-with
         (fn [v1 v2]
           (if (and (map? v1) (map? v2))
             (deep-merge v1 v2)
             v2))
         (filter map? maps)))
