(ns co.poyo.clj-llm.core-test
  "Tests for the core API"
  (:require [clojure.test :refer [deftest testing is]]
            [co.poyo.clj-llm.core :as llm]
            [co.poyo.clj-llm.protocol :as proto]
            [clojure.core.async :as a :refer [chan go >! <!! close!]]
            [clojure.string :as str]))

;; ════════════════════════════════════════════════════════════════════
;; Mock provider
;; ════════════════════════════════════════════════════════════════════

(defrecord MockProvider [responses defaults]
  proto/LLMProvider
  (request-stream [_ model system-prompt messages schema tools tool-choice provider-opts]
    (let [ch (chan)]
      (go
        (doseq [event @responses]
          (>! ch event))
        (>! ch {:type :done})
        (close! ch))
      ch)))

(defn mock-provider
  ([events] (mock-provider events {}))
  ([events defaults]
   (->MockProvider (atom events)
                   (merge {:model "test-model"} defaults))))

;; ════════════════════════════════════════════════════════════════════
;; Tests
;; ════════════════════════════════════════════════════════════════════

(deftest test-generate
  (testing "Basic text generation returns map"
    (let [provider (mock-provider [{:type :content :content "Hello "}
                                   {:type :content :content "world!"}])
          result (llm/generate provider "test")]
      (is (map? result))
      (is (= "Hello world!" (:text result)))))

  (testing "Structured output generation"
    (let [provider (mock-provider [{:type :content :content "{\"name\":\"Alice\",\"age\":30}"}])
          schema [:map [:name :string] [:age pos-int?]]
          result (llm/generate provider "test" {:schema schema})]
      (is (= "{\"name\":\"Alice\",\"age\":30}" (:text result)))
      (is (= {:name "Alice" :age 30} (:structured result)))))

  (testing "Usage included when present"
    (let [provider (mock-provider [{:type :content :content "hi"}
                                   {:type :usage :prompt-tokens 5 :completion-tokens 10}])
          result (llm/generate provider "test")]
      (is (= "hi" (:text result)))
      (is (some? (:usage result)))
      (is (= 5 (:prompt-tokens (:usage result))))))

  (testing "Error handling"
    (let [provider (mock-provider [{:type :error :error "API Error"}])]
      (is (thrown? Exception (llm/generate provider "test"))))))

(deftest test-prompt
  (testing "Rich response object"
    (let [provider (mock-provider [{:type :content :content "Response text"}
                                   {:type :usage :prompt-tokens 10 :completion-tokens 20}])
          resp (llm/prompt provider "test")]
      ;; IDeref gives text
      (is (= "Response text" @resp))
      ;; Direct promise access
      (is (= "Response text" @(:text resp)))
      ;; Usage
      (let [usage @(:usage resp)]
        (is (= :usage (:type usage)))
        (is (= 10 (:prompt-tokens usage)))
        (is (= 20 (:completion-tokens usage)))))))

(deftest test-stream
  (testing "Streaming text chunks"
    (let [provider (mock-provider [{:type :content :content "Hello "}
                                   {:type :content :content "streaming "}
                                   {:type :content :content "world!"}])
          chunks (llm/stream provider "test")
          collected (atom [])]
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
      (loop []
        (when-let [event (<!! events)]
          (swap! collected conj event)
          (when-not (= :done (:type event))
            (recur))))
      (is (= 3 (count @collected)))
      (is (= :content (:type (first @collected))))
      (is (= :usage (:type (second @collected)))))))

(deftest test-message-building
  (testing "With system prompt"
    (let [provider (mock-provider [{:type :content :content "OK"}])
          result (llm/generate provider "Hello" {:system-prompt "Be helpful"})]
      (is (= "OK" (:text result)))))

  (testing "Direct message history"
    (let [provider (mock-provider [{:type :content :content "OK"}])
          messages [{:role :user :content "Hello"}]
          result (llm/generate provider nil {:message-history messages})]
      (is (= "OK" (:text result))))))

