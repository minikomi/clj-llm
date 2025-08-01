(ns co.poyo.clj-llm.core-test
  "Basic tests for the new API"
  (:require [clojure.test :refer [deftest testing is]]
            [co.poyo.clj-llm.core :as llm]
            [co.poyo.clj-llm.protocol :as proto]
            [clojure.core.async :as a :refer [chan go >! <!! close!]]))

;; Mock provider for testing
(defrecord MockProvider [responses]
  proto/LLMProvider
  (request-stream [_ model messages opts]
    (let [ch (chan)]
      (go
        (doseq [event @responses]
          (>! ch event))
        (>! ch {:type :done})
        (close! ch))
      ch)))

(defn mock-provider
  "Create a mock provider with predefined responses"
  [events]
  (->MockProvider (atom events)))

(deftest test-generate
  (testing "Basic text generation"
    (let [provider (mock-provider [{:type :content :content "Hello "}
                                   {:type :content :content "world!"}])]
      (is (= "Hello world!" (llm/generate provider "test")))))

  (testing "Structured output generation"
    (let [provider (mock-provider [{:type :content :content "{\"name\":\"Alice\",\"age\":30}"}])
          schema [:map [:name :string] [:age pos-int?]]]
      (is (= {:name "Alice" :age 30}
             (llm/generate provider "test" {:schema schema})))))

  (testing "Error handling"
    (let [provider (mock-provider [{:type :error :error "API Error"}])]
      (is (thrown? Exception (llm/generate provider "test"))))))

(deftest test-stream
  (testing "Streaming text chunks"
    (let [provider (mock-provider [{:type :content :content "Hello "}
                                   {:type :content :content "streaming "}
                                   {:type :content :content "world!"}])
          chunks (llm/stream provider "test")
          collected (atom [])]
      ;; Collect all chunks
      (loop []
        (when-let [chunk (<!! chunks)]
          (swap! collected conj chunk)
          (recur)))
      (is (= ["Hello " "streaming " "world!"] @collected)))))

(deftest test-events
  (testing "Raw event access"
    (let [provider (mock-provider [{:type :content :content "Hi"}
                                   {:type :usage :prompt-tokens 5 :completion-tokens 10}])
          events (llm/events provider "test")
          collected (atom [])]
      ;; Collect all events
      (loop []
        (when-let [event (<!! events)]
          (swap! collected conj event)
          (when-not (= :done (:type event))
            (recur))))
      (is (= 3 (count @collected))) ;; content, usage, done
      (is (= :content (:type (first @collected))))
      (is (= :usage (:type (second @collected)))))))

(deftest test-prompt
  (testing "Rich response object"
    (let [provider (mock-provider [{:type :content :content "Response text"}
                                   {:type :usage :prompt-tokens 10 :completion-tokens 20}])
          resp (llm/prompt provider "test")]
      ;; Test promise access directly (no IDeref anymore)
      (is (= "Response text" @(:text resp)))
      ;; Usage includes enriched metadata but contains original fields
      (let [usage @(:usage resp)]
        (is (= :usage (:type usage)))
        (is (= 10 (:prompt-tokens usage)))
        (is (= 20 (:completion-tokens usage)))))))

(deftest test-message-building
  (testing "Messages from prompt and system prompt"
    (let [provider (mock-provider [{:type :content :content "OK"}])]
      ;; Test is implicit through generate working correctly
      (is (string? (llm/generate provider "Hello" {:system-prompt "Be helpful"})))))

  (testing "Direct messages array"
    (let [provider (mock-provider [{:type :content :content "OK"}])
          messages [{:role :user :content "Hello"}]]
      (is (string? (llm/generate provider nil {:messages messages}))))))