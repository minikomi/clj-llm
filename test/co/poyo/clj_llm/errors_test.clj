(ns co.poyo.clj-llm.errors-test
  "Tests for HTTP error handling — exercises real production code paths"
  (:require [clojure.test :refer [deftest testing is]]
            [co.poyo.clj-llm.stream :as stream]
            [co.poyo.clj-llm.core :as llm]
            [co.poyo.clj-llm.protocol :as proto]
            [clojure.core.async :as a]))

;; ════════════════════════════════════════════════════════════════════
;; stream/check-status! — the real HTTP error barrier
;; ════════════════════════════════════════════════════════════════════

(def ^:private check-status! @#'stream/check-status!)

(deftest test-check-status-throws-on-non-200
  (testing "check-status! throws ex-info with status and body for non-200"
    (testing "401 with JSON body"
      (let [ex (try
                 (check-status! {:status 401
                                 :body (java.io.ByteArrayInputStream.
                                         (.getBytes "{\"error\":{\"message\":\"Invalid API key\"}}"))})
                 (catch Exception e e))]
        (is (= 401 (:status (ex-data ex))))
        (is (string? (:body (ex-data ex))))))

    (testing "500 with plain text body"
      (let [ex (try
                 (check-status! {:status 500
                                 :body (java.io.ByteArrayInputStream. (.getBytes "Internal error"))})
                 (catch Exception e e))]
        (is (= 500 (:status (ex-data ex))))
        (is (= "Internal error" (:body (ex-data ex))))))

    (testing "200 passes through"
      (is (nil? (check-status! {:status 200
                                :body (java.io.ByteArrayInputStream. (.getBytes "ok"))}))))))

;; ════════════════════════════════════════════════════════════════════
;; Event error path — :error events through the state machine
;; ════════════════════════════════════════════════════════════════════

(defrecord ErrorProvider [error]
  proto/LLMProvider
  (api-key [_] nil)
  (build-url [_ _] "https://mock.test/api")
  (build-headers [_] {})
  (build-body [_ _ _ _ _ _ _ _] {})
  (parse-chunk [_ chunk _ _] (if (:type chunk) [chunk] []))
  (stream-events [_ _ _ _]
    (let [ch (a/chan 256)]
      (a/thread
        (a/>!! ch {:type :error :error error})
        (a/>!! ch {:type :done})
        (a/close! ch))
      ch)))

(deftest test-error-event-through-generate
  (testing ":error events with map error throw from generate"
    (let [provider (assoc (->ErrorProvider {:message "Rate limited"}) :defaults {:model "test"})]
      (is (thrown-with-msg? Exception #"Rate limited"
            (llm/generate provider "test")))))

  (testing ":error events with string error"
    (let [provider (assoc (->ErrorProvider "connection reset") :defaults {:model "test"})]
      (is (thrown? Exception
            (llm/generate provider "test"))))))

;; ════════════════════════════════════════════════════════════════════
;; Option validation errors
;; ════════════════════════════════════════════════════════════════════

(defrecord MinimalProvider []
  proto/LLMProvider
  (api-key [_] nil)
  (build-url [_ _] "")
  (build-headers [_] {})
  (build-body [_ _ _ _ _ _ _ _] {})
  (parse-chunk [_ _ _ _] [])
  (stream-events [_ _ _ _] (a/chan 1)))

(deftest test-invalid-options-throw
  (testing "Unknown options throw ex-info with :llm/invalid-request"
    (let [provider (assoc (->MinimalProvider) :defaults {:model "test"})]
      (try
        (llm/generate provider {:bogus true} "test")
        (is false "should have thrown")
        (catch Exception e
          (is (= :llm/invalid-request (:error-type (ex-data e)))))))))
