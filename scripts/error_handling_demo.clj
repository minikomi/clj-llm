#!/usr/bin/env bb

(ns error-handling-demo
  (:require [babashka.classpath :as cp]))

;; Add the source directory to classpath
(cp/add-classpath "src")

(require '[co.poyo.clj-llm.core :as llm]
         '[co.poyo.clj-llm.backends.openai :as openai]
         '[co.poyo.clj-llm.errors :as errors]
         '[clojure.core.async :refer [<!!]])

(defn demonstrate-error-handling []
  (println "\n🛡️  clj-llm Error Handling Examples\n")

  ;; Example 1: Invalid API key
  (println "1️⃣ Invalid API key:")
  (try
    (let [ai (openai/backend {:api-key "invalid-key"})]
      (llm/generate ai "Hello"))
    (catch Exception e
      (println "✅ Caught:" (errors/format-error e))
      (println "   Type:" (errors/error-type e))
      (println "   Category:" (errors/error-category (errors/error-type e)))
      (println "   Retryable:" (errors/retryable? e))))
  (println)

  ;; Example 2: Network error (invalid URL)
  (println "2️⃣ Network error:")
  (try
    (let [ai (openai/backend {:api-key "test"
                              :api-base "http://invalid.localhost:9999/v1"})]
      (llm/generate ai "Hello"))
    (catch Exception e
      (println "✅ Caught:" (.getMessage e))
      (println "   Type:" (errors/error-type e))
      (println "   Retryable:" (errors/retryable? e))))
  (println)

  ;; Example 3: Invalid model
  (println "3️⃣ Invalid model:")
  (try
    (let [ai (openai/backend {:api-key-env "OPENAI_API_KEY"})]
      (llm/generate ai "Hello" {:model "gpt-999"}))
    (catch Exception e
      (println "✅ Caught:" (.getMessage e))
      (println "   Error data:" (select-keys (ex-data e) [:type :category :model]))))
  (println)

  ;; Example 4: Schema validation error
  (println "4️⃣ Schema validation error:")
  (try
    (let [ai (openai/backend {:api-key-env "OPENAI_API_KEY"})
          schema [:map
                  [:name :string]
                  [:age pos-int?]
                  [:email [:re #".+@.+\..+"]]]
          ;; This will likely produce invalid data
          result (llm/generate ai
                               "Generate: {\"name\": \"John\", \"age\": \"twenty\", \"email\": \"invalid\"}"
                               {:model "gpt-4o-mini"
                                :schema schema})]
      (println "Result:" result))
    (catch Exception e
      (println "✅ Caught:" (.getMessage e))
      (when-let [errors (:errors (ex-data e))]
        (println "   Validation errors:" errors))))
  (println)

  ;; Example 5: Streaming error handling
  (println "5️⃣ Streaming error handling:")
  (try
    (let [ai (openai/backend {:api-key "invalid-key"})
          chunks (llm/stream ai "Tell me a story")]
      (loop []
        (when-let [chunk (<!! chunks)]
          (if (map? chunk)
            (if-let [error (:error chunk)]
              (println "✅ Stream error:" (.getMessage error))
              (print chunk))
            (print chunk))
          (flush)
          (recur))))
    (catch Exception e
      (println "✅ Caught exception:" (.getMessage e))))
  (println)

  ;; Example 6: Error recovery with retry
  (println "6️⃣ Error recovery example:")
  (let [ai (openai/backend {:api-key-env "OPENAI_API_KEY"})
        retry-with-backoff (fn [f max-retries]
                             (loop [attempt 1]
                               (let [result (try
                                              {:success true :value (f)}
                                              (catch Exception e
                                                {:success false :error e}))]
                                 (if (:success result)
                                   (:value result)
                                   (if (and (errors/retryable? (:error result))
                                            (<= attempt max-retries))
                                     (do
                                       (println (str "   Attempt " attempt " failed, retrying..."))
                                       (Thread/sleep (* 1000 attempt))
                                       (recur (inc attempt)))
                                     (throw (:error result)))))))]
    (try
      (retry-with-backoff
       #(llm/generate ai "Hello" {:model "gpt-4o-mini"})
       3)
      (println "✅ Request succeeded after retry")
      (catch Exception e
        (println "❌ Failed after retries:" (.getMessage e)))))

  (println "\n✅ Error handling demonstration complete!"))

(defn -main []
  (demonstrate-error-handling))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))