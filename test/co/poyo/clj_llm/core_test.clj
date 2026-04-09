(ns co.poyo.clj-llm.core-test
  "Tests for the core API"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.core.async :as a]
            [cheshire.core :as json]
            [co.poyo.clj-llm.core :as llm]
            [co.poyo.clj-llm.protocol :as proto]
            [malli.core]))

;; ════════════════════════════════════════════════════════════════════
;; Mock provider
;; ════════════════════════════════════════════════════════════════════

(defrecord MockProvider [responses defaults calls]
  proto/LLMProvider
  (api-key [_] "mock-api-key")
  (build-url [_ model] (str "https://mock.example.com/" model))
  (build-headers [_] {"Content-Type" "application/json"})
  (build-body [this model system-prompt messages schema tools tool-choice provider-opts]
    (when calls
      (swap! calls conj {:model model
                         :system-prompt system-prompt
                         :messages messages
                         :schema schema
                         :tools tools
                         :tool-choice tool-choice
                         :provider-opts provider-opts}))
    ;; Return minimal body - mock doesn't need actual API format
    {:model model
     :messages (if system-prompt
                 (into [{:role "system" :content system-prompt}] messages)
                 messages)})
  (parse-chunk [_ chunk _schema _tools]
    ;; Test events are already in internal event format - pass through
    (if (:type chunk) [chunk] []))
  (stream-events [this _url _headers _body]
    (let [events (conj (vec @(.responses this)) {:type :done})
          ch (a/chan 256)]
      (a/thread
        (doseq [e events]
          (a/>!! ch e))
        (a/close! ch))
      ch)))

(defn mock-provider
  ([events] (mock-provider events {}))
  ([events defaults]
   (->MockProvider (atom events)
                   (merge {:model "test-model"} defaults)
                   (atom []))))

;; Tests
;; ════════════════════════════════════════════════════════════════════

(deftest test-generate
  (testing "Text generation returns result map with :text"
    (let [provider (mock-provider [{:type :content :content "Hello "}
                                   {:type :content :content "world!"}])]
      (is (= "Hello world!" (:text (llm/generate provider "test"))))))

  (testing "Structured output returns :structured in result map"
    (let [provider (mock-provider [{:type :content :content "{\"name\":\"Alice\",\"age\":30}"}])
          schema [:map [:name :string] [:age pos-int?]]
          result (llm/generate provider {:schema schema} "test")]
      (is (= {:name "Alice" :age 30} (:structured result)))
      (is (string? (:text result)))))

  (testing "Error handling"
    (let [provider (mock-provider [{:type :error :error "API Error"}])]
      (is (thrown? Exception (llm/generate provider "test"))))))

(deftest test-generate-returns-usage
  (testing "Usage is included in generate result"
    (let [provider (mock-provider [{:type :content :content "hi"}
                                   {:type :usage :prompt-tokens 10 :completion-tokens 20}])]
      (is (= 10 (get-in (llm/generate provider "test") [:usage :prompt-tokens]))))))

(deftest test-message-building
  (testing "With system prompt"
    (let [provider (mock-provider [{:type :content :content "OK"}])]
      (is (= "OK" (:text (llm/generate provider {:system-prompt "Be helpful"} "Hello"))))))

  (testing "Direct message history"
    (let [provider (mock-provider [{:type :content :content "OK"}])
          messages [{:role :user :content "Hello"}]]
      (is (= "OK" (:text (llm/generate provider messages)))))))

(deftest test-provider-defaults
  (testing "Provider with defaults on :defaults key"
    (let [base (mock-provider [{:type :content :content "meow"}])
          agent (assoc base :defaults {:model "gpt-4o"
                                       :system-prompt "you are a cat"})]
      (is (= "gpt-4o" (get-in agent [:defaults :model])))
      (is (= "you are a cat" (get-in agent [:defaults :system-prompt])))
      (is (= "meow" (:text (llm/generate agent "hi"))))))

  (testing "Layering defaults with update+merge"
    (let [base (mock-provider [{:type :content :content "ok"}])
          ai (assoc base :defaults {:model "gpt-4o-mini"})
          extractor (update ai :defaults merge {:system-prompt "extract"})]
      (is (= "gpt-4o-mini" (get-in extractor [:defaults :model])))
      (is (= "extract" (get-in extractor [:defaults :system-prompt])))
      (is (= "ok" (:text (llm/generate extractor "test")))))))

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
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid options"
            (llm/generate provider {:bogus true} "test"))))))

