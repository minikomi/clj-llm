(ns agent.core
  "Core agent system built on clj-llm.
   
   Provides a flexible agent framework with:
   - Tool calling capabilities
   - Memory/context management
   - Chain of thought reasoning
   - Multi-step planning"
  (:require [co.poyo.clj-llm.core :as llm]
            [co.poyo.clj-llm.errors :as errors]
            [clojure.core.async :as a :refer [go go-loop <! >! <!! chan]]
            [malli.core :as m]
            [cheshire.core :as json]))

;; ──────────────────────────────────────────────────────────────
;; Protocols
;; ──────────────────────────────────────────────────────────────

(defprotocol Tool
  "Protocol for agent tools"
  (tool-name [this]
    "Return the name of this tool")
  (tool-description [this]
    "Return a description of what this tool does")
  (tool-schema [this]
    "Return the Malli schema for tool parameters")
  (execute [this params]
    "Execute the tool with given parameters"))

(defprotocol Memory
  "Protocol for agent memory systems"
  (remember [this key value]
    "Store a value in memory")
  (recall [this key]
    "Retrieve a value from memory")
  (forget [this key]
    "Remove a value from memory")
  (memories [this]
    "Return all memories"))

;; ──────────────────────────────────────────────────────────────
;; Tool Implementation Helpers
;; ──────────────────────────────────────────────────────────────

(defrecord SimpleTool [name description schema fn]
  Tool
  (tool-name [_] name)
  (tool-description [_] description)
  (tool-schema [_] schema)
  (execute [_ params]
    (try
      {:success true
       :result (fn params)}
      (catch Exception e
        {:success false
         :error (str "Tool execution failed: " (.getMessage e))}))))

(defn make-tool
  "Create a simple tool from a function"
  [{:keys [name description schema fn]}]
  (->SimpleTool name description schema fn))

;; ──────────────────────────────────────────────────────────────
;; Memory Implementation
;; ──────────────────────────────────────────────────────────────

(defrecord SimpleMemory [store]
  Memory
  (remember [_ key value]
    (swap! store assoc key value))
  (recall [_ key]
    (get @store key))
  (forget [_ key]
    (swap! store dissoc key))
  (memories [_]
    @store))

(defn make-memory
  "Create a simple in-memory store"
  []
  (->SimpleMemory (atom {})))

;; ──────────────────────────────────────────────────────────────
;; Agent Implementation
;; ──────────────────────────────────────────────────────────────

(defn- format-tools-for-prompt
  "Format available tools for the LLM prompt"
  [tools]
  (str "Available tools:\n"
       (apply str
              (for [tool tools]
                (str "- " (tool-name tool) ": " (tool-description tool) "\n"
                     "  Parameters: " (pr-str (tool-schema tool)) "\n")))))

