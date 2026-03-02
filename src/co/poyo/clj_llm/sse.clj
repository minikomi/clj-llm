(ns co.poyo.clj-llm.sse
  "SSE line parsing transducer."
  (:require
   [camel-snake-kebab.core :as csk]
   [camel-snake-kebab.extras :as cske]
   [cheshire.core :as json]
   [clojure.string :as str]))

(def ^:private ->kebab-key (memoize csk/->kebab-case-keyword))

(defn- parse-sse-line
  "Parse one SSE data line into a kebab-cased map, or nil."
  [line]
  (when (str/starts-with? line "data:")
    (try
      (let [raw (str/trim (subs line 5))]
        (cske/transform-keys ->kebab-key (json/parse-string raw)))
      (catch Exception _ nil))))

(def xf
  "Transducer: raw SSE lines \u2192 parsed data maps. Skips [DONE] and blanks."
  (comp
    (remove #(str/ends-with? % "[DONE]"))
    (keep parse-sse-line)))
