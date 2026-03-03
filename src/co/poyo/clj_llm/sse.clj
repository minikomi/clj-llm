(ns co.poyo.clj-llm.sse
  "Pure SSE data-line decoding.  No HTTP or I/O."
  (:require
   [camel-snake-kebab.core :as csk]
   [camel-snake-kebab.extras :as cske]
   [cheshire.core :as json]
   [clojure.string :as str]))

(def ^:private ->kebab-key (memoize csk/->kebab-case-keyword))

(defn parse-data-line
  "Parse one SSE text line into a decoded map.
   Returns nil for non-data lines, blank/[DONE], and invalid JSON."
  [^String line]
  (when (str/starts-with? line "data:")
    (let [data (str/trim (subs line 5))]
      (when-not (or (str/blank? data)
                    (= "[DONE]" data))
        (try
          (cske/transform-keys ->kebab-key (json/parse-string data))
          (catch Exception _
            nil))))))