(defn- parse-tool-call
  "Parse a tool call from LLM response"
  [response]
  (try
    ;; Look for JSON blocks in the response
    (when-let [json-match (re-find #"```json\s*(\{[^`]+\})\s*```" response)]
      (json/parse-string (second json-match) true))
    (catch Exception _
      ;; Try to parse the whole response as JSON
      (try
        (json/parse-string response true)
        (catch Exception _
          nil)))))

(defn- execute-tool-call
  "Execute a tool call and return the result"
  [tools tool-call]
  (if-let [tool (first (filter #(= (tool-name %) (:tool tool-call)) tools))]
    (let [params (:parameters tool-call)
          schema (tool-schema tool)]
      (if (m/validate schema params)
        (execute tool params)
        {:success false
         :error (str "Invalid parameters: " (pr-str (m/explain schema params)))}))
    {:success false
     :error (str "Unknown tool: " (:tool tool-call))}))

(defrecord Agent [llm-provider system-prompt tools memory options])

(defn think
  "Make the agent think about a task and potentially use tools"
  [{:keys [llm-provider system-prompt tools memory options] :as agent} task]
  (let [;; Build context
        context (str system-prompt "\n\n"
                     (when (seq tools)
                       (str (format-tools-for-prompt tools) "\n"
                            "To use a tool, respond with JSON in this format:\n"
                            "```json\n"
                            "{\"tool\": \"tool-name\", \"parameters\": {...}}\n"
                            "```\n\n"))
                     (when-let [mems (seq (memories memory))]
                       (str "Memory:\n" (pr-str mems) "\n\n")))

        ;; Initial thought
        messages [{:role "system" :content context}
                  {:role "user" :content task}]

        ;; Reasoning loop
        max-steps (get options :max-steps 5)]

    (loop [messages messages
           steps 0
           observations []]
      (if (>= steps max-steps)
        {:success false
         :error "Maximum steps reached"
         :observations observations}

        (let [;; Get LLM response
              response (try
                         (llm/generate llm-provider nil {:messages messages})
                         (catch Exception e
                           {:error (str "LLM error: " (.getMessage e))}))

              ;; Check for errors
              _ (when (:error response)
                  (throw (ex-info "LLM generation failed" response)))]

          ;; Check if this is a tool call
          (if-let [tool-call (parse-tool-call response)]
            ;; Execute tool and continue
            (let [result (execute-tool-call tools tool-call)
                  observation (str "Tool: " (:tool tool-call) "\n"
                                   "Result: " (pr-str result))]
              (recur (conj messages
                           {:role "assistant" :content response}
                           {:role "user" :content observation})
                     (inc steps)
                     (conj observations {:step steps
                                         :action :tool-call
                                         :tool (:tool tool-call)
                                         :parameters (:parameters tool-call)
                                         :result result})))

            ;; Final answer
            {:success true
             :answer response
             :observations observations
             :steps steps}))))))

(defn create-agent
  "Create a new agent with the given configuration"
  [{:keys [llm-provider system-prompt tools memory options]
    :or {tools []
         memory (make-memory)
         options {}}}]
  (map->Agent {:llm-provider llm-provider
               :system-prompt (or system-prompt "You are a helpful AI assistant.")
               :tools tools
               :memory memory
               :options options}))

;; ──────────────────────────────────────────────────────────────
;; Conversation Management
;; ──────────────────────────────────────────────────────────────

(defn converse
  "Have a conversation with the agent"
  [{:keys [llm-provider system-prompt memory] :as agent} messages]
  (let [context (str system-prompt "\n\n"
                     (when-let [mems (seq (memories memory))]
                       (str "Memory:\n" (pr-str mems) "\n\n")))

        full-messages (into [{:role "system" :content context}]
                            messages)]

    (llm/generate llm-provider nil {:messages full-messages})))

(defn stream-converse
  "Stream a conversation response from the agent"
  [{:keys [llm-provider system-prompt memory] :as agent} messages]
  (let [context (str system-prompt "\n\n"
                     (when-let [mems (seq (memories memory))]
                       (str "Memory:\n" (pr-str mems) "\n\n")))

        full-messages (into [{:role "system" :content context}]
                            messages)]

    (llm/stream llm-provider nil {:messages full-messages})))

;; ──────────────────────────────────────────────────────────────
;; Planning and Reasoning
;; ──────────────────────────────────────────────────────────────

(def planning-schema
  "Schema for structured planning output"
  [:map
   [:goal :string]
   [:steps [:vector [:map
                     [:step :int]
                     [:action :string]
                     [:reason :string]
                     [:depends-on {:optional true} [:vector :int]]]]]
   [:success-criteria [:vector :string]]])

(defn plan
  "Make the agent create a plan for a complex task"
  [{:keys [llm-provider] :as agent} goal]
  (let [prompt (str "Create a detailed plan to achieve this goal: " goal "\n\n"
                    "Break it down into clear, actionable steps.")

        response (llm/generate llm-provider prompt
                               {:schema planning-schema
                                :system-prompt "You are a strategic planning AI. Create clear, logical plans."})]
    response))

;; ──────────────────────────────────────────────────────────────
;; Chain of Thought
;; ──────────────────────────────────────────────────────────────

(defn think-step-by-step
  "Use chain of thought reasoning"
  [{:keys [llm-provider] :as agent} problem]
  (let [cot-prompt (str problem "\n\n"
                        "Let's think step by step:")

        response (llm/generate llm-provider cot-prompt
                               {:system-prompt "You are a logical reasoning AI. Think through problems step by step, showing your work."})]
    response))