(ns co.poyo.clj-llm.core-test
  "Basic tests for the new API"
  (:require [clojure.test :refer [deftest testing is]]
            [co.poyo.clj-llm.core :as llm]
            [co.poyo.clj-llm.protocol :as proto]
            [clojure.core.async :as a :refer [chan go >! <!! close!]]))

;; Mock provider for testing
(defrecord MockProvider [responses defaults]
  proto/LLMProvider
  (request-stream [_ model system-prompt messages schema provider-opts]
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
   (->MockProvider (atom events)
                   (merge #:co.poyo.clj-llm.core{:model "test-model"} defaults))))

(deftest test-basic-prompt
  (testing "Basic text generation"
    (let [provider (mock-provider [{:type :content :content "Hello "}
                                   {:type :content :content "world!"}])
          response (llm/prompt provider "test")]
      (is (= "Hello world!" @(:text response)))))

  (testing "Structured output generation"
    (let [provider (mock-provider [{:type :content :content "{\"name\":\"Alice\",\"age\":30}"}])
          schema [:map [:name :string] [:age pos-int?]]
          response (llm/prompt provider "test" #:co.poyo.clj-llm.core{:schema schema})]
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
          response (llm/prompt provider "Hello" #:co.poyo.clj-llm.core{:system-prompt "Be helpful"})]
      (is (string? @(:text response)))))

  (testing "Direct message history"
    (let [provider (mock-provider [{:type :content :content "OK"}])
          messages [{:role :user :content "Hello"}]
          response (llm/prompt provider nil #:co.poyo.clj-llm.core{:message-history messages})]
      (is (string? @(:text response))))))

(deftest test-extract-prompt-opts
  (testing "Validates and extracts prompt options"
    (let [extract-fn #'llm/extract-prompt-opts
          opts #:co.poyo.clj-llm.core{:system-prompt "Test"
                                       :schema [:map [:name :string]]
                                       :timeout-ms 5000
                                       :message-history [{:role :user :content "Hi"}]
                                       :provider-opts {:model "gpt-4"}}
          result (extract-fn opts)]
      ;; Should extract all co.poyo.clj-llm.core/* keys
      (is (= "Test" (:co.poyo.clj-llm.core/system-prompt result)))
      (is (= [:map [:name :string]] (:co.poyo.clj-llm.core/schema result)))
      (is (= 5000 (:co.poyo.clj-llm.core/timeout-ms result)))
      (is (= [{:role :user :content "Hi"}] (:co.poyo.clj-llm.core/message-history result)))
      (is (= {:model "gpt-4"} (:co.poyo.clj-llm.core/provider-opts result)))))

  (testing "Rejects invalid options"
    (let [extract-fn #'llm/extract-prompt-opts
          opts #:co.poyo.clj-llm.core{:invalid-key "value"}]
      (is (thrown? clojure.lang.ExceptionInfo (extract-fn opts))))))