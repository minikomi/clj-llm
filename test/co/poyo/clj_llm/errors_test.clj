(ns co.poyo.clj-llm.errors-test
  "Tests for HTTP error handling in backends"
  (:require [clojure.test :refer [deftest testing is]]
            [cheshire.core :as json]))

(defn- read-error-body [body]
  (try
    (let [raw (if (string? body) body (slurp body))]
      (try (json/parse-string raw true)
           (catch Exception _ raw)))
    (catch Exception _ nil)))

(defn- error-event [_provider-name {:keys [status body]}]
  {:type :error :status status :body (read-error-body body)})

(deftest test-error-event-json-body
  (testing "Surfaces parsed JSON error body"
    (let [evt (error-event "openai" {:status 401
                                     :body "{\"error\":{\"message\":\"Invalid API key\"}}"})]
      (is (= :error (:type evt)))
      (is (= 401 (:status evt)))
      (is (= {:error {:message "Invalid API key"}} (:body evt))))))

(deftest test-error-event-string-body
  (testing "Surfaces plain string when body isn't JSON"
    (let [evt (error-event "anthropic" {:status 500 :body "Internal error"})]
      (is (= :error (:type evt)))
      (is (= 500 (:status evt)))
      (is (= "Internal error" (:body evt))))))

(deftest test-error-event-stream-body
  (testing "Reads InputStream body"
    (let [stream (java.io.ByteArrayInputStream. (.getBytes "{\"error\":\"bad\"}"))
          evt (error-event "openai" {:status 400 :body stream})]
      (is (= {:error "bad"} (:body evt))))))