(deftest test-tools-and-schema-mutually-exclusive
  (testing "Passing both :tools and :schema throws"
    (let [provider (mock-provider [{:type :content :content "ok"}])
          ping-fn (with-meta
                    (fn [{:keys [host]}] (str "pong " host))
                    {:malli/schema [:=> [:cat [:map {:name "ping" :description "Ping"}
                                              [:host :string]]]
                                       :string]})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot use :tools and :schema simultaneously"
            (llm/generate provider {:tools [ping-fn] :schema [:map [:name :string]]} "test"))))))

(deftest test-tool-calls
  (testing "Tool call accumulation via request + reduce"
    (let [provider (mock-provider [{:type :tool-call :index 0 :id "call_1" :name "ping" :arguments ""}
                                   {:type :tool-call-delta :index 0 :arguments "{\"host\":"}
                                   {:type :tool-call-delta :index 0 :arguments "\"example.com\"}"}
                                   {:type :usage :prompt-tokens 10 :completion-tokens 5}])
          ;; Use generate to get assembled tool calls
          ping-fn (with-meta
                    (fn [{:keys [host]}] (str "pong " host))
                    {:malli/schema [:=> [:cat [:map {:name "ping" :description "Ping a host"}
                                              [:host :string]]]
                                       :string]})
          result (llm/generate provider {:tools [ping-fn]} "ping example.com")]
      (is (= 1 (count (:tool-calls result))))
      (is (= "ping" (:name (first (:tool-calls result)))))
      (is (= {:host "example.com"} (:arguments (first (:tool-calls result)))))))

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
          result (llm/run-agent provider {:tools [get-weather]} "Weather in Tokyo?")]
      (is (= "It's sunny in Tokyo!" (:text result)))
      (is (vector? (:history result)))
      (is (= 1 (count (:steps result))))
      (is (= 1 (count (:tool-calls (first (:steps result))))))
      (is (= "get_weather" (:name (first (:tool-calls (first (:steps result)))))))))

  (testing "run-agent requires non-empty tools vector"
    (let [provider (mock-provider [{:type :content :content "hi"}])]
      (is (thrown? clojure.lang.ExceptionInfo
            (llm/run-agent provider {:tools []} "test")))))

  (testing "run-agent errors when no tools provided"
    (let [provider (mock-provider [{:type :content :content "hi"}])]
      (is (thrown? clojure.lang.ExceptionInfo
            (llm/run-agent provider {} "test")))))


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
          result (llm/run-agent provider
                   {:tools [done-tool]
                    :stop-when (fn [{:keys [tool-calls]}]
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
          result (llm/run-agent provider {:tools [dummy-tool]} "hello")]
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
          result (llm/run-agent provider {:tools [lookup]} "find user 123")]
      ;; run-agent always returns text as a string
      (is (string? (:text result)))
      (is (= "Alice is 30 years old" (:text result)))
      ;; history is reusable for structured extraction via generate
      (is (vector? (:history result))))))

(deftest test-run-agent-usage-accumulates
  (testing "run-agent accumulates usage across all steps"
    (let [provider (->MockProvider
                    (atom [{:type :tool-call :index 0 :id "call_1"
                            :name "search" :arguments ""}
                           {:type :tool-call-delta :index 0
                            :arguments "{\"q\":\"tokyo\"}"}
                           {:type :usage :prompt-tokens 100 :completion-tokens 50 :total-tokens 150}])
                    {:model "test-model"}
                    (atom []))
          search-tool (with-meta
                         (fn [{:keys [q]}]
                           ;; After first call, return a final text response with its own usage
                           (reset! (.responses provider)
                                   [{:type :content :content (str "Results for " q)}
                                    {:type :usage :prompt-tokens 200 :completion-tokens 80 :total-tokens 280}])
                           (str "found: " q))
                         {:malli/schema [:=> [:cat [:map {:name "search" :description "Search"}
                                                    [:q :string]]]
                                            :string]})
          result (llm/run-agent provider {:tools [search-tool]} "search tokyo")]
      ;; Two steps happened: step 0 (tool call) and step 1 (final text)
      (is (= "Results for tokyo" (:text result)))
      ;; Usage should be accumulated: step 0 + step 1
      (is (= 300 (get-in result [:usage :prompt-tokens])))
      (is (= 130 (get-in result [:usage :completion-tokens])))
      (is (= 430 (get-in result [:usage :total-tokens])))
      ;; Model should be included
      (is (= "test-model" (:model (:usage result)))))))

