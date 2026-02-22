(ns co.poyo.clj-llm.core-test
  "Tests for the core API"
  (:require [clojure.test :refer [deftest testing is]]
            [co.poyo.clj-llm.core :as llm]
            [co.poyo.clj-llm.protocol :as proto]
            [clojure.core.async :as a :refer [chan go >! <!! close!]]
            [clojure.string :as str]
            [malli.core]))

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
    (let [tool-schema [:=> [:cat [:map {:name "ping" :description "Ping a host"}
                                  [:host :string]]]
                           :string]
          provider (mock-provider [{:type :content :content "hi"}])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not support :tools"
            (llm/generate provider {:tools [tool-schema]} "ping both"))))))

(defn ^:private ping-tool
  {:malli/schema [:=> [:cat [:map {:name "ping" :description "Ping"}
                             [:host :string]]] :string]}
  [{:keys [host]}]
  (str "pong " host))

(deftest test-tool-schema-from-defn
  (testing "defn with :malli/schema — schema lives on the var"
    (is (= "pong example.com" (ping-tool {:host "example.com"})))
    (let [schema (:malli/schema (meta #'ping-tool))]
      (is (some? schema))
      (is (= :=> (malli.core/type (malli.core/schema schema))))
      (let [s (malli.core/schema schema)
            cat-schema (first (malli.core/children s))
            input (first (malli.core/children (malli.core/schema cat-schema)))]
        (is (= "ping" (:name (malli.core/properties (malli.core/schema input))))))))

  (testing "with-meta fn also works"
    (let [f (with-meta
              (fn [{:keys [x]}] (* x x))
              {:malli/schema [:=> [:cat [:map {:name "square" :description "Square"}
                                        [:x :int]]] :int]})]
      (is (= 9 (f {:x 3})))
      (is (some? (:malli/schema (meta f))))))

  (testing "flat arrow :-> syntax works"
    (let [f (with-meta
              (fn [{:keys [host]}] (str "pong " host))
              {:malli/schema [:-> [:map {:name "ping" :description "Ping"}
                                   [:host :string]] :string]})]
      (is (fn? f))
      (is (= "pong x" (f {:host "x"})))
      (is (some? (:malli/schema (meta f)))))))

(deftest test-run-agent
  (testing "run-agent with tool functions"
    (let [provider (->MockProvider
                    (atom [{:type :tool-call :index 0 :id "call_1"
                            :name "get_weather" :arguments ""}
                           {:type :tool-call-delta :index 0
                            :arguments "{\"city\":\"Tokyo\"}"}])
                    {:model "test-model"})
          get-weather (with-meta
                        (fn [{:keys [city]}]
                          (reset! (.responses provider)
                                  [{:type :content :content "It's sunny in Tokyo!"}])
                          (str "Sunny, 22C in " city))
                        {:malli/schema [:=> [:cat [:map {:name "get_weather" :description "Get weather"}
                                                   [:city :string]]]
                                            :string]})
          result (llm/run-agent provider [get-weather] "Weather in Tokyo?")]
      (is (= "It's sunny in Tokyo!" (:text result)))
      (is (vector? (:history result)))
      (is (= 1 (count (:steps result))))
      (is (= 1 (count (:tool-calls (first (:steps result))))))
      (is (= "get_weather" (:name (first (:tool-calls (first (:steps result)))))))))

  (testing "run-agent requires non-empty tools vector"
    (let [provider (mock-provider [{:type :content :content "hi"}])]
      (is (thrown? clojure.lang.ExceptionInfo
            (llm/run-agent provider [] "test")))))

  (testing "run-agent with :schema parses final response"
    (let [provider (->MockProvider
                    (atom [{:type :tool-call :index 0 :id "call_1"
                            :name "lookup" :arguments ""}
                           {:type :tool-call-delta :index 0
                            :arguments "{\"id\":\"123\"}"}])
                    {:model "test-model"})
          lookup (with-meta
                   (fn [{:keys [id]}]
                     (reset! (.responses provider)
                             [{:type :content :content "{\"name\":\"Alice\",\"age\":30}"}])
                     (str "found user " id))
                   {:malli/schema [:=> [:cat [:map {:name "lookup" :description "Lookup"}
                                              [:id :string]]]
                                       :string]})
          result (llm/run-agent provider [lookup]
                   {:schema [:map [:name :string] [:age pos-int?]]}
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
