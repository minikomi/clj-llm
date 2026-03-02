(ns co.poyo.clj-llm.anthropic-test
  (:require [clojure.test :refer [deftest testing is]]
            [co.poyo.clj-llm.backends.anthropic]))

(def ^:private data->event @#'co.poyo.clj-llm.backends.anthropic/data->event)
(def ^:private build-body @#'co.poyo.clj-llm.backends.anthropic/build-body)

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
