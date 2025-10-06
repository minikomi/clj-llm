#!/usr/bin/env bb

(require '[co.poyo.clj-llm.core :as llm]
         '[co.poyo.clj-llm.backends.anthropic :as anthropic]
         '[clojure.string :as str]
         '[clojure.java.io :as io])

;; Load .env
(when (.exists (io/file ".env"))
  (doseq [line (str/split-lines (slurp ".env"))]
    (when-let [[_ k v] (re-matches #"([^=]+)=(.*)" line)]
      (System/setProperty k (str/replace v #"^['\"]|['\"]$" "")))))

(def claude (anthropic/->anthropic))

(def response (llm/prompt claude "Say hello!" #:co.poyo.clj-llm.core{:model "claude-sonnet-4-5"}))

(def text @(:text response))
(def usage @(:usage response))

(if (instance? Exception text)
  (do
    (println "Error:" (.getMessage text))
    (println "Data:" (ex-data text)))
  (do
    (println text)
    (println)
    (println "Usage:")
    (println "  Input tokens: " (:input-tokens usage))
    (println "  Output tokens:" (:output-tokens usage))))
