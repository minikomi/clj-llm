#!/usr/bin/env bb

(ns tool-calling-example
  "Example demonstrating multi-tool calling with an agentic loop.

   This script shows how to:
   - Define multiple tools using Malli schemas
   - Let the LLM choose which tool(s) to call
   - Execute tools and feed results back
   - Loop until the LLM provides a final answer"
  (:require [co.poyo.clj-llm.core :as llm]
            [co.poyo.clj-llm.backends.openai :as openai]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.pprint :as pp]))

;; ════════════════════════════════════════════════════════════════════
;; Tool Definitions with Malli Schemas
;; ════════════════════════════════════════════════════════════════════

(def ping-schema
  "Schema for the ping tool"
  [:map {:name "ping"
         :description "Ping a host with a message and get a response"}
   [:host {:description "The host to ping"} :string]
   [:message {:description "Message to send"} :string]])

(defn ping
  "Simple ping tool that responds with a message"
  [{:keys [host message]}]
  (println (str "  🔧 Executing ping: host=" host " message=" message))
  {:status "success"
   :response (str "PONG from " host ": " message)
   :latency_ms 42})

(def get-weather-schema
  "Schema for the get-weather tool"
  [:map {:name "get_weather"
         :description "Get the current weather for a location"}
   [:location {:description "City name or location"} :string]
   [:units {:description "Temperature units"
            :optional true
            :default "celsius"}
    [:enum "celsius" "fahrenheit"]]])

(defn get-weather
  "Get weather for a location (mock implementation)"
  [{:keys [location units]}]
  (println (str "  🔧 Executing get_weather: location=" location " units=" (or units "celsius")))
  {:location location
   :temperature (if (= units "fahrenheit") 72 22)
   :units (or units "celsius")
   :conditions "Sunny"
   :humidity 65})

;; Map tool names to their implementations
(def tool-registry
  {"ping" ping
   "get_weather" get-weather})

;; Vector of all tool schemas for the LLM
(def available-tools
  [ping-schema
   get-weather-schema])

;; ════════════════════════════════════════════════════════════════════
;; Tool Calling Logic
;; ════════════════════════════════════════════════════════════════════

(defn execute-tool
  "Execute a tool call and return result message"
  [tool-call]
  (let [{:keys [id name arguments]} tool-call
        tool-fn (get tool-registry name)
        args (json/parse-string arguments true)]

    (println (str "\n  ⚙️  Tool: " name))
    (println "  📦 Args:")
    (pp/pprint args)

    (if tool-fn
      (let [result (tool-fn args)]
        (println "  ✅ Result:")
        (pp/pprint result)
        ;; Return a tool result message for the conversation
        {:role :tool
         :tool_call_id id
         :content (json/generate-string result)})
      (do
        (println (str "  ❌ Unknown tool: " name))
        {:role :tool
         :tool_call_id id
         :content (json/generate-string {:error (str "Unknown tool: " name)})}))))

