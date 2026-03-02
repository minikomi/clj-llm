(ns co.poyo.clj-llm.errors-test
  "Tests for HTTP error classification in sse"
  (:require [clojure.test :refer [deftest testing is]]
            [co.poyo.clj-llm.sse]))

(def ^:private error-type
  @#'co.poyo.clj-llm.sse/error-type)

(def ^:private error-event
  @#'co.poyo.clj-llm.sse/error-event)

(deftest test-error-type-classification
  (testing "Auth errors"
    (is (= :llm/invalid-key (error-type 401)))
    (is (= :llm/invalid-key (error-type 403))))
  (testing "Rate limit"
    (is (= :llm/rate-limit (error-type 429))))
  (testing "Client errors"
    (is (= :llm/invalid-request (error-type 400)))
    (is (= :llm/invalid-request (error-type 404)))
    (is (= :llm/invalid-request (error-type 422))))
  (testing "Server errors"
    (is (= :llm/server-error (error-type 500)))
    (is (= :llm/server-error (error-type 502)))
    (is (= :llm/server-error (error-type 503)))
    (is (= :llm/server-error (error-type 504))))
  (testing "Unknown"
    (is (= :llm/unknown (error-type 600)))))

(deftest test-error-event-structure
  (testing "Error event from JSON error body"
    (let [response {:status 401
                    :body "{\"error\":{\"message\":\"Invalid API key\"}}"}]
      (let [evt (error-event "openai" response)]
        (is (= :error (:type evt)))
        (is (= 401 (:status evt)))
        (is (clojure.string/includes? (:error evt) "Invalid API key"))
        (is (clojure.string/includes? (:error evt) "openai"))
        (is (= :llm/invalid-key (:error-type (ex-data (:exception evt))))))))

  (testing "Error event from plain string body"
    (let [evt (error-event "anthropic" {:status 500 :body "Internal error"})]
      (is (= 500 (:status evt)))
      (is (clojure.string/includes? (:error evt) "Internal error"))
      (is (= :llm/server-error (:error-type (ex-data (:exception evt))))))))