(deftest test-tool-result
  (testing "tool-result creates correct message map with string"
    (let [msg (llm/tool-result "call_abc" "Sunny, 22°C")]
      (is (= :tool (:role msg)))
      (is (= "call_abc" (:tool-call-id msg)))
      (is (= "Sunny, 22°C" (:content msg)))))
  (testing "tool-result auto-serializes non-strings to JSON"
    (let [msg (llm/tool-result "call_xyz" {:name "Tokyo" :latitude 35.69})]
      (is (= :tool (:role msg)))
      (is (= "call_xyz" (:tool-call-id msg)))
      (is (= (json/parse-string (:content msg) true)
             {:name "Tokyo" :latitude 35.69})))))

(deftest test-generate-return-map
  (testing "generate always returns a map with :text"
    (let [provider (mock-provider [{:type :content :content "hi"}])
          result (llm/generate provider "test")]
      (is (map? result))
      (is (= "hi" (:text result))))))

;; ════════════════════════════════════════════════════════════════════
;; result? / auto-unwrap tests
;; ════════════════════════════════════════════════════════════════════

(deftest test-generate-result-type
  (testing "generate returns a result map"
    (let [provider (mock-provider [{:type :content :content "hello"}])
          result (llm/generate provider "test")]
      (is (contains? result :text))
      (is (map? result))))

  (testing "run-agent returns a result map"
    (let [provider (mock-provider [{:type :content :content "done"}])
          dummy (with-meta (fn [{:keys [x]}] x)
                  {:malli/schema [:=> [:cat [:map {:name "dummy" :description "D"}
                                             [:x :string]]] :string]})
          result (llm/run-agent provider {:tools [dummy]} "test")]
      (is (contains? result :text)))))

(deftest test-generate-result-str-coercion
  (testing "str renders as a map"
    (let [provider (mock-provider [{:type :content :content "hi"}])
          result (llm/generate provider "test")
          s (str result)]
      (is (clojure.string/includes? s ":text"))
      (is (clojure.string/includes? s "\"hi\"")))))