(defn process-with-tools
  "Main agentic loop: call LLM, handle tool calls, repeat until done"
  [backend user-input]
  (println (str "\n" (str/join "" (repeat 70 "═"))))
  (println (str "User: " user-input))
  (println (str/join "" (repeat 70 "═")))

  (loop [context [{:role :user :content user-input}]
         iteration 1
         max-iterations 5]

    (when (> iteration max-iterations)
      (println "\n⚠️  Max iterations reached!")
      (throw (ex-info "Max iterations exceeded" {:iterations iteration})))

    (println (str "\n--- Iteration " iteration " ---"))

    ;; Call LLM with available tools
    (let [response (llm/prompt backend nil
                               {:message-history context
                                :tools available-tools
                                :tool-choice "auto"})]

      ;; Wait for response to complete
      (let [tool-calls @(:tool-calls response)
            text @(:text response)]

        ;; Show what the LLM returned
        (println "\n📋 LLM Response:")
        (println "  tool-calls:" (if tool-calls (str (count tool-calls) " call(s)") "nil"))
        (println "  text:" (if (str/blank? text) "(empty)" (str "\"" (subs text 0 (min 60 (count text))) (when (> (count text) 60) "...") "\"")))
        (when tool-calls
          (println "\n  Tool calls detail:")
          (doseq [tc tool-calls]
            (println (str "    - " (:name tc) " (id: " (:id tc) ")"))))

        (cond
          ;; LLM wants to call tools
          (seq tool-calls)
          (do
            (println (str "\n🔧 LLM called " (count tool-calls) " tool(s):"))

            ;; Add assistant message with tool calls to context
            (let [assistant-msg {:role :assistant
                                :tool_calls (mapv (fn [tc]
                                                    {:id (:id tc)
                                                     :type "function"
                                                     :function {:name (:name tc)
                                                               :arguments (:arguments tc)}})
                                                  tool-calls)}
                  ;; Execute all tools and get result messages
                  tool-results (mapv execute-tool tool-calls)

                  ;; Build new context
                  new-context (-> context
                                  (conj assistant-msg)
                                  (into tool-results))]

              (println "\n  ↻ Continuing with tool results...")
              (recur new-context (inc iteration) max-iterations)))

          ;; LLM provided a text response (no tool calls)
          :else
          (do
            (println (str "\n" (str/join "" (repeat 70 "─"))))
            (println "Assistant: " text)
            (println (str/join "" (repeat 70 "─")))

            (when-let [usage @(:usage response)]
              (println (str "\n💰 Usage: "
                           (or (:prompt-tokens usage) (:input-tokens usage)) " in / "
                           (or (:completion-tokens usage) (:output-tokens usage)) " out / "
                           (or (:total-tokens usage)
                               (+ (or (:prompt-tokens usage) (:input-tokens usage) 0)
                                  (or (:completion-tokens usage) (:output-tokens usage) 0))) " total")))

            text))))))

;; ════════════════════════════════════════════════════════════════════
;; Demo
;; ════════════════════════════════════════════════════════════════════

(defn -main [& args]
  (println "\n🤖 Multi-Tool Calling Example")
  (println "════════════════════════════════════════════════════════════════════\n")
  (println "This example demonstrates true multi-tool calling where:")
  (println "  1. Multiple tools are provided to the LLM")
  (println "  2. LLM autonomously chooses which tool(s) to call")
  (println "  3. Tools are executed and results fed back")
  (println "  4. Loop continues until LLM has the final answer\n")

  ;; Create backend
  (let [backend (-> (openai/->openai)
                    (llm/with-model "gpt-4o-mini")
                    (llm/with-system-prompt "You are a helpful assistant. Use the available tools when needed to answer user questions."))]

    ;; Example 1: Simple tool use
    (println "\n" (str/join "" (repeat 70 "═")))
    (println "EXAMPLE 1: Single tool use")
    (println (str/join "" (repeat 70 "═")))
    (process-with-tools backend "Ping the server at 'api.example.com' with the message 'health check'")

    ;; Example 2: Different tool
    (println "\n\n" (str/join "" (repeat 70 "═")))
    (println "EXAMPLE 2: Different tool")
    (println (str/join "" (repeat 70 "═")))
    (process-with-tools backend "What's the weather like in San Francisco?")

    ;; Example 3: Multiple tools in sequence
    (println "\n\n" (str/join "" (repeat 70 "═")))
    (println "EXAMPLE 3: Multiple tools")
    (println (str/join "" (repeat 70 "═")))
    (process-with-tools backend "Check the weather in Tokyo and then ping the server at 'weather-api.example.com' with the message 'fetching Tokyo weather'")

    ;; Example 4: No tool needed
    (println "\n\n" (str/join "" (repeat 70 "═")))
    (println "EXAMPLE 4: No tool needed")
    (println (str/join "" (repeat 70 "═")))
    (process-with-tools backend "What is 2 + 2?")

    (println "\n\n✨ Done!\n")))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
