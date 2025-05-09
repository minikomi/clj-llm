(ns co.poyo.clj-llm.backends.openai-test
  (:require [clojure.test :refer [deftest testing is are use-fixtures]]
            [clojure.core.async :as async :refer [chan go <!! >!! close!]]
            [co.poyo.clj-llm.backends.openai :as openai]
            [co.poyo.clj-llm.protocol :as proto]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.java.io :as io])
  (:import [java.io ByteArrayInputStream]
           [java.nio.charset StandardCharsets]
           java.net.http.HttpClient
           java.net.http.HttpClient$Version
           java.net.http.HttpRequest
           java.net.http.HttpResponse
           java.util.Base64
           ))

;; ==========================================
;; Test utilities and fixtures
;; ==========================================

(defn- input-stream-from-string [s]
  (ByteArrayInputStream. (.getBytes s StandardCharsets/UTF_8)))

(defn make-sse-event [data]
  (str "event: message\ndata: " (json/generate-string data) "\n\n"))

(defn make-done-event []
  "event: done\ndata: [DONE]\n\n")

(defn make-fake-sse-stream [events]
  (let [events-str (str/join "" (concat
                                 (map make-sse-event events)
                                 [(make-done-event)]))]
    (input-stream-from-string events-str)))

(defn with-mocked-http [f]
  (with-redefs [openai/make-openai-request
                (fn [model-id prompt-str opts]
                  (let [chunks-chan (chan)
                        metadata-atom (atom {})]
                    ;; Close the channel immediately for testing
                    (close! chunks-chan)
                    {:channel chunks-chan
                     :metadata metadata-atom}))]
    (f)))

;; ==========================================
;; Unit tests for pure functions
;; ==========================================

(deftest test-normalize-tool-calls
  (testing "normalizes tool call events correctly"
    (let [events [{:choices [{:delta {:tool_calls [{:index 0
                                                    :id "call_1"
                                                    :type "function"
                                                    :function {:name "get_weather"}}]}}]}
                  {:choices [{:delta {:tool_calls [{:index 0
                                                    :function {:arguments "{\"location\":"}}]}}]}
                  {:choices [{:delta {:tool_calls [{:index 0
                                                    :function {:arguments "\"New York\"}"}}]}}]}]
          result (openai/normalize-tool-calls events)
          ]

          (is (= 1 (count result)))
          (is (= "call_1" (-> result first :id)))
          (is (= "function" (-> result first :type)))
          (is (= "get_weather" (-> result first :function :name)))
          (is (= {:location "New York"}
                 (-> result first :function :arguments))))))

(deftest test-openai-transformer
  (testing "transforms content events correctly"
    (let [event {:choices [{:delta {:content "Hello world"}}]}
          result (-> (#'openai/openai-transformer event))]
      (is (= :content (:type result)))
      (is (= "Hello world" (:content result)))))

  (testing "transforms tool call events correctly"
    (let [event {:choices [{:delta {:tool_calls [{:index 0 :id "call_1"}]}}]}
          result (-> (#'openai/openai-transformer event))]
      (is (= :tool-call (:type result)))
      (is (= [{:index 0 :id "call_1"}] (:tool-calls result)))))

  (testing "transforms finish events correctly"
    (let [event {:choices [{:finish_reason "stop" :delta {}}]}
          result (-> (#'openai/openai-transformer event))]
      (is (= :finish (:type result)))
      (is (= "stop" (:reason result)))))

  (testing "transforms usage events correctly"
    (let [event {:usage {:prompt_tokens 10 :completion_tokens 20}}
          result (-> (#'openai/openai-transformer event))]
      (is (= :usage (:type result)))
      (is (= {:prompt_tokens 10 :completion_tokens 20} (:usage result))))))

(deftest test-build-request-body
  (testing "builds basic request body correctly"
    (let [model-id "gpt-4"
          prompt "Hello, world!"
          opts {}
          result (#'openai/build-request-body model-id prompt opts)]
      (is (= model-id (:model result)))
      (is (= [{:role "user" :content [{:type "text" :text "Hello, world!"}]}] (:messages result)))
      (is (true? (:stream result)))))

  (testing "includes optional parameters correctly"
    (let [model-id "gpt-4"
          prompt "Hello, world!"
          opts {:temperature 0.7
                :max-tokens 100
                :top-p 0.9
                :frequency-penalty 0.5
                :presence-penalty 0.5
                :stop "END"
                :tools [{:type "function" :function {:name "get_weather"}}]
                :tool-choice "auto"}
          result (#'openai/build-request-body model-id prompt opts)]
      (is (= 0.7 (:temperature result)))
      (is (= 100 (:max_tokens result)))
      (is (= 0.9 (:top_p result)))
      (is (= 0.5 (:frequency_penalty result)))
      (is (= 0.5 (:presence_penalty result)))
      (is (= ["END"] (:stop result)))
      (is (= [{:type "function" :function {:name "get_weather"}}] (:tools result)))
      (is (= "auto" (:tool_choice result))))))

;; ==========================================
;; Integration tests for protocol implementation
;; ==========================================

(deftest test-openai-backend-protocol
  (testing "implements LLMBackend protocol methods"
    (let [backend (openai/create-backend)]
      (is (satisfies? proto/LLMBackend backend))
      (is (= openai/openai-opts-schema (proto/-opts-schema backend "model-id"))))))

;; ==========================================
;; Tests for error handling
;; ==========================================

(deftest test-error-handling
  (testing "handles missing API key"
    (with-redefs [openai/get-env (fn [_] nil)]
      (let [backend (openai/create-backend)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No OpenAI API key provided"
                              (#'openai/get-api-key {}))))))

  (testing "handles unsupported attachment types"
    (let [invalid-attachment {:type :unsupported}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported attachment type"
                            (#'openai/process-attachment invalid-attachment))))))

;; ==========================================
;; Tests for make-message functionality
;; ==========================================

(deftest test-make-messages
  (testing "creates basic text message correctly"
    (let [prompt "Hello, world!"
          result (#'openai/make-messages prompt [])]
      (is (= 1 (count result)))
      (is (= "user" (-> result first :role)))
      (is (= [{:type "text" :text "Hello, world!"}] (-> result first :content)))))

  (testing "includes image attachments correctly"
    (with-redefs [openai/file-to-data-url (fn [_] "data:image/png;base64,test123")]
      (let [prompt "Describe this image"
            attachments [{:type :image :path "/fake/path.png"}]
            result (#'openai/make-messages prompt attachments)]
        (is (= 1 (count result)))
        (is (= "user" (-> result first :role)))
        (is (= 2 (count (-> result first :content))))
        (is (= {:type "text" :text "Describe this image"} (-> result first :content first)))
        (is (= {:type "image_url"
                :image_url {:url "data:image/png;base64,test123"}}
               (-> result first :content second)))))))
