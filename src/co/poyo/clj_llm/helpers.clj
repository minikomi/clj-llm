(ns co.poyo.clj-llm.helpers
  (:require [clojure.string :as str]))

(defn underscore->kebab [k]
  "Convert underscore string to kebab-case"
  (str/replace (name k) "_" "-"))

(defn kebab->underscore [k]
  "Convert kebab-case string to underscore"
  (str/replace (name k) "-" "_"))

(defn deep-merge
  [& maps]
  (apply merge-with
         (fn [v1 v2]
           (if (and (map? v1) (map? v2))
             (deep-merge v1 v2)
             v2))
         (filter map? maps)))
