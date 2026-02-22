(ns co.poyo.clj-llm.errors-test
  "Tests for simplified error handling"
  (:require [clojure.test :refer [deftest testing is]]
            [co.poyo.clj-llm.errors :as errors]))

(deftest test-error-creation
  (testing "Basic error creation"
    (let [err (errors/error "Test error" {:foo "bar"})]
      (is (instance? clojure.lang.ExceptionInfo err))
      (is (= :llm/unknown (:error-type (ex-data err))))
      (is (= "Test error" (ex-message err)))
      (is (= "bar" (:foo (ex-data err)))))))

(deftest test-error-type
  (testing "error-type returns correct keyword for HTTP status codes"
    (is (= :llm/rate-limit (errors/error-type (errors/error "test" {:error-type :llm/rate-limit}))))
    (is (= :llm/invalid-key (errors/error-type (errors/error "test" {:error-type :llm/invalid-key}))))
    (is (= :llm/server-error (errors/error-type (errors/error "test" {:error-type :llm/server-error}))))
    (is (= :llm/invalid-request (errors/error-type (errors/error "test" {:error-type :llm/invalid-request}))))
    (is (= :llm/unknown (errors/error-type (errors/error "test" {})))))
  (testing "error-type returns nil for non-ExceptionInfo"
    (is (nil? (errors/error-type (Exception. "regular"))))))

(deftest test-error-type-from-http
  (testing "parse-http-error tags the right error-type"
    (is (= :llm/rate-limit (errors/error-type (errors/parse-http-error "openai" 429 {}))))
    (is (= :llm/invalid-key (errors/error-type (errors/parse-http-error "openai" 401 {}))))
    (is (= :llm/invalid-key (errors/error-type (errors/parse-http-error "openai" 403 {}))))
    (is (= :llm/server-error (errors/error-type (errors/parse-http-error "openai" 500 {}))))
    (is (= :llm/server-error (errors/error-type (errors/parse-http-error "openai" 503 {}))))
    (is (= :llm/invalid-request (errors/error-type (errors/parse-http-error "openai" 400 {}))))
    (is (= :llm/invalid-request (errors/error-type (errors/parse-http-error "openai" 404 {}))))))

(deftest test-retry-after
  (testing "retry-after extracts value from error"
    (let [err (errors/parse-http-error "openai" 429 {:error {:retry_after 60}})]
      (is (= 60 (errors/retry-after err)))))
  (testing "retry-after returns nil when not present"
    (is (nil? (errors/retry-after (errors/error "test" {}))))))

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
      (is (= 500 (:status (ex-data err)))))))
