(ns co.poyo.clj-llm.event-parsing-test
  "Tests for SSE event parsing using real fixture data.
   Fixtures are raw SSE streams captured from providers.
   Tests replay them through the SSE parser and backend event converters."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [co.poyo.clj-llm.stream :as stream]
            [co.poyo.clj-llm.backend.openai]))

;; ════════════════════════════════════════════════════════════════════
;; Helpers
;; ════════════════════════════════════════════════════════════════════

(defn parse-fixture
  "Parse an SSE fixture file into data maps using the same path as production."
  [fixture-path]
  (with-open [reader (io/reader (io/resource fixture-path))]
    (into [] (keep stream/parse-sse-data) (line-seq reader))))

;; ════════════════════════════════════════════════════════════════════
;; OpenAI event converter — delegates to real implementation
;; ════════════════════════════════════════════════════════════════════

(def ^:private data->events* @#'co.poyo.clj-llm.backend.openai/data->events)

(defn openai-events
  "Convert parsed SSE data to internal events via real openai backend. Returns seq or nil."
  ([data] (data->events* data nil nil))
  ([data schema tools] (data->events* data schema tools)))

(defn openai-event
  "Convert parsed SSE data to a single internal event (first of seq). For single-event tests."
  ([data] (first (openai-events data)))
  ([data schema tools] (first (openai-events data schema tools))))

(defn replay-openai-fixture
  "Parse an SSE fixture and convert all events through the OpenAI event converter.
   Returns vector of non-nil internal events."
  ([fixture-path] (replay-openai-fixture fixture-path nil nil))
  ([fixture-path schema tools]
   (->> (parse-fixture fixture-path)
        (mapcat #(openai-events % schema tools))
        vec)))

(defn assemble-tool-calls
  "Replay the tool-call assembly logic from core/consume-events.
   Returns the final assembled tool calls."
  [internal-events]
  (reduce
   (fn [{:keys [tool-calls tool-call-positions]} event]
     (case (:type event)
       :tool-call
       (let [idx (or (:index event) (count tool-calls))
             call (assoc event :arguments (or (:arguments event) ""))]
         {:tool-calls (conj tool-calls call)
          :tool-call-positions (assoc tool-call-positions idx (count tool-calls))})

       :tool-call-delta
       (let [pos (get tool-call-positions (:index event))]
         {:tool-calls (if pos
                        (update-in tool-calls [pos :arguments] str (:arguments event))
                        tool-calls)
          :tool-call-positions tool-call-positions})

       ;; pass through other events
       {:tool-calls tool-calls :tool-call-positions tool-call-positions}))
   {:tool-calls [] :tool-call-positions {}}
   internal-events))

;; ════════════════════════════════════════════════════════════════════
;; Tests: SSE Parser
;; ════════════════════════════════════════════════════════════════════

(deftest test-sse-parser-simple-text
  (testing "SSE parser produces data maps from simple text fixture"
    (let [events (parse-fixture "fixtures/openai-simple-text.sse")]
      (is (pos? (count events)) "Should produce events")
      (is (every? map? events) "All events should be data maps")
      (is (some #(get-in % [:choices 0 :delta :content]) events)
          "Should have content chunks"))))

(deftest test-sse-parser-tool-calls
  (testing "SSE parser handles tool call fixtures"
    (doseq [fixture ["fixtures/openai-tool-calls.sse"
                     "fixtures/minimax-tool-calls.sse"]]
      (testing fixture
        (let [events (parse-fixture fixture)]
          (is (pos? (count events))))))))

;; ════════════════════════════════════════════════════════════════════
;; Tests: OpenAI Simple Text
;; ════════════════════════════════════════════════════════════════════

(deftest test-openai-simple-text-events
  (testing "Simple text stream produces content events and no empty strings"
    (let [events (replay-openai-fixture "fixtures/openai-simple-text.sse")]
      (is (seq events) "Should produce events")

      ;; No empty content events
      (let [content-events (filter #(= :content (:type %)) events)]
        (is (pos? (count content-events)))
        (doseq [e content-events]
          (is (not-empty (:content e)) "Content events should not have empty strings")))

      ;; Text assembles correctly
      (let [text (->> events
                      (filter #(= :content (:type %)))
                      (map :content)
                      (apply str))]
        (is (not-empty text))
        (is (re-find #"(?i)hello" text) "Response should contain hello")))))

(deftest test-openai-simple-text-usage
  (testing "Usage event is captured (not swallowed by empty content)"
    (let [events (replay-openai-fixture "fixtures/openai-simple-text.sse")
          usage-events (filter #(= :usage (:type %)) events)]
      (is (= 1 (count usage-events)) "Should have exactly one usage event")
      (let [usage (first usage-events)]
        (is (pos? (:prompt-tokens usage)))
        (is (pos? (:completion-tokens usage)))
        (is (pos? (:total-tokens usage)))))))

;; ════════════════════════════════════════════════════════════════════
;; Tests: OpenAI Tool Calls
;; ════════════════════════════════════════════════════════════════════

(deftest test-openai-tool-call-events
  (testing "Tool call stream from GPT-4.1-nano"
    (let [tools [:placeholder] ;; truthy, enables tool-call path
          events (replay-openai-fixture "fixtures/openai-tool-calls.sse" nil tools)
          tc-events (filter #(= :tool-call (:type %)) events)
          delta-events (filter #(= :tool-call-delta (:type %)) events)]
      (is (= 2 (count tc-events)) "Should have 2 tool calls")
      (is (pos? (count delta-events)) "Should have argument deltas")
      ;; First tool call is get_weather
      (is (= "get_weather" (:name (first tc-events))))
      ;; Second is search_restaurants
      (is (= "search_restaurants" (:name (second tc-events)))))))

(deftest test-openai-tool-call-assembly
  (testing "Tool call arguments assemble correctly from GPT-4.1-nano"
    (let [tools [:placeholder]
          events (replay-openai-fixture "fixtures/openai-tool-calls.sse" nil tools)
          {:keys [tool-calls]} (assemble-tool-calls events)]
      (is (= 2 (count tool-calls)))

      ;; get_weather
      (let [tc1 (first tool-calls)]
        (is (= "get_weather" (:name tc1)))
        (is (str/includes? (:arguments tc1) "Tokyo"))
        ;; Should be valid JSON
        (is (str/starts-with? (.trim (:arguments tc1)) "{")))

      ;; search_restaurants
      (let [tc2 (second tool-calls)]
        (is (= "search_restaurants" (:name tc2)))
        (is (str/includes? (:arguments tc2) "Tokyo"))
        (is (str/includes? (:arguments tc2) "ramen"))))))

(deftest test-openai-tool-call-usage
  (testing "Usage is captured even with tool calls"
    (let [tools [:placeholder]
          events (replay-openai-fixture "fixtures/openai-tool-calls.sse" nil tools)
          usage-events (filter #(= :usage (:type %)) events)]
      (is (= 1 (count usage-events))
          "Should capture usage from chunk with empty content"))))

;; ════════════════════════════════════════════════════════════════════
;; Tests: Minimax Tool Calls (different chunking behavior)
;; ════════════════════════════════════════════════════════════════════

(deftest test-minimax-tool-call-events
  (testing "Tool call stream from minimax (different delta chunking)"
    (let [tools [:placeholder]
          events (replay-openai-fixture "fixtures/minimax-tool-calls.sse" nil tools)
          tc-events (filter #(= :tool-call (:type %)) events)]
      (is (= 2 (count tc-events)) "Should have 2 tool calls")
      (is (= "get_weather" (:name (first tc-events))))
      (is (= "search_restaurants" (:name (second tc-events)))))))

(deftest test-minimax-tool-call-assembly
  (testing "Minimax tool call arguments assemble to valid JSON"
    (let [tools [:placeholder]
          events (replay-openai-fixture "fixtures/minimax-tool-calls.sse" nil tools)
          {:keys [tool-calls]} (assemble-tool-calls events)]
      (is (= 2 (count tool-calls)))

      ;; get_weather - should be valid JSON with opening {
      (let [args (:arguments (first tool-calls))]
        (is (str/starts-with? (.trim args) "{")
            (str "Arguments should start with {, got: " (pr-str args)))
        (is (str/includes? args "Tokyo")))

      ;; search_restaurants
      (let [args (:arguments (second tool-calls))]
        (is (str/starts-with? (.trim args) "{")
            (str "Arguments should start with {, got: " (pr-str args)))
        (is (str/includes? args "Tokyo"))
        (is (re-find #"(?i)ramen" args))))))

(deftest test-minimax-usage-not-swallowed
  (testing "Minimax usage event is not swallowed by empty content strings"
    (let [events (replay-openai-fixture "fixtures/minimax-tool-calls.sse")
          usage-events (filter #(= :usage (:type %)) events)]
      (is (= 1 (count usage-events))
          "Usage should not be swallowed by empty content")
      (when (seq usage-events)
        (let [usage (first usage-events)]
          (is (pos? (:prompt-tokens usage)))
          (is (pos? (:completion-tokens usage))))))))

;; ════════════════════════════════════════════════════════════════════
;; Tests: Empty string edge cases (the bug we fixed)
;; ════════════════════════════════════════════════════════════════════

(deftest test-empty-content-does-not-swallow-usage
  (testing "A chunk with empty content and usage data produces :usage not :content"
    ;; This is the exact bug: {"choices":[{"delta":{"content":""}}], "usage":{...}}
    (let [data {:choices [{:index 0
                           :delta {:role "assistant" :content ""}}]
                :usage {:prompt-tokens 10
                        :completion-tokens 5
                        :total-tokens 15}}]
      (is (= :usage (:type (openai-event data)))
          "Should produce :usage, not :content or nil"))))

(deftest test-empty-content-produces-nil
  (testing "A chunk with only empty content produces nil (filtered out)"
    (let [data {:choices [{:index 0
                           :delta {:role "assistant" :content ""}}]}]
      (is (nil? (openai-event data))
          "Empty content with no other data should produce nil"))))

(deftest test-concurrent-tool-calls-in-single-chunk
  (testing "Multiple tool calls in a single SSE chunk are all emitted"
    (let [data {:choices [{:index 0
                           :delta {:role "assistant"
                                   :content nil
                                   :tool-calls [{:id "call_1"
                                                  :index 0
                                                  :function {:name "get_weather"
                                                             :arguments ""}}
                                                 {:id "call_2"
                                                  :index 1
                                                  :function {:name "search_restaurants"
                                                             :arguments ""}}]}}]}
          result (openai-events data nil [:some-tool])]
      (is (= 2 (count result)) "Should have 2 tool call events")
      (is (= "get_weather" (:name (first result))))
      (is (= "search_restaurants" (:name (second result)))))))

(deftest test-concurrent-tool-call-deltas
  (testing "Multiple tool call argument deltas in a single chunk are all emitted"
    (let [data {:choices [{:index 0
                           :delta {:tool-calls [{:index 0
                                                  :function {:arguments "{\"city\""}}
                                                 {:index 1
                                                  :function {:arguments "{\"query\""}}]}}]}
          result (openai-events data nil [:some-tool])]
      (is (= 2 (count result)) "Should have 2 delta events")
      (is (= :tool-call-delta (:type (first result))))
      (is (= :tool-call-delta (:type (second result)))))))

(deftest test-single-tool-call-returns-seq
  (testing "Single tool call in chunk returns a seq with one event"
    (let [data {:choices [{:index 0
                           :delta {:tool-calls [{:id "call_1"
                                                  :index 0
                                                  :function {:name "get_weather"
                                                             :arguments ""}}]}}]}
          result (openai-events data nil [:some-tool])]
      (is (= 1 (count result)))
      (is (= :tool-call (:type (first result)))))))

(deftest test-null-content-with-tool-calls
  (testing "A chunk with null content and tool_calls produces tool event"
    (let [data {:choices [{:index 0
                           :delta {:role "assistant"
                                   :content nil
                                   :tool-calls [{:id "call_123"
                                                  :index 0
                                                  :function {:name "get_weather"
                                                             :arguments ""}}]}}]}]
      (is (= :tool-call (:type (openai-event data nil [:some-tool])))
          "Should produce :tool-call when tools are present"))))
