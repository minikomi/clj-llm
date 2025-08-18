(ns co.poyo.clj-llm.helpers
  (:require [clojure.string :as str]))

(defn underscore->kebab [k]
  "Convert underscore keyword to kebab-case"
  (if (keyword? k)
    (keyword (str/replace (name k) "_" "-"))
    k))
