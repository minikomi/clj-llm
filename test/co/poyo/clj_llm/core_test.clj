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

(defrecord MockProvider [responses defaults calls]
  proto/LLMProvider
  (request-stream [_ model system-prompt messages output-schema tools tool-choice provider-opts]
    (when calls
      (swap! calls conj {:model model
                         :system-prompt system-prompt
                         :messages messages
                         :output-schema output-schema
                         :tools tools
                         :tool-choice tool-choice
                         :provider-opts provider-opts}))
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
                   (merge {:model "test-model"} defaults)
                   (atom []))))

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
      (is (= {:name "Alice" :age 30} (llm/generate provider {:output-schema schema} "test")))))

  (testing "Error handling"
    (let [provider (mock-provider [{:type :error :error "API Error"}])]
      (is (thrown? Exception (llm/generate provider "test"))))))

(deftest test-request
  (testing "Rich response object"
    (let [provider (mock-provider [{:type :content :content "Response text"}
                                   {:type :usage :prompt-tokens 10 :completion-tokens 20}])
          resp (llm/request provider "test")]
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

(deftest test-args-forwarded-to-provider
  (testing "model from defaults is forwarded"
    (let [provider (mock-provider [{:type :content :content "ok"}] {:model "gpt-4o"})]
      (llm/generate provider "test")
      (is (= "gpt-4o" (:model (first @(:calls provider)))))))

  (testing "system-prompt is forwarded"
    (let [provider (mock-provider [{:type :content :content "ok"}])]
      (llm/generate provider {:system-prompt "Be helpful"} "test")
      (is (= "Be helpful" (:system-prompt (first @(:calls provider)))))))

  (testing "temperature and max-tokens arrive in provider-opts"
    (let [provider (mock-provider [{:type :content :content "ok"}])]
      (llm/generate provider {:temperature 0.5 :max-tokens 100} "test")
      (let [opts (:provider-opts (first @(:calls provider)))]
        (is (= 0.5 (:temperature opts)))
        (is (= 100 (:max_tokens opts))))))

  (testing "messages are built correctly from string input"
    (let [provider (mock-provider [{:type :content :content "ok"}])]
      (llm/generate provider "hello")
      (is (= [{:role :user :content "hello"}] (:messages (first @(:calls provider)))))))

  (testing "messages passed through from history vector"
    (let [provider (mock-provider [{:type :content :content "ok"}])
          history [{:role :user :content "hi"} {:role :assistant :content "hello"}]]
      (llm/generate provider history)
      (is (= history (:messages (first @(:calls provider))))))))

(deftest test-unknown-opts-rejected
  (testing "Unknown options throw"
    (let [provider (mock-provider [{:type :content :content "ok"}])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown options"
            (llm/generate provider {:bogus true} "test"))))))

(deftest test-tool-calls
  (testing "Tool call accumulation via request"
    (let [provider (mock-provider [{:type :tool-call :index 0 :id "call_1" :name "ping" :arguments ""}
                                   {:type :tool-call-delta :index 0 :arguments "{\"host\":"}
                                   {:type :tool-call-delta :index 0 :arguments "\"example.com\"}"}
                                   {:type :usage :prompt-tokens 10 :completion-tokens 5}])
          resp (llm/request provider "test")
          tcs @(:tool-calls resp)]
      (is (= 1 (count tcs)))
      (is (= "ping" (:name (first tcs))))
      (is (= "{\"host\":\"example.com\"}" (:arguments (first tcs))))))

  (testing "generate with :tools executes tools and returns results"
    (let [provider (mock-provider [{:type :tool-call :index 0 :id "call_1" :name "ping" :arguments ""}
                                   {:type :tool-call-delta :index 0 :arguments "{\"host\":\"example.com\"}"}])
          ping-fn (with-meta
                    (fn [{:keys [host]}] (str "pong " host))
                    {:malli/schema [:=> [:cat [:map {:name "ping" :description "Ping a host"}
                                              [:host :string]]]
                                       :string]})
          result (llm/generate provider {:tools [ping-fn]} "ping example.com")]
      (is (map? result))
      (is (= 1 (count (:tool-calls result))))
      (is (= "ping" (:name (first (:tool-calls result)))))
      (is (= {:host "example.com"} (:arguments (first (:tool-calls result)))))
      ;; Tool was executed
      (is (= ["pong example.com"] (:tool-results result)))))

  (testing "generate with :tools and text returns both"
    (let [provider (mock-provider [{:type :content :content "Let me check that"}
                                   {:type :tool-call :index 0 :id "call_1" :name "ping" :arguments ""}
                                   {:type :tool-call-delta :index 0 :arguments "{\"host\":\"example.com\"}"}])
          ping-fn (with-meta
                    (fn [{:keys [host]}] (str "pong " host))
                    {:malli/schema [:=> [:cat [:map {:name "ping" :description "Ping"}
                                              [:host :string]]]
                                       :string]})
          result (llm/generate provider {:tools [ping-fn]} "ping example.com")]
      (is (= "Let me check that" (:text result)))
      (is (= 1 (count (:tool-calls result))))
      (is (= ["pong example.com"] (:tool-results result)))))

  (testing "generate with :tools but no tool calls returns empty vectors"
    (let [provider (mock-provider [{:type :content :content "No tools needed"}])
          ping-fn (with-meta
                    (fn [{:keys [host]}] (str "pong " host))
                    {:malli/schema [:=> [:cat [:map {:name "ping" :description "Ping"}
                                              [:host :string]]]
                                       :string]})
          result (llm/generate provider {:tools [ping-fn]} "hello")]
      (is (map? result))
      (is (= "No tools needed" (:text result)))
      (is (= [] (:tool-calls result)))
      (is (= [] (:tool-results result))))))

(defn ^:private ping-tool
  {:malli/schema [:=> [:cat [:map {:name "ping" :description "Ping"}
                             [:host :string]]] :string]}
  [{:keys [host]}]
  (str "pong " host))

;; Tool registered via m/=> (global Malli registry, no metadata on var)
(defn ^:private registry-tool [{:keys [n]}] (inc n))
(malli.core/=> registry-tool [:=> [:cat [:map {:name "increment" :description "Add one"}
                                         [:n :int]]] :int])

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
      (is (some? (:malli/schema (meta f))))))

  (testing "m/=> global registry — no metadata on var"
    ;; registry-tool has no :malli/schema on its var metadata
    (is (nil? (:malli/schema (meta #'registry-tool))))
    (is (nil? (:schema (meta #'registry-tool))))
    (is (= 2 (registry-tool {:n 1})))
    ;; resolve-tool-schema finds it in Malli's global function registry
    (let [schema (#'co.poyo.clj-llm.core/resolve-tool-schema #'registry-tool)]
      (is (some? schema))
      (is (= :=> (malli.core/type (malli.core/schema schema)))))))

(deftest test-run-agent
  (testing "run-agent with tool functions"
    (let [provider (->MockProvider
                    (atom [{:type :tool-call :index 0 :id "call_1"
                            :name "get_weather" :arguments ""}
                           {:type :tool-call-delta :index 0
                            :arguments "{\"city\":\"Tokyo\"}"}])
                    {:model "test-model"}
                    (atom []))
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

  (testing "run-agent :stop-when predicate"
    (let [call-count (atom 0)
          provider (->MockProvider
                    (atom [{:type :tool-call :index 0 :id "call_1"
                            :name "done" :arguments ""}
                           {:type :tool-call-delta :index 0
                            :arguments "{\"result\":\"finished\"}"}])
                    {:model "test-model"}
                    (atom []))
          done-tool (with-meta
                      (fn [{:keys [result]}]
                        (swap! call-count inc)
                        result)
                      {:malli/schema [:=> [:cat [:map {:name "done" :description "Signal completion"}
                                                 [:result :string]]]
                                         :string]})
          result (llm/run-agent provider [done-tool]
                   {:stop-when (fn [{:keys [tool-calls]}]
                                 (some #(= "done" (:name %)) tool-calls))}
                   "do something")]
      ;; Tool was NOT executed (stop-when fires before execution)
      (is (= 0 @call-count))
      ;; Pending tool calls are returned
      (is (= 1 (count (:tool-calls result))))
      (is (= "done" (:name (first (:tool-calls result)))))))

  (testing "run-agent default stop-when: no tool calls"
    (let [provider (mock-provider [{:type :content :content "Just text, no tools"}])
          dummy-tool (with-meta
                       (fn [{:keys [x]}] x)
                       {:malli/schema [:=> [:cat [:map {:name "dummy" :description "Dummy"}
                                                  [:x :string]]]
                                          :string]})
          result (llm/run-agent provider [dummy-tool] "hello")]
      (is (= "Just text, no tools" (:text result)))
      (is (nil? (:tool-calls result)))
      (is (empty? (:steps result)))))

  (testing "run-agent returns text, compose with generate for structured output"
    (let [provider (->MockProvider
                    (atom [{:type :tool-call :index 0 :id "call_1"
                            :name "lookup" :arguments ""}
                           {:type :tool-call-delta :index 0
                            :arguments "{\"id\":\"123\"}"}])
                    {:model "test-model"}
                    (atom []))
          lookup (with-meta
                   (fn [{:keys [id]}]
                     (reset! (.responses provider)
                             [{:type :content :content "Alice is 30 years old"}])
                     (str "found user " id))
                   {:malli/schema [:=> [:cat [:map {:name "lookup" :description "Lookup"}
                                              [:id :string]]]
                                       :string]})
          result (llm/run-agent provider [lookup] "find user 123")]
      ;; run-agent always returns text as a string
      (is (string? (:text result)))
      (is (= "Alice is 30 years old" (:text result)))
      ;; history is reusable for structured extraction via generate
      (is (vector? (:history result))))))

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
