(ns co.poyo.clj-llm.anthropic-test
  (:require [clojure.test :refer [deftest testing is]]
            [co.poyo.clj-llm.backend.anthropic]
            [co.poyo.clj-llm.protocol :as proto]))

(def ^:private data->event @#'co.poyo.clj-llm.backend.anthropic/data->event)
(def ^:private build-body @#'co.poyo.clj-llm.backend.anthropic/build-body)

;; ════════════════════════════════════════════════════════════════════
;; data->internal-event
;; ════════════════════════════════════════════════════════════════════

(deftest test-content-block-delta-text
  (let [event (data->event {:type "content_block_delta"
                            :delta {:text "Hello"}} nil nil)]
    (is (= {:type :content :content "Hello"} event))))

(deftest test-content-block-delta-empty
  (is (nil? (data->event {:type "content_block_delta"
                          :delta {:text ""}} nil nil))))

(deftest test-content-block-delta-schema-json
  (let [event (data->event {:type "content_block_delta"
                            :delta {:partial-json "{\"name\":"}}
                           :some-schema nil)]
    (is (= {:type :content :content "{\"name\":"} event))))

(deftest test-content-block-delta-tool-json
  (let [event (data->event {:type "content_block_delta"
                            :index 0
                            :delta {:partial-json "{\"city\":"}}
                           nil [:some-tool])]
    (is (= {:type :tool-call-delta :index 0 :arguments "{\"city\":"} event))))

(deftest test-content-block-start-tool
  (let [event (data->event {:type "content_block_start"
                            :index 1
                            :content_block {:type "tool_use"
                                            :id "toolu_123"
                                            :name "get_weather"}}
                           nil [:some-tool])]
    (is (= {:type :tool-call :id "toolu_123" :index 1
            :name "get_weather" :arguments ""}
           event))))

(deftest test-content-block-start-no-tools
  (is (nil? (data->event {:type "content_block_start"
                          :index 0
                          :content_block {:type "tool_use"
                                          :id "toolu_123"
                                          :name "get_weather"}}
                         nil nil))))

(deftest test-message-delta-usage
  (let [event (data->event {:type "message_delta"
                            :usage {:output-tokens 42}} nil nil)]
    (is (= :usage (:type event)))
    (is (= 42 (:output-tokens event)))))

(deftest test-message-start-usage
  (let [event (data->event {:type "message_start"
                            :message {:usage {:input-tokens 10}}} nil nil)]
    (is (= :usage (:type event)))
    (is (= 10 (:input-tokens event)))))

(deftest test-message-start-no-usage
  (is (nil? (data->event {:type "message_start" :message {}} nil nil))))

(deftest test-message-stop
  (is (= {:type :done} (data->event {:type "message_stop"} nil nil))))

(deftest test-ping-ignored
  (is (nil? (data->event {:type "ping"} nil nil))))

(deftest test-content-block-stop-ignored
  (is (nil? (data->event {:type "content_block_stop"} nil nil))))

(deftest test-error-event
  (let [event (data->event {:type "error" :error {:message "bad"}} nil nil)]
    (is (= :error (:type event)))))

(deftest test-unknown-event-type
  (is (nil? (data->event {:type "unknown_thing"} nil nil))))

;; ════════════════════════════════════════════════════════════════════
;; build-body
;; ════════════════════════════════════════════════════════════════════

(deftest test-build-body-basic
  (let [body (build-body "claude-3" nil [{:role "user" :content "hi"}]
                         nil nil nil {})]
    (is (= "claude-3" (:model body)))
    (is (= true (:stream body)))
    (is (= 4096 (:max_tokens body)))
    (is (nil? (:system body)))))

(deftest test-build-body-system-prompt
  (let [body (build-body "claude-3" "Be helpful" [{:role "user" :content "hi"}]
                         nil nil nil {})]
    (is (= "Be helpful" (:system body)))))

(deftest test-build-body-tool-choice-none-omitted
  (testing "tool_choice is omitted (not null) when 'none'"
    (let [body (build-body "claude-3" nil [{:role "user" :content "hi"}]
                           nil [[:=> [:cat [:map [:x :string]]] :string]] "none" {})]
      (is (not (contains? body :tool_choice))))))

(deftest test-build-body-tool-choice-auto
  (let [body (build-body "claude-3" nil [{:role "user" :content "hi"}]
                         nil [[:=> [:cat [:map [:x :string]]] :string]] "auto" {})]
    (is (= {:type "auto"} (:tool_choice body)))))

(deftest test-build-body-tool-choice-required
  (let [body (build-body "claude-3" nil [{:role "user" :content "hi"}]
                         nil [[:=> [:cat [:map [:x :string]]] :string]] "required" {})]
    (is (= {:type "any"} (:tool_choice body)))))

(deftest test-build-body-custom-max-tokens
  (let [body (build-body "claude-3" nil [{:role "user" :content "hi"}]
                         nil nil nil {:max-tokens 1000})]
    (is (= 1000 (:max_tokens body)))))

;; ════════════════════════════════════════════════════════════════════
;; parse-chunk round-trip — the mapcat bug regression test
;; ════════════════════════════════════════════════════════════════════

(def ^:private anthropic-backend* @#'co.poyo.clj-llm.backend.anthropic/backend)

(deftest test-parse-chunk-returns-vector
  (testing "parse-chunk always returns a vector for valid events"
    (let [b (anthropic-backend* {:api-key false})]
      ;; text content event
      (is (vector? (proto/parse-chunk b {:type "content_block_delta"
                                         :delta {:text "hello"}} nil nil)))
      (is (= :content (:type (first (proto/parse-chunk b {:type "content_block_delta"
                                                          :delta {:text "hello"}} nil nil)))))
      ;; nil event returns empty vector
      (is (= [] (proto/parse-chunk b {:type "ping"} nil nil)))
      ;; tool call event
      (is (vector? (proto/parse-chunk b {:type "content_block_start"
                                         :index 0
                                         :content_block {:type "tool_use"
                                                         :id "toolu_1"
                                                         :name "test"}} nil [:some-tool])))
      ;; error event
      (is (vector? (proto/parse-chunk b {:type "error"
                                         :error {:message "bad"}} nil nil))))))

(deftest test-parse-chunk-mapcat-safe
  (testing "parse-chunk results work correctly with mapcat"
    (let [b (anthropic-backend* {:api-key false})
          chunks [{:type "content_block_delta" :delta {:text "Hello "}}
                  {:type "content_block_delta" :delta {:text "world!"}}
                  {:type "message_delta" :usage {:output-tokens 5}
                   :delta {:stop_reason "end_turn"}}
                  {:type "message_stop"}]
          events (mapcat #(proto/parse-chunk b % nil nil) chunks)]
      ;; Should get: content, content, usage, finish, done
      (is (every? map? events))
      (is (every? #(contains? % :type) events))
      (is (= [:content :content :usage :finish :done] (mapv :type events)))
      (is (= "end_turn" (:reason (some #(when (= :finish (:type %)) %) events)))))))

;; ════════════════════════════════════════════════════════════════════
;; normalize-messages
;; ════════════════════════════════════════════════════════════════════

(def ^:private normalize-messages @#'co.poyo.clj-llm.backend.anthropic/normalize-messages)

;; ════════════════════════════════════════════════════════════════════
;; backend api-key-fn behavior
;; ════════════════════════════════════════════════════════════════════

(deftest test-backend-no-api-key-uses-default-fn
  (testing "backend with no :api-key uses default-api-key-fn (not constantly nil)"
    (let [b (anthropic-backend* {})]
      ;; The key-fn should NOT be (constantly nil) — it should throw
      ;; when ANTHROPIC_API_KEY env var is absent.
      (is (thrown? Exception ((.-api-key-fn b)))))))

(deftest test-backend-api-key-false-returns-nil
  (testing "backend with :api-key false returns nil from key fn"
    (let [b (anthropic-backend* {:api-key false})]
      (is (nil? ((.-api-key-fn b)))))))

(deftest test-backend-api-key-string-returns-string
  (testing "backend with :api-key string returns that string"
    (let [b (anthropic-backend* {:api-key "sk-test-123"})]
      (is (= "sk-test-123" ((.-api-key-fn b)))))))

(deftest test-normalize-messages-basic
  (testing "assistant tool_calls become Anthropic content array with tool_use blocks"
    (let [msgs [{:role :assistant :tool-calls [{:id "c1" :type "function"
                                                :function {:name "f" :arguments "{\"x\":1}"}}]
                 :content "ok"}]
          result (normalize-messages msgs)
          assistant-msg (first result)]
      (is (= :assistant (:role assistant-msg)))
      (is (vector? (:content assistant-msg)))
      (is (= [{:type "text" :text "ok"}
              {:type "tool_use" :id "c1" :name "f" :input {:x 1}}]
             (:content assistant-msg)))))

  (testing "tool messages become user messages with tool_result blocks"
    (let [msgs [{:role :tool :tool-call-id "c1" :content "result"}]
          result (normalize-messages msgs)
          tool-msg (first result)]
      (is (= :user (:role tool-msg)))
      (is (= [{:type "tool_result" :tool_use_id "c1" :content "result"}]
             (:content tool-msg)))))

  (testing "assistant tool_calls with parsed arguments (not string)"
    (let [msgs [{:role :assistant :tool-calls [{:id "c2" :type "function"
                                                :function {:name "g" :arguments {:y 2}}}]}]
          result (normalize-messages msgs)
          assistant-msg (first result)]
      (is (= [{:type "tool_use" :id "c2" :name "g" :input {:y 2}}]
             (:content assistant-msg))))))

(deftest test-normalize-messages-preserves-strings
  (testing "string content passes through unchanged"
    (let [msgs [{:role "user" :content "hello"}]
          result (normalize-messages msgs)]
      (is (= [{:role "user" :content "hello"}] result)))))
