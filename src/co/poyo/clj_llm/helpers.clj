(ns co.poyo.clj-llm.helpers
  (:require [clojure.string :as str]))

(defn underscore->kebab [k]
  "Convert underscore string to kebab-case"
  (str/replace (name k) "_" "-"))

(defn kebab->underscore [k]
  "Convert kebab-case string to underscore"
  (str/replace (name k) "-" "_"))
