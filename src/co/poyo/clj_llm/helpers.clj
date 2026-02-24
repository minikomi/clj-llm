(ns co.poyo.clj-llm.helpers)

(defn deep-merge
  "Recursively merge maps. Later values win for non-map conflicts.
   Nil arguments are silently dropped."
  [& maps]
  (apply merge-with
         (fn [existing override]
           (if (and (map? existing) (map? override))
             (deep-merge existing override)
             override))
         (filter map? maps)))
