(ns co.poyo.clj-llm.registry)

(def ^:private backends (atom {}))

(defn register-backend! [k backend]
  (swap! backends assoc k backend))

(defn fetch-backend [k]
  (@backends k))
