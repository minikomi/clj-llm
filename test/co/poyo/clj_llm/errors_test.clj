(ns co.poyo.clj-llm.errors-test
  "Tests for comprehensive error handling"
  (:require [clojure.test :refer [deftest testing is]]
            [co.poyo.clj-llm.errors :as errors]))

(deftest test-error-creation
  (testing "Network errors"
    (let [err (errors/network-error "Connection failed" {:url "http://test"})]
      (is (instance? clojure.lang.ExceptionInfo err))
      (is (= :llm/network-error (:type (ex-data err))))
      (is (= :network (:category (ex-data err))))
      (is (= "Connection failed" (ex-message err)))))

  (testing "Provider errors"
    (let [err (errors/rate-limit-error "openai" :retry-after 60)]
      (is (= :llm/rate-limit (:type (ex-data err))))
      (is (= :provider (:category (ex-data err))))
      (is (= 60 (:retry-after (ex-data err))))))

  (testing "Validation errors"
    (let [err (errors/schema-validation-error
               [:map [:name :string]]
               {:name 123}
               {:name ["should be a string"]})]
      (is (= :llm/schema-validation (:type (ex-data err))))
      (is (= :validation (:category (ex-data err)))))))

(deftest test-error-utilities
  (testing "Error type detection"
    (let [network-err (errors/network-error "test" {})
          regular-err (Exception. "regular")]
      (is (errors/error? network-err))
      (is (not (errors/error? regular-err)))
      (is (= :llm/network-error (errors/error-type network-err)))
      (is (nil? (errors/error-type regular-err)))))

  (testing "Retryable errors"
    (is (errors/retryable? (errors/network-error "test" {})))
    (is (errors/retryable? (errors/timeout-error "timeout" 5000 {})))
    (is (errors/retryable? (errors/rate-limit-error "openai")))
    (is (not (errors/retryable? (errors/invalid-api-key "openai"))))
    (is (not (errors/retryable? (errors/schema-validation-error {} {} {}))))))

(deftest test-http-error-parsing
  (testing "401 Unauthorized"
    (let [err (errors/parse-http-error "openai" 401 {} {})]
      (is (= :llm/invalid-api-key (:type (ex-data err))))))

  (testing "429 Rate Limit"
    (let [err (errors/parse-http-error "openai" 429
                                       {:error {:retry_after 60}}
                                       {})]
      (is (= :llm/rate-limit (:type (ex-data err))))
      (is (= 60 (:retry-after (ex-data err))))))

  (testing "404 Model not found"
    (let [err (errors/parse-http-error "openai" 404 {} {:model "gpt-999"})]
      (is (= :llm/model-not-found (:type (ex-data err))))))

  (testing "500 Server error"
    (let [err (errors/parse-http-error "openai" 500 "Internal error" {})]
      (is (= :llm/network-error (:type (ex-data err))))
      (is (errors/retryable? err)))))

(deftest test-error-formatting
  (testing "Format with hint"
    (let [err (errors/invalid-api-key "openai")
          formatted (errors/format-error err)]
      (is (re-find #"Invalid or missing API key" formatted))
      (is (re-find #"Check your API key" formatted))))

  (testing "Format regular exception"
    (let [err (Exception. "test error")
          formatted (errors/format-error err)]
      (is (= "java.lang.Exception: test error" formatted)))))

(deftest test-retry-after-extraction
  (testing "Extract retry-after"
    (let [err (errors/rate-limit-error "openai" :retry-after 30)]
      (is (= 30 (errors/extract-retry-after err)))))

  (testing "Extract from reset-time"
    (let [future-time (+ (System/currentTimeMillis) 60000)
          err (errors/rate-limit-error "openai" :reset-time future-time)
          retry-after (errors/extract-retry-after err)]
      (is (pos? retry-after))
      (is (<= retry-after 60000))))

  (testing "No retry info"
    (let [err (errors/invalid-api-key "openai")]
      (is (nil? (errors/extract-retry-after err))))))