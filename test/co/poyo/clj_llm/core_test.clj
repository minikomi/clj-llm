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
  (testing "Text generation returns string"
    (let [provider (mock-provider [{:type :content :content "Hello "}
                                   {:type :content :content "world!"}])]
      (is (= "Hello world!" (llm/generate provider "test")))))

  (testing "Structured output returns parsed data directly"
    (let [provider (mock-provider [{:type :content :content "{\"name\":\"Alice\",\"age\":30}"}])
          schema [:map [:name :string] [:age pos-int?]]]
      (is (= {:name "Alice" :age 30} (llm/generate provider {:schema schema} "test")))))

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
    (let [provider (mock-provider [{:type :content :content "OK"}])]
      (is (= "OK" (llm/generate provider {:system-prompt "Be helpful"} "Hello")))))

  (testing "Direct message history"
    (let [provider (mock-provider [{:type :content :content "OK"}])
          messages [{:role :user :content "Hello"}]]
      (is (= "OK" (llm/generate provider messages))))))

(deftest test-provider-defaults
  (testing "Provider with defaults on :defaults key"
    (let [base (mock-provider [{:type :content :content "meow"}])
          agent (assoc base :defaults {:model "gpt-4o"
                                       :system-prompt "you are a cat"})]
      (is (= "gpt-4o" (get-in agent [:defaults :model])))
      (is (= "you are a cat" (get-in agent [:defaults :system-prompt])))
      (is (= "meow" (llm/generate agent "hi")))))

  (testing "Layering defaults with update+merge"
    (let [base (mock-provider [{:type :content :content "ok"}])
          ai (assoc base :defaults {:model "gpt-4o-mini"})
          extractor (update ai :defaults merge {:system-prompt "extract"})]
      (is (= "gpt-4o-mini" (get-in extractor [:defaults :model])))
      (is (= "extract" (get-in extractor [:defaults :system-prompt])))
      (is (= "ok" (llm/generate extractor "test"))))))

(deftest test-unknown-opts-rejected
  (testing "Unknown options throw"
    (let [provider (mock-provider [{:type :content :content "ok"}])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown options"
            (llm/generate provider {:bogus true} "test"))))))

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

  (testing "generate rejects :tools — use run-agent instead"
    (let [tool-schema [:map {:name "ping" :description "Ping a host"}
                       [:host :string]]
          provider (mock-provider [{:type :content :content "hi"}])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not support :tools"
            (llm/generate provider {:tools [tool-schema]} "ping both"))))))

(deftest test-run-agent
  (testing "run-agent executes tool calls and returns final text"
    (let [call-count (atom 0)
          tool-schema [:map {:name "get_weather" :description "Get weather"}
                       [:city :string]]
          ;; First call returns tool call, second returns text
          provider (->MockProvider
                    (atom [{:type :tool-call :index 0 :id "call_1"
                            :name "get_weather" :arguments ""}
                           {:type :tool-call-delta :index 0
                            :arguments "{\"city\":\"Tokyo\"}"}])
                    {:model "test-model"})
          execute-fn (fn [tc]
                       ;; After first execution, swap to text response
                       (reset! (.responses provider)
                               [{:type :content :content "It's sunny in Tokyo!"}])
                       (str "Sunny, 22C in " (:city (:arguments tc))))
          result (llm/run-agent provider
                   {:tools [tool-schema] :execute execute-fn}
                   "Weather in Tokyo?")]
      (is (= "It's sunny in Tokyo!" (:text result)))
      (is (vector? (:history result)))
      (is (= 1 (count (:steps result))))
      (is (= 1 (count (:tool-calls (first (:steps result))))))
      (is (= "get_weather" (:name (first (:tool-calls (first (:steps result)))))))))

  (testing "run-agent requires :execute in opts"
    (let [provider (mock-provider [{:type :content :content "hi"}])]
      (is (thrown? clojure.lang.ExceptionInfo
            (llm/run-agent provider {:tools []} "test")))))

  (testing "run-agent with :schema parses final response"
    (let [tool-schema [:map {:name "lookup" :description "Lookup"}
                       [:id :string]]
          provider (->MockProvider
                    (atom [{:type :tool-call :index 0 :id "call_1"
                            :name "lookup" :arguments ""}
                           {:type :tool-call-delta :index 0
                            :arguments "{\"id\":\"123\"}"}])
                    {:model "test-model"})
          execute-fn (fn [tc]
                       (reset! (.responses provider)
                               [{:type :content :content "{\"name\":\"Alice\",\"age\":30}"}])
                       "found it")
          result (llm/run-agent provider
                   {:tools [tool-schema]
                    :execute execute-fn
                    :schema [:map [:name :string] [:age pos-int?]]}
                   "find user 123")]
      (is (= {:name "Alice" :age 30} (:text result))))))

(deftest test-tool-result
  (testing "tool-result creates correct message map"
    (let [msg (llm/tool-result "call_abc" "Sunny, 22°C")]
      (is (= :tool (:role msg)))
      (is (= "call_abc" (:tool-call-id msg)))
      (is (= "Sunny, 22°C" (:content msg))))))

(deftest test-generate-return-consistency
  (testing "generate returns string for simple text"
    (let [provider (mock-provider [{:type :content :content "hi"}])]
      (is (string? (llm/generate provider "test")))
      (is (= "hi" (llm/generate provider "test"))))))

(deftest test-stream-print
  (testing "stream-print returns text string"
    (let [provider (mock-provider [{:type :content :content "one "}
                                   {:type :content :content "two "}
                                   {:type :content :content "three"}])
          output (with-out-str
                   (let [result (llm/stream-print provider "test")]
                     (is (string? result))
                     (is (= "one two three" result))))]
      ;; Verify it printed to stdout
      (is (clojure.string/includes? output "one two three")))))
