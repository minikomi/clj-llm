(ns co.poyo.clj-llm.sse-test
  (:require [clojure.test :refer :all]
            [co.poyo.clj-llm.sse :as sse]
            [promesa.core :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io])
  (:import [java.io ByteArrayInputStream InputStream IOException]))

;; Helper to create an InputStream from a String
(defn string->input-stream ^InputStream [s]
  (ByteArrayInputStream. (.getBytes s "UTF-8")))

;; Helper to format SSE data lines
(defn sse-line [data]
  (str "data: " data "\n"))

(deftest stream-events-test
  (testing "Processing a simple SSE stream"
    (let [event1 {:id 1 :message "hello"}
          event2 {:id 2 :message "world"}
          sse-data (str (sse-line (json/generate-string event1))
                        "\n" ; empty line separator
                        (sse-line (json/generate-string event2))
                        (sse-line "[DONE]"))
          input-stream (string->input-stream sse-data)
          events-ch (sse/stream->events input-stream)
          results-p (p/into [] events-ch)]

      (is (= [event1 event2] @results-p) "Should correctly parse all events before [DONE]")))

  (testing "Processing stream with only [DONE]"
    (let [sse-data (sse-line "[DONE]")
          input-stream (string->input-stream sse-data)
          events-ch (sse/stream->events input-stream)
          results-p (p/into [] events-ch)]
      (is (empty? @results-p) "Should return an empty sequence for stream with only [DONE]")
      (is (p/closed? events-ch) "Channel should be closed after [DONE]")))

  (testing "Processing an empty stream"
    (let [sse-data ""
          input-stream (string->input-stream sse-data)
          events-ch (sse/stream->events input-stream)
          results-p (p/into [] events-ch)]
      (is (empty? @results-p) "Should return an empty sequence for an empty stream")
      (is (p/closed? events-ch) "Channel should be closed on EOF")))

  (testing "Stream with lines not starting with 'data:'"
    (let [event1 {:id "a"}
          sse-data (str "event: message\n"
                        (sse-line (json/generate-string event1))
                        ":this is a comment\n"
                        (sse-line "[DONE]"))
          input-stream (string->input-stream sse-data)
          events-ch (sse/stream->events input-stream)
          results-p (p/into [] events-ch)]
      (is (= [event1] @results-p) "Should ignore non-data lines and comments")))

  (testing "Stream with data that is not JSON (should be passed as is if not [DONE])"
    ;; According to sse.clj, it uses (json/parse-string data-str true)
    ;; which will throw if data-str is not valid JSON and not "[DONE]"
    ;; The test should reflect this.
    (let [event1-json {:id "valid-json"}
          raw-data-line "this is not json"
          sse-data (str (sse-line (json/generate-string event1-json))
                        (sse-line raw-data-line)
                        (sse-line "[DONE]"))
          input-stream (string->input-stream sse-data)
          events-ch (sse/stream->events input-stream)]
      ;; We expect json/parse-string to throw an exception for "this is not json"
      ;; The sse/stream->events function catches general exceptions and closes the channel.
      ;; It also prints an error. We can't easily test the print, but we can check channel state.
      (p/then (p/into [] events-ch)
              (fn [results]
                (is (= [event1-json] results) "Should process valid JSON before invalid.")
                (is (p/closed? events-ch) "Channel should be closed after error")))
      ;; It's hard to deterministically test the error logged by json/parse-string
      ;; without more advanced mocking of the parser or inspecting logs.
      ;; The main behavior is that the channel closes and prior events are processed.
      ))

  (testing "Stream ending abruptly without [DONE]"
    (let [event1 {:id 123}
          sse-data (sse-line (json/generate-string event1)) ; No [DONE], stream just ends
          input-stream (string->input-stream sse-data)
          events-ch (sse/stream->events input-stream)
          results-p (p/into [] events-ch)]
      (is (= [event1] @results-p) "Should process events received before EOF")
      (is (p/closed? events-ch) "Channel should be closed on EOF")))

  (testing "Input stream throwing an IOException during read"
    (let [faulty-input-stream (proxy [InputStream] []
                                (read [_ _ _] (throw (IOException. "Simulated read error")))
                                (close [])) ; Close can be a no-op or also throw if needed
          events-ch (sse/stream->events faulty-input-stream)
          results-p (p/into [] events-ch)]
      ;; Wait for the promise to complete, expecting an empty result due to immediate error
      (is (empty? @results-p) "Should not retrieve any events if stream read fails early")
      (is (p/closed? events-ch) "Channel should be closed when an IOException occurs")))

 (testing "Stream with empty data lines"
    (let [event1 {:id "first"}
          event2 {:id "second"}
          sse-data (str (sse-line (json/generate-string event1))
                        "data: \n" ;; empty data line, spec says it's an event with empty data
                        (sse-line (json/generate-string event2))
                        (sse-line "[DONE]"))
          input-stream (string->input-stream sse-data)
          events-ch (sse/stream->events input-stream)
          results-p (p/into [] events-ch)]
      ;; The current sse.clj implementation (json/parse-string "" true) would likely error on empty data.
      ;; Let's verify based on actual implementation. If parse-string "" true fails, it's caught.
      ;; If it parses to nil or an empty map, that's what we'd expect.
      ;; `(json/parse-string "" true)` actually throws `com.fasterxml.jackson.core.JsonParseException`
      ;; So, like the non-JSON test, it should process event1, then error, then close.
      (p/then (p/into [] events-ch)
              (fn [results]
                (is (= [event1] results) "Should process event1, then error on empty data line if it's not valid JSON.")
                (is (p/closed? events-ch) "Channel should be closed after error on empty data line.")))
      ))

  (testing "Stream with only newlines and then [DONE]"
    (let [sse-data "\n\n\n"
                      (sse-line "[DONE]")
          input-stream (string->input-stream sse-data)
          events-ch (sse/stream->events input-stream)
          results-p (p/into [] events-ch)]
      (is (empty? @results-p) "Should be empty if only newlines before [DONE]")
      (is (p/closed? events-ch) "Channel should be closed")))
      
  (testing "Stream with a very large event"
    (let [large-event-data (apply str (repeat 2048 "x")) ; Create a string larger than typical buffer
          event1 {:data large-event-data}
          sse-data (str (sse-line (json/generate-string event1))
                        (sse-line "[DONE]"))
          input-stream (string->input-stream sse-data)
          events-ch (sse/stream->events input-stream)
          results-p (p/into [] events-ch)]
      (is (= [event1] @results-p) "Should handle large events correctly.")))
)
;; Run all tests in the namespace
;; (run-tests)