(deftest test-with-defaults
  (testing "Building an agent with defaults"
    (let [base (mock-provider [{:type :content :content "meow"}])
          agent (llm/with-defaults base {:model "gpt-4o"
                                         :system-prompt "you are a cat"})]
      (is (= "gpt-4o" (get-in agent [:defaults :model])))
      (is (= "you are a cat" (get-in agent [:defaults :system-prompt])))
      (is (= "meow" (:text (llm/generate agent "hi"))))))

  (testing "with-defaults validates keys"
    (let [base (mock-provider [{:type :content :content "ok"}])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown options"
            (llm/with-defaults base {:model "gpt-4o" :bogus true}))))))

(deftest test-unknown-opts-rejected
  (testing "Unknown options throw"
    (let [provider (mock-provider [{:type :content :content "ok"}])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown options"
            (llm/generate provider "test" {:bogus true}))))))

(deftest test-tool-calls
  (testing "Tool call accumulation via prompt"
    (let [provider (mock-provider [{:type :tool-call :index 0 :id "call_1" :name "ping" :arguments ""}
                                   {:type :tool-call-delta :index 0 :arguments "{\"host\":"}
                                   {:type :tool-call-delta :index 0 :arguments "\"example.com\"}"}
                                   {:type :usage :prompt-tokens 10 :completion-tokens 5}])
          resp (llm/prompt provider "test")]
      (let [tool-calls @(:tool-calls resp)]
        (is (= 1 (count tool-calls)))
        (is (= "ping" (:name (first tool-calls))))
        (is (= "{\"host\":\"example.com\"}" (:arguments (first tool-calls)))))))

  (testing "generate with :tools returns map with :tool-calls and :message"
    (let [tool-schema [:map {:name "ping" :description "Ping a host"}
                       [:host :string]]
          provider (mock-provider [{:type :tool-call :index 0 :id "call_1" :name "ping" :arguments ""}
                                   {:type :tool-call-delta :index 0 :arguments "{\"host\":\"example.com\"}"}
                                   {:type :tool-call :index 1 :id "call_2" :name "ping" :arguments ""}
                                   {:type :tool-call-delta :index 1 :arguments "{\"host\":\"test.com\"}"}
                                   {:type :usage :prompt-tokens 10 :completion-tokens 5}])
          result (llm/generate provider "ping both" {:tools [tool-schema]})]
      ;; Returns a map
      (is (map? result))
      (is (string? (:text result)))
      ;; Tool calls are parsed
      (let [tc (:tool-calls result)]
        (is (= 2 (count tc)))
        (is (= "ping" (:name (first tc))))
        (is (= {:host "example.com"} (:arguments (first tc))))
        (is (= {:host "test.com"} (:arguments (second tc)))))
      ;; :message is formatted for history round-tripping
      (let [msg (:message result)]
        (is (= :assistant (:role msg)))
        (is (= 2 (count (:tool_calls msg))))
        (is (= "function" (:type (first (:tool_calls msg)))))
        (is (= "ping" (get-in msg [:tool_calls 0 :function :name])))))))

(deftest test-tool-result
  (testing "tool-result creates correct message map"
    (let [msg (llm/tool-result "call_abc" "Sunny, 22°C")]
      (is (= :tool (:role msg)))
      (is (= "call_abc" (:tool_call_id msg)))
      (is (= "Sunny, 22°C" (:content msg))))))

(deftest test-generate-return-consistency
  (testing "generate always returns a map, even for simple text"
    (let [provider (mock-provider [{:type :content :content "hi"}])
          result (llm/generate provider "test")]
      (is (map? result))
      (is (contains? result :text)))))

(deftest test-stream-print
  (testing "stream-print returns {:text ...}"
    (let [provider (mock-provider [{:type :content :content "one "}
                                   {:type :content :content "two "}
                                   {:type :content :content "three"}])
          output (with-out-str
                   (let [result (llm/stream-print provider "test")]
                     (is (map? result))
                     (is (= "one two three" (:text result)))))]
      ;; Verify it printed to stdout
      (is (clojure.string/includes? output "one two three")))))