(deftest test-generate-result-map-behavior
  (testing "result map behaves as a map"
    (let [provider (mock-provider [{:type :content :content "hi"}
                                   {:type :usage :prompt-tokens 5 :completion-tokens 2}])
          result (llm/generate provider "test")]
      (is (= "hi" (:text result)))
      (is (= 5 (get-in result [:usage :prompt-tokens])))
      (is (contains? result :text))
      (is (#{#{:text :usage} #{:text :usage :timings}} (set (keys result))))
      (is (= "hi" (:text result))))))

(deftest test-generate-result-auto-unwrap-chaining
  (testing "chaining on :structured uses prn-str as content"
    (let [calls (atom [])
          provider (->MockProvider
                     (atom [{:type :content :content "{\"name\":\"Alice\"}"}])
                     {:model "test-model"}
                     calls)
          schema [:map [:name :string]]
          prev-result (llm/generate provider {:schema schema} "extract")
          _ (reset! (.responses provider) [{:type :content :content "ok"}])
          _ (llm/generate provider prev-result)]
      (is (= (prn-str {:name "Alice"})
             (get-in (second @calls) [:messages 0 :content])))))

  (testing "generate accepts a result as input, unwraps :text"
    (let [calls (atom [])
          provider (->MockProvider
                     (atom [{:type :content :content "response"}])
                     {:model "test-model"}
                     calls)
          ;; Simulate a previous result
          prev-result (llm/generate provider "first call")]
      ;; Reset responses for next call
      (reset! (.responses provider) [{:type :content :content "second response"}])
      ;; Pass the result directly as input
      (let [result (llm/generate provider prev-result)]
        (is (= "second response" (:text result)))
        ;; The second call should have received :text from prev-result as content
        (is (= "response" (get-in (second @calls) [:messages 0 :content])))))))

(deftest test-generate-result-threading
  (testing "->> threading works without :text extraction"
    (let [call-count (atom 0)
          provider (->MockProvider
                     (atom [{:type :content :content "step1"}])
                     {:model "test-model"}
                     (atom []))
          run (fn [input]
                (swap! call-count inc)
                (let [n @call-count]
                  (reset! (.responses provider)
                          [{:type :content :content (str "step" (inc n))}]))
                (llm/generate provider input))]
      (let [result (->> "start"
                        run
                        run
                        run)]
        (is (contains? result :text))
        (is (string? (str result)))
        (is (= 3 @call-count))))))

(deftest test-generate-result-print
  (testing "pr-str renders as a map"
    (let [provider (mock-provider [{:type :content :content "hi"}])
          result (llm/generate provider "test")
          printed (pr-str result)]
      (is (clojure.string/includes? printed ":text"))
      (is (clojure.string/includes? printed "\"hi\"")))))

;; ════════════════════════════════════════════════════════════════════
;; generate callback tests
;; ════════════════════════════════════════════════════════════════════

(deftest test-generate-on-text-callback
  (testing ":on-text fires for each content chunk"
    (let [chunks (atom [])
          provider (mock-provider [{:type :content :content "Hello "}
                                   {:type :content :content "world!"}])
          result (llm/generate provider {:on-text #(swap! chunks conj %)} "test")]
      (is (= ["Hello " "world!"] @chunks))
      (is (= "Hello world!" (:text result))))))

(deftest test-generate-on-tool-calls-callback
  (testing ":on-tool-calls fires before tool execution"
    (let [seen (atom nil)
          exec-order (atom [])
          provider (mock-provider [{:type :tool-call :index 0 :id "c1" :name "ping" :arguments ""}
                                   {:type :tool-call-delta :index 0 :arguments "{\"host\":\"x\"}"}])
          ping (with-meta
                 (fn [{:keys [host]}]
                   (swap! exec-order conj :executed)
                   (str "pong " host))
                 {:malli/schema [:=> [:cat [:map {:name "ping" :description "P"}
                                            [:host :string]]] :string]})
          result (llm/generate provider
                   {:tools [ping]
                    :on-tool-calls (fn [info]
                                    (swap! exec-order conj :on-tool-calls)
                                    (reset! seen info))}
                   "test")]
      ;; callback fired before execution
      (is (= [:on-tool-calls :executed] @exec-order))
      (is (= 1 (count (:tool-calls @seen))))
      (is (= "ping" (:name (first (:tool-calls @seen))))))))

(deftest test-generate-on-tool-result-callback
  (testing ":on-tool-result fires after each tool"
    (let [seen (atom [])
          provider (mock-provider [{:type :tool-call :index 0 :id "c1" :name "ping" :arguments ""}
                                   {:type :tool-call-delta :index 0 :arguments "{\"host\":\"a\"}"}])
          ping (with-meta
                 (fn [{:keys [host]}] (str "pong " host))
                 {:malli/schema [:=> [:cat [:map {:name "ping" :description "P"}
                                            [:host :string]]] :string]})
          result (llm/generate provider
                   {:tools [ping]
                    :on-tool-result (fn [info] (swap! seen conj info))}
                   "test")]
      (is (= 1 (count @seen)))
      (is (= "pong a" (:result (first @seen))))
      (is (nil? (:error (first @seen))))
      (is (= "ping" (:name (:tool-call (first @seen))))))))

(deftest test-generate-on-tool-result-with-error
  (testing ":on-tool-result reports errors"
    (let [seen (atom [])
          provider (mock-provider [{:type :tool-call :index 0 :id "c1" :name "boom" :arguments ""}
                                   {:type :tool-call-delta :index 0 :arguments "{\"x\":\"y\"}"}])
          boom (with-meta
                 (fn [_] (throw (ex-info "kaboom" {})))
                 {:malli/schema [:=> [:cat [:map {:name "boom" :description "B"}
                                            [:x :string]]] :string]})
          result (llm/generate provider
                   {:tools [boom]
                    :on-tool-result (fn [info] (swap! seen conj info))}
                   "test")]
      (is (= 1 (count @seen)))
      (is (some? (:error (first @seen))))
      (is (clojure.string/starts-with? (:result (first @seen)) "Error:")))))

(deftest test-generate-callbacks-without-tools
  (testing ":on-text works without tools"
    (let [chunks (atom [])
          provider (mock-provider [{:type :content :content "hi"}])
          result (llm/generate provider {:on-text #(swap! chunks conj %)} "test")]
      (is (= ["hi"] @chunks))
      (is (= "hi" (:text result)))))

  (testing ":on-tool-calls and :on-tool-result are silently ignored without tools"
    (let [provider (mock-provider [{:type :content :content "hi"}])
          result (llm/generate provider
                   {:on-tool-calls (fn [_] (throw (Exception. "should not fire")))
                    :on-tool-result (fn [_] (throw (Exception. "should not fire")))}
                   "test")]
      (is (= "hi" (:text result))))))
