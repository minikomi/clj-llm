(ns co.poyo.clj-llm.sse
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [camel-snake-kebab.core :as csk]))

(defn- kebab-keys [m]
  (walk/postwalk
   (fn [x] (if (map? x) (update-keys x (comp keyword csk/->kebab-case)) x))
   m))

(defn parse-line
  "Parse one SSE line. Returns {:data map}, {:done true}, or nil."
  [line]
  (when (str/starts-with? line "data:")
    (let [raw (str/trim (subs line 5))]
      (if (= raw "[DONE]")
        {:done true}
        (try {:data (-> raw json/parse-string kebab-keys)}
             (catch Exception _ nil))))))
