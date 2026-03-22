(ns co.poyo.clj-llm.backend.openrouter
  "OpenRouter API provider implementation.

    OpenRouter is OpenAI-compatible, so this backend reuses the OpenAI
    backend implementation with OpenRouter-specific defaults."
  (:require
   [co.poyo.clj-llm.backend.openai :as openai]))

(def ^:private default-api-base "https://openrouter.ai/api/v1")
(def ^:private default-model "minimax/minimax-m2.5"))

(defn- default-api-key-fn []
    (or (System/getenv "OPENROUTER_KEY")
        (throw (ex-info "No API key provided and OPENROUTER_KEY env var not set" {}))))

(defn backend
  "Create an OpenRouter provider.

    Config: :api-key, :api-base, :defaults.
    :api-key can be a string, a zero-arg fn, or false (skip auth).

    Defaults:
      - :api-base: https://openrouter.ai/api/v1
      - :model: minimax/minimax-m2.5
      - :api-key: OPENROUTER_KEY env var

    Example:

      (require '[co.poyo.clj-llm.backend.openrouter :as openrouter])
      (require '[co.poyo.clj-llm.core :as llm])

      (def ai (openrouter/backend))
      (llm/generate ai \"Hello!\") ;; Uses minimax/minimax-m2.5 by default

      ;; Override default model
      (def ai (openrouter/backend {:defaults {:model \"anthropic/claude-3.5-sonnet\"}}))"
  ([] (backend {}))
  ([{:keys [api-key api-base defaults]}]
   (openai/backend {:api-base (or api-base default-api-base)
                    :api-key (or api-key default-api-key-fn)
                    :defaults (merge {:model default-model} defaults)})))
