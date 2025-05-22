(ns co.poyo.clj-llm.core-test
  (:require [clojure.test :refer :all]
            [co.poyo.clj-llm.core :as llm-core]
            [co.poyo.clj-llm.sse :as sse]
            [co.poyo.clj-llm.protocol :as proto]
            [co.poyo.clj-llm.registry :as reg]
            [promesa.core :as p]
            [cheshire.core :as json]
            [malli.core :as m])
  (:import [java.io ByteArrayInputStream InputStream]))

;; --- Mock Backend Setup ---

(defrecord MockLLMBackend [config]
  proto/LLMBackend
  (-models [_] ["mock-model"])
  (-opts-schema [_ _model-id] [:map])
  (-raw-stream [_model-id _prompt _opts]
    ;; This stream is often overridden by mocking sse/stream->events directly in tests
    {:stream (ByteArrayInputStream. (.getBytes "" "UTF-8"))
     :metadata {:model-id "mock-model"}})
  (-raw-chat-stream [_ _messages _opts]
    ;; Similar to -raw-stream, primarily for satisfying the protocol
    {:stream (ByteArrayInputStream. (.getBytes "" "UTF-8"))
     :metadata {:model-id "mock-model"}}))

(defonce mock-backend-registered
  (reg/register-backend! :mock (MockLLMBackend. {})))

;; --- Helper Functions ---

(defn- make-content-event [content]
  {:choices [{:delta {:content content}}]})

(defn- make-tool-call-delta-event [index id name args-str]
  {:choices [{:delta {:tool_calls [{:index index :id id :function {:name name :arguments args-str}}]}}]})

(defn- make-usage-event [usage-map]
  {:usage usage-map})

;; --- Tests for llm-core/prompt ---

