(ns co.poyo.clj-llm.helpers)

(defn deep-merge
  [& maps]
  (apply merge-with
         (fn [existing override]
           (if (and (map? existing) (map? override))
             (deep-merge existing override)
             override))
         (filter map? maps)))
