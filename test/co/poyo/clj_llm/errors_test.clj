(ns co.poyo.clj-llm.errors-test
  "Tests for HTTP error parsing in backend-helpers"
  (:require [clojure.test :refer [deftest testing is]]
            [co.poyo.clj-llm.backends.backend-helpers]))

(def ^:private parse-http-error
  "Access the private parse-http-error fn for testing."
  @#'co.poyo.clj-llm.backends.backend-helpers/parse-http-error)

(defn- error-type [e]
  (when (instance? clojure.lang.ExceptionInfo e)
    (:error-type (ex-data e))))

(defn- retry-after [e]
  (when (instance? clojure.lang.ExceptionInfo e)
    (:retry-after (ex-data e))))

(deftest test-error-type-from-http
  (testing "parse-http-error tags the right error-type"
    (is (= :llm/rate-limit (error-type (parse-http-error "openai" 429 {}))))
    (is (= :llm/invalid-key (error-type (parse-http-error "openai" 401 {}))))
    (is (= :llm/invalid-key (error-type (parse-http-error "openai" 403 {}))))
    (is (= :llm/server-error (error-type (parse-http-error "openai" 500 {}))))
    (is (= :llm/server-error (error-type (parse-http-error "openai" 503 {}))))
    (is (= :llm/invalid-request (error-type (parse-http-error "openai" 400 {}))))
    (is (= :llm/invalid-request (error-type (parse-http-error "openai" 404 {}))))))

(deftest test-retry-after
  (testing "retry-after extracts value from error"
    (let [err (parse-http-error "openai" 429 {:error {:retry_after 60}})]
      (is (= 60 (retry-after err)))))
  (testing "retry-after returns nil when not present"
    (is (nil? (retry-after (ex-info "test" {}))))))

(deftest test-http-error-parsing
  (testing "401 Unauthorized"
    (let [err (parse-http-error "openai" 401 {})]
      (is (= "openai: Invalid API key" (ex-message err)))
      (is (= 401 (:status (ex-data err))))))

  (testing "429 Rate Limit"
    (let [err (parse-http-error "openai" 429
                                {:error {:retry_after 60}})]
      (is (= "openai: Rate limit exceeded" (ex-message err)))
      (is (= 429 (:status (ex-data err))))
      (is (= 60 (:retry-after (ex-data err))))))

  (testing "500 Server error"
    (let [err (parse-http-error "openai" 500 "Internal error")]
      (is (= "openai: Server error" (ex-message err)))
      (is (= 500 (:status (ex-data err)))))))

(deftest test-unknown-status
  (testing "Unknown HTTP status falls back to :llm/unknown"
    (let [err (parse-http-error "test" 418 {})]
      (is (= :llm/unknown (error-type err)))
      (is (= "test: HTTP 418" (ex-message err))))))
