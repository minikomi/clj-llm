(ns co.poyo.clj-llm.registry
  (:require [co.poyo.clj-llm.protocol :as proto]))

(def ^:private backends (atom {}))

(defn register-backend!
  "Register an implementation that satisfies `Backend`. Called by plugins."
  [backend-key impl]
  (swap! backends assoc backend-key impl))

(defn backend-impl
  "Get the implementation for a backend key."
  [backend-key]
  (get @backends backend-key))
