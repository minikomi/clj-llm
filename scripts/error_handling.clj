#!/usr/bin/env bb

(ns error-handling-example
  (:require [babashka.classpath :as cp]))

;; Add the source directory to classpath
(cp/add-classpath "src")

(require '[co.poyo.clj-llm.core :as llm]
         '[co.poyo.clj-llm.backends.openai :as openai]
         '[clojure.core.async :refer [<!!]])

(defn -main []
  (println "\n🚨 clj-llm Error Handling Examples\n")

  ;; Example 1: Missing API key
  (println "1️⃣ Missing API key:")
  (try
    (openai/backend {:api-key nil})
    (catch Exception e
      (println "✅ Caught expected error:" (ex-message e))))
  (println)

  ;; Example 2: Invalid model
  (println "2️⃣ Invalid model name:")
  (let [ai (openai/backend {:api-key-env "OPENAI_API_KEY"})]
    (try
      (llm/generate ai "Hello" {:model "invalid-model-xyz"})
      (catch Exception e
        (println "✅ Error will come through streaming..."))))

  ;; Check streaming error
  (let [ai (openai/backend {:api-key-env "OPENAI_API_KEY"})
        events (llm/events ai "Hello" {:model "invalid-model-xyz"})]
    (println "   Checking event stream:")
    (loop []
      (when-let [event (<!! events)]
        (case (:type event)
          :error (println "   ✅ Got error event:" (:error event))
          :content (print ".")
          nil)
        (when-not (#{:error :done} (:type event))
          (recur)))))
  (println)

  ;; Example 3: Invalid structured output
  (println "3️⃣ Invalid structured output:")
  (let [ai (openai/backend {:api-key-env "OPENAI_API_KEY"})
        schema [:map [:name :string] [:age pos-int?]]]
    (try
      ;; Mock a response that returns invalid JSON
      (let [mock-provider (reify co.poyo.clj-llm.protocol/LLMProvider
                            (request-stream [_ _ _ _]
                              (let [ch (clojure.core.async/chan)]
                                (clojure.core.async/go
                                  (clojure.core.async/>! ch {:type :content :content "This is not JSON"})
                                  (clojure.core.async/>! ch {:type :done})
                                  (clojure.core.async/close! ch))
                                ch)))]
        (llm/generate mock-provider "test" {:schema schema}))
      (catch Exception e
        (println "✅ Caught parsing error:" (ex-message e)))))
  (println)

  ;; Example 4: Network timeout handling
  (println "4️⃣ Network issues:")
  (println "   If network is down, errors would appear in the event stream")
  (println "   The library will pass through HTTP errors from the provider")

  (println "\n✅ Error handling examples completed!")
  (println "\nKey points:")
  (println "- Backend creation validates configuration immediately")
  (println "- Synchronous functions throw exceptions")
  (println "- Streaming functions emit error events")
  (println "- All errors include helpful context"))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))