(deftest prompt-test
  (testing "Basic prompt functionality with mocked SSE"
    (let [mock-events-ch (p/chan)
          prompt-str "Hello, world!"]
      (with-redefs [sse/stream->events (fn [input-stream]
                                         ;; We could inspect input-stream if needed, but here just return the mock channel
                                         mock-events-ch)]
        (let [response-map (llm-core/prompt :mock/mock-model prompt-str {})]
          (is (identical? mock-events-ch (:chunks response-map)) "Chunks should be the sse channel")
          (is (p/promise? (:text response-map)) "Text should be a promise")
          (is (p/promise? (:usage response-map)) "Usage should be a promise")
          (is (p/promise? (:json response-map)) "JSON should be a promise")
          (is (p/promise? (:tool-calls response-map)) "Tool-calls should be a promise")
          (is (p/promise? (:structured-output response-map)) "Structured-output should be a promise")

          ;; Simulate SSE events
          (p/put! mock-events-ch (make-content-event "Hello"))
          (p/put! mock-events-ch (make-content-event ", "))
          (p/put! mock-events-ch (make-content-event "AI!"))
          (p/put! mock-events-ch (make-usage-event {:prompt_tokens 5 :completion_tokens 3 :total_tokens 8}))
          (p/close! mock-events-ch)

          (is (= "Hello, AI!" @(:text response-map)) "Text promise should resolve to concatenated content")
          (is (= {:prompt_tokens 5 :completion_tokens 3 :total_tokens 8} @(:usage response-map)) "Usage promise should resolve")
          (is (= [(make-content-event "Hello")
                  (make-content-event ", ")
                  (make-content-event "AI!")
                  (make-usage-event {:prompt_tokens 5 :completion_tokens 3 :total_tokens 8})]
                 @(:json response-map)) "JSON promise should resolve to all events")
          (is (empty? @(:tool-calls response-map)) "Tool-calls should be empty if no tool_calls in events")
          (is (nil? @(:structured-output response-map)) "Structured-output should be nil if no schema/tool-calls"))))

  (testing "Prompt with tool calls and structured output"
    (let [mock-events-ch (p/chan)
          tool-schema [:map [:location :string]]]
      (with-redefs [sse/stream->events (fn [_] mock-events-ch)]
        (let [response-map (llm-core/prompt :mock/mock-model "What's the weather in L.A.?" {:schema tool-schema})]
          (p/put! mock-events-ch (make-tool-call-delta-event 0 "tool1" "get_weather" "{\"loc"))
          (p/put! mock-events-ch (make-tool-call-delta-event 0 "tool1" "get_weather" "ation\":"))
          (p/put! mock-events-ch (make-tool-call-delta-event 0 "tool1" "get_weather" " \"Los Angeles\"}"))
          (p/put! mock-events-ch (make-content-event "Okay, checking...")) ; Content alongside tool call
          (p/close! mock-events-ch)

          (let [tool-calls @(:tool-calls response-map)]
            (is (= 1 (count tool-calls)))
            (is (= {:index 0
                    :id "tool1"
                    :name "get_weather"
                    :args-edn {:location "Los Angeles"}}
                   (first tool-calls))))
          (is (= {:location "Los Angeles"} @(:structured-output response-map)) "Structured-output should parse from tool call"))))

  (testing "on-complete callback"
    (let [mock-events-ch (p/chan)
          on-complete-result (p/promise)
          on-complete-fn (fn [resolved-map] (p/resolve! on-complete-result resolved-map))]
      (with-redefs [sse/stream->events (fn [_] mock-events-ch)]
        (llm-core/prompt :mock/mock-model "Test on-complete" {:on-complete on-complete-fn})

        (p/put! mock-events-ch (make-content-event "Data"))
        (p/put! mock-events-ch (make-usage-event {:total_tokens 1}))
        (p/close! mock-events-ch)

        (let [resolved-val @on-complete-result]
          (is (= "Data" (:text resolved-val)))
          (is (= {:total_tokens 1} (:usage resolved-val)))
          (is (some? (:json resolved-val)))
          (is (p/chan? (:chunks resolved-val))))))))

;; --- Tests for llm-core/conversation ---

(deftest conversation-test
  (testing "Conversation flow and history management"
    (let [history-atom (atom [])
          mock-prompt-calls (atom [])
          ;; Mock llm-core/prompt to control its behavior for conversation tests
          mock-prompt-fn (fn [model content opts]
                           (swap! mock-prompt-calls conj {:model model :content content :opts opts})
                           ;; Return a map of already resolved promises for simplicity
                           (let [text-response (str "Assistant to: " content)
                                 usage-response {:total_tokens (+ (count content) (count text-response))}]
                             {:chunks (p/chan) ;; Won't be used much in this mock
                              :text (p/resolved text-response)
                              :usage (p/resolved usage-response)
                              :json (p/resolved [{:choices [{:delta {:content text-response}}]}
                                                 {:usage usage-response}])
                              :tool-calls (p/resolved [])
                              :structured-output (p/resolved nil)}))
          ;; Create conversation with the mocked prompt
          convo (with-redefs [llm-core/prompt mock-prompt-fn
                              ;; conversations internal on-complete needs the actual atom
                              ;; but we can spy on its effects via mock-prompt-fn's opts
                              ]
                  (llm-core/conversation :mock/mock-model))]

      ;; First user message
      (let [resp1 @((:prompt convo) "User message 1")]
        (is (= "Assistant to: User message 1" @(:text resp1)))
        (is (= [{:role :user :content "User message 1"}
                {:role :assistant :content "Assistant to: User message 1"}] @(:history convo))))

      (let [first-call (first @mock-prompt-calls)]
        (is (= :mock/mock-model (:model first-call)))
        (is (= "User message 1" (:content first-call)))
        (is (empty? (:history (:opts first-call)))) ; History for API call is before current user msg
        (is (fn? (:on-complete (:opts first-call)))))

      ;; Second user message
      (reset! mock-prompt-calls []) ; Clear for next call
      (let [resp2 @((:prompt convo) "User message 2" {:temperature 0.5})]
        (is (= "Assistant to: User message 2" @(:text resp2)))
        (is (= [{:role :user :content "User message 1"}
                {:role :assistant :content "Assistant to: User message 1"}
                {:role :user :content "User message 2"}
                {:role :assistant :content "Assistant to: User message 2"}] @(:history convo))))

      (let [second-call (first @mock-prompt-calls)]
        (is (= :mock/mock-model (:model second-call)))
        (is (= "User message 2" (:content second-call)))
        (is (= [{:role :user :content "User message 1"}
                {:role :assistant :content "Assistant to: User message 1"}]
               (:history (:opts second-call)))) ; History for API call
        (is (= 0.5 (:temperature (:opts second-call))))
        (is (fn? (:on-complete (:opts second-call)))))


      ((:clear convo))
      (is (empty? @(:history convo))))))

;; --- Tests for llm-core/call-function-with-llm ---

(m/=> get-city-weather [:=> [:cat [:map [:city {:optional true} :string]]] any?])
(defn get-city-weather [{:keys [city]}]
  (if (= city "Lund")
    {:temperature 20 :condition "Sunny"}
    {:temperature 25 :condition "Cloudy"}))

(deftest call-function-with-llm-test
  (testing "Successfully calling a function with LLM"
    (let [mock-response {:structured-output (p/resolved {:city "Lund"})}
          f-schema (llm-core/sch/instrumented-function->malli-schema get-city-weather)]
      (with-redefs [llm-core/prompt (fn [_model-id _content _opts] mock-response)]
        (let [result @(llm-core/call-function-with-llm #'get-city-weather :mock/mock-model "Weather in Lund?")]
          (is (= {:temperature 20 :condition "Sunny"} result))))))

  (testing "Function call with schema validation failure"
    (let [mock-response {:structured-output (p/resolved {:city 123})} ; Invalid arg type
          f-schema (llm-core/sch/instrumented-function->malli-schema get-city-weather)]
      (with-redefs [llm-core/prompt (fn [_model-id _content _opts] mock-response)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Function arguments did not match schema"
              @(llm-core/call-function-with-llm #'get-city-weather :mock/mock-model "Weather in Lund?"))))))

  (testing "Function call skipping schema validation"
    (let [mock-response {:structured-output (p/resolved {:city 123})} ; Invalid arg type, but validation skipped
          f-schema (llm-core/sch/instrumented-function->malli-schema get-city-weather)]
      (with-redefs [llm-core/prompt (fn [_model-id _content _opts] mock-response)]
        ;; This will likely fail inside the function `get-city-weather` if it expects a string
        ;; but the call itself (up to the point of applying the function) should not throw validation error
        (is (thrown? ClassCastException @(llm-core/call-function-with-llm #'get-city-weather :mock/mock-model "Weather in Lund?" {:validate? false})))
        )))
)

;; To run tests:
;; (clojure.test/run-tests 'co.poyo.clj-llm.core-test)
