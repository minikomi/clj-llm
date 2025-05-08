(ns co.poyo.clj-llm.registry
  (:require [co.poyo.clj-llm.protocol :as proto]))

(def backends (atom {}))

(defn register-backend!
  "Register an implementation that satisfies `Backend`. Called by plugins."
  [backend-key impl]
  (swap! backends assoc backend-key impl))

(defn get-backend
  "Get the implementation for a backend key."
  [backend-key]
  (get @backends backend-key))
