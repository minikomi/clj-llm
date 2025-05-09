(ns co.poyo.clj-llm.stream-test
  (:require [clojure.test :refer :all]
            [co.poyo.clj-llm.stream :as stream]
            [clojure.core.async :as async :refer [chan <!! >!! go <!]]
            [cheshire.core :as json]
            [clojure.java.io :as io])
  (:import [java.io ByteArrayInputStream InputStream]))

;; Helpers for testing

(defn string->input-stream
  "Convert a string to an InputStream"
  [s]
  (ByteArrayInputStream. (.getBytes s "UTF-8")))

(defn make-sse-event
  "Create a properly formatted SSE event string from a string or map"
  [data]
  (let [data-str (if (map? data)
                   (json/generate-string data)  ;; Using Cheshire for JSON encoding
                   data)]
    (str "data: " data-str "\n\n")))

;; Tests for extract-sse-data

(deftest test-extract-sse-data
  (testing "Extract data from valid SSE event string"
    (is (= "some data" (stream/extract-sse-data "data: some data"))))

  (testing "Return nil for empty string"
    (is (nil? (stream/extract-sse-data ""))))

  (testing "Return nil for nil input"
    (is (nil? (stream/extract-sse-data nil))))

  (testing "Return nil for non-data SSE lines"
    (is (nil? (stream/extract-sse-data "event: update")))))

;; Tests for parse-json-data

(deftest test-parse-json-data
  (testing "Parse valid JSON string"
    (is (= {:key "value"} (stream/parse-json-data "{\"key\": \"value\"}"))))

  (testing "Handle [DONE] special marker"
    (is (= {:done true} (stream/parse-json-data "[DONE]"))))

  (testing "Return nil for nil input"
    (is (nil? (stream/parse-json-data nil))))

  (testing "Return nil for empty string"
    (is (nil? (stream/parse-json-data ""))))

  (testing "Handle invalid JSON"
    (let [result (stream/parse-json-data "{invalid json}")]
      (is (contains? result :error))
      (is (string? (:error result))))))

;; Tests for create-stream-processor

(deftest test-create-stream-processor
  (testing "Default processor behavior"
    (let [processor (stream/create-stream-processor)]
      (is (= {:key "value"} (processor "{\"key\": \"value\"}")))))

  (testing "Custom extraction function"
    ;; Let's create an extraction function that always returns invalid JSON
    (let [extract-fn (fn [s] "{INVALID_JSON")
          processor (stream/create-stream-processor :extract-fn extract-fn)
          result (processor "{\"key\": \"value\"}")]
      (is (contains? result :error))
      (is (string? (:error result)))))

  (testing "Custom transform function"
    (let [transform-fn (fn [data] (assoc data :transformed true))
          processor (stream/create-stream-processor :transform-fn transform-fn)]
      (is (= {:key "value" :transformed true}
             (processor "{\"key\": \"value\"}")))))

  (testing "Exception handling"
    (let [processor (stream/create-stream-processor)]
      (is (= {:type :error, :message "Test exception"}
             (processor (Exception. "Test exception")))))))

;; Async Tests

(deftest test-read-input-stream
  (testing "Reading a simple SSE stream"
    (let [sse-data (make-sse-event "{\"key\": \"value\"}")
          input-stream (string->input-stream sse-data)
          events-chan (stream/read-input-stream input-stream 1024)
          result (<!! events-chan)]
      (is (= "data: {\"key\": \"value\"}" result))
      (is (nil? (<!! events-chan))))) ; Channel should be closed

  (testing "Reading multiple SSE events"
    (let [sse-data (str (make-sse-event {:id 1})
                         (make-sse-event {:id 2})
                         (make-sse-event {:id 3}))
          input-stream (string->input-stream sse-data)
          events-chan (stream/read-input-stream input-stream 1024)
          results [(<!! events-chan) (<!! events-chan) (<!! events-chan)]]
      (is (= ["data: {\"id\":1}" "data: {\"id\":2}" "data: {\"id\":3}"] results))
      (is (nil? (<!! events-chan))))) ; Channel should be closed

  (testing "Reading events with different line endings"
    (let [sse-data "data: {\"id\": 1}\r\n\r\ndata: {\"id\": 2}\n\n"
          input-stream (string->input-stream sse-data)
          events-chan (stream/read-input-stream input-stream 1024)
          results [(<!! events-chan) (<!! events-chan)]]
      (is (= ["data: {\"id\": 1}" "data: {\"id\": 2}"] results))
      (is (nil? (<!! events-chan)))))

  (testing "Exception handling"
    (let [events-chan (stream/read-input-stream
                       (proxy [InputStream] []
                         (read [_ _ _] (throw (Exception. "Test exception"))))
                       1024)
          result (<!! events-chan)]
      (is (instance? Exception result))
      (is (= "Test exception" (.getMessage result)))
      (is (nil? (<!! events-chan)))))) ; Channel should be closed

