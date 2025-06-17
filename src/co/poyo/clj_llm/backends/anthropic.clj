(ns co.poyo.clj-llm.backends.anthropic
  "Anthropic API implementation - placeholder for now"
  (:require [clojure.core.async :refer [chan close!]]
            [co.poyo.clj-llm.protocol :as proto]))

(defn- stream-impl
  "Placeholder Anthropic implementation"
  [model message opts]
  (let [out-ch (chan)]
    (close! out-ch) ; Close immediately for now
    {:channel out-ch}))

;; ──────────────────────────────────────────────────────────────
;; Protocol implementation
;; ──────────────────────────────────────────────────────────────

(deftype AnthropicBackend []
  proto/LLMBackend
  (stream [this model message opts]
    (stream-impl model message opts)))

;; ──────────────────────────────────────────────────────────────
;; Public API
;; ──────────────────────────────────────────────────────────────

(defn make-backend
  "Create an Anthropic backend instance.
  
  Returns:
    Backend instance for use with llm/prompt"
  []
  (->AnthropicBackend))