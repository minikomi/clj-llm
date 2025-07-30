(ns co.poyo.clj-llm.errors-test
  "Tests for simplified error handling"
  (:require [clojure.test :refer [deftest testing is]]
            [co.poyo.clj-llm.errors :as errors]))

(deftest test-error-creation
  (testing "Basic error creation"
    (let [err (errors/error "Test error" {:foo "bar"})]
      (is (instance? clojure.lang.ExceptionInfo err))
      (is (= :llm-error (:type (ex-data err))))
      (is (= "Test error" (ex-message err)))
      (is (= "bar" (:foo (ex-data err)))))))

(deftest test-retryable
  (testing "Retryable errors"
    (is (errors/retryable? (errors/error "test" {:status 429})))
    (is (errors/retryable? (errors/error "test" {:status 500})))
    (is (errors/retryable? (errors/error "test" {:status 502})))
    (is (errors/retryable? (errors/error "test" {:status 503})))
    (is (not (errors/retryable? (errors/error "test" {:status 401}))))
    (is (not (errors/retryable? (errors/error "test" {:status 404}))))
    (is (not (errors/retryable? (Exception. "regular"))))))

(deftest test-http-error-parsing
  (testing "401 Unauthorized"
    (let [err (errors/parse-http-error "openai" 401 {})]
      (is (= "openai: Invalid API key" (ex-message err)))
      (is (= 401 (:status (ex-data err))))))
  
  (testing "429 Rate Limit"
    (let [err (errors/parse-http-error "openai" 429 
                                       {:error {:retry_after 60}})]
      (is (= "openai: Rate limit exceeded" (ex-message err)))
      (is (= 429 (:status (ex-data err))))
      (is (= 60 (:retry-after (ex-data err))))))
  
  (testing "500 Server error"
    (let [err (errors/parse-http-error "openai" 500 "Internal error")]
      (is (= "openai: Server error" (ex-message err)))
      (is (= 500 (:status (ex-data err))))
      (is (errors/retryable? err)))))