;; Test for process-sse

(deftest test-process-sse
  (testing "Basic processing of SSE events"
    (let [sse-data (str (make-sse-event {:id 1})
                         (make-sse-event {:id 2})
                         (make-sse-event "[DONE]"))
          input-stream (string->input-stream sse-data)
          results (atom [])
          done-called (atom false)
          events-chan (stream/process-sse
                       input-stream
                       {:on-content #(swap! results conj %)
                        :on-done #(reset! done-called true)})]

      ;; Wait for all events to be processed
      (Thread/sleep 100)

      (is (= [{:id 1} {:id 2}] @results))
      (is @done-called)))

  (testing "Exception handling in stream processing"
    ;; Create a failing input stream that throws an exception when read
    (let [exception-stream (proxy [InputStream] []
                             (read [buf off len]
                               (throw (Exception. "Test stream exception"))))
          error-received (atom nil)
          done-called (atom false)

          ;; Process the stream with proper error handling
          events-chan (stream/process-sse
                       exception-stream
                       {:on-content (fn [result] (println "Content received:" result))
                        :on-error (fn [err] (reset! error-received err))
                        :on-done (fn [] (reset! done-called true))})]

      ;; Wait for processing to complete
      (Thread/sleep 1000)

      ;; This time we're testing actual exception handling, which is more reliable
      (is (not (nil? @error-received)))
      (is (= "Test stream exception" @error-received))
      (is @done-called)))
  
  (testing "Stream exception handling"
    (let [input-stream (proxy [InputStream] []
                         (read [_ _ _] (throw (Exception. "Test error"))))
          error-message (atom nil)
          events-chan (stream/process-sse
                       input-stream
                       {:on-error #(reset! error-message %)
                        :on-done #()})]

      ;; Wait for error to be processed
      (Thread/sleep 100)

      (is (= "Test error" @error-message))))

  (testing "Custom extraction and transformation"
    (let [sse-data (make-sse-event "{\"value\": 42}")
          input-stream (string->input-stream sse-data)
          result (atom nil)
          extract-fn (fn [s] (stream/extract-sse-data s))
          transform-fn (fn [data] (update data :value inc))
          events-chan (stream/process-sse
                       input-stream
                       {:extract-fn extract-fn
                        :transform-fn transform-fn
                        :on-content #(reset! result %)
                        :on-done #()})]

      ;; Wait for event to be processed
      (Thread/sleep 100)

      (is (= {:value 43} @result))))

  (testing "Buffer size configuration"
    (let [sse-data (make-sse-event "{\"large\": \"data\"}")
          input-stream (string->input-stream sse-data)
          result (atom nil)
          events-chan (stream/process-sse
                       input-stream
                       {:buffer-size 1024 ; Explicitly set buffer size
                        :on-content #(reset! result %)
                        :on-done #()})]

      ;; Wait for event to be processed
      (Thread/sleep 100)

      (is (= {:large "data"} @result)))))

;; Integration test that simulates real-world usage

(deftest test-sse-integration
  (testing "Full SSE streaming pipeline"
    (let [sse-stream (str
                      (make-sse-event {:type "start", :id "12345"})
                      (make-sse-event {:type "progress", :completion 50})
                      (make-sse-event {:type "progress", :completion 100})
                      (make-sse-event "[DONE]"))
          input-stream (string->input-stream sse-stream)
          events (atom [])
          done? (atom false)

          ;; Configure handlers
          handlers {:on-content #(swap! events conj %)
                    :on-error #(println "Error:" %)
                    :on-done #(reset! done? true)}

          ;; Process the stream
          events-chan (stream/process-sse input-stream handlers)]

      ;; Wait for processing to complete
      (Thread/sleep 200)

      ;; Verify results
      (is @done?)
      (is (= 3 (count @events)))
      (is (= "start" (:type (first @events))))
      (is (= "progress" (:type (second @events))))
      (is (= 50 (:completion (second @events))))
      (is (= 100 (:completion (last @events)))))))
