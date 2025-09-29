(ns co.poyo.clj-llm.core-test
  "Basic tests for the new API"
  (:require [clojure.test :refer [deftest testing is]]
            [co.poyo.clj-llm.core :as llm]
            [co.poyo.clj-llm.protocol :as proto]
            [clojure.core.async :as a :refer [chan go >! <!! close!]]))

;; Mock provider for testing
(defrecord MockProvider [responses defaults]
  proto/LLMProvider
  (request-stream [_ messages provider-opts]
    (let [ch (chan)]
      (go
        (doseq [event @responses]
          (>! ch event))
        (>! ch {:type :done})
        (close! ch))
      ch)))

(defn mock-provider
  "Create a mock provider with predefined responses"
  ([events] (mock-provider events nil))
  ([events defaults]
   (->MockProvider (atom events) defaults)))

(deftest test-basic-prompt
  (testing "Basic text generation"
    (let [provider (mock-provider [{:type :content :content "Hello "}
                                   {:type :content :content "world!"}])
          response (llm/prompt provider "test")]
      (is (= "Hello world!" @(:text response)))))

  (testing "Structured output generation"
    (let [provider (mock-provider [{:type :content :content "{\"name\":\"Alice\",\"age\":30}"}])
          schema [:map [:name :string] [:age pos-int?]]
          response (llm/prompt provider "test" {:llm/schema schema})]
      (is (= {:name "Alice" :age 30} @(:structured response)))))

  (testing "Error handling"
    (let [provider (mock-provider [{:type :error :error "API Error"}])
          response (llm/prompt provider "test")]
      (is (instance? Exception @(:text response))))))

(deftest test-stream
  (testing "Streaming text chunks"
    (let [provider (mock-provider [{:type :content :content "Hello "}
                                   {:type :content :content "streaming "}
                                   {:type :content :content "world!"}])
          response (llm/prompt provider "test")
          chunks (:chunks response)
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
          response (llm/prompt provider "test")
          events (:events response)
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

(deftest test-response-object
  (testing "Rich response object"
    (let [provider (mock-provider [{:type :content :content "Response text"}
                                   {:type :usage :prompt-tokens 10 :completion-tokens 20}])
          resp (llm/prompt provider "test")]
      ;; Test promise access
      (is (= "Response text" @(:text resp)))
      ;; Usage includes enriched metadata but contains original fields
      (let [usage @(:usage resp)]
        (is (= :usage (:type usage)))
        (is (= 10 (:prompt-tokens usage)))
        (is (= 20 (:completion-tokens usage)))))))

(deftest test-message-building
  (testing "Messages from prompt and system prompt"
    (let [provider (mock-provider [{:type :content :content "OK"}])
          response (llm/prompt provider "Hello" {:llm/system-prompt "Be helpful"})]
      (is (string? @(:text response)))))

  (testing "Direct message history"
    (let [provider (mock-provider [{:type :content :content "OK"}])
          messages [{:role :user :content "Hello"}]
          response (llm/prompt provider nil {:llm/message-history messages})]
      (is (string? @(:text response))))))