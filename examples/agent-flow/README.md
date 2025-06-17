# Agent Flow Examples

This directory contains examples of building AI agents using the clj-llm library.

## Overview

The agent system provides:
- **Tool calling** - Agents can use tools to perform actions
- **Memory management** - Agents can remember information across interactions
- **Planning** - Agents can create multi-step plans
- **Chain of thought** - Step-by-step reasoning
- **Streaming responses** - Real-time interaction

## Architecture

```
agent.core      - Core agent protocols and implementation
agent.tools     - Collection of tools agents can use
agent.examples  - Example agents demonstrating different capabilities
```

## Running Examples

Make sure you have `OPENAI_API_KEY` environment variable set, then:

```bash
# Show available examples
clojure -M:run

# Run specific examples
clojure -M:weather   # Weather assistant
clojure -M:research  # Research assistant
clojure -M:chat      # Interactive chat

# Or run via main
clojure -M:run weather
clojure -M:run research
clojure -M:run chat
```

## Example Agents

### Weather Assistant
Demonstrates tool usage with weather lookup and calculations:
- Get weather for any location (mock data)
- Temperature conversions
- Current time

### Research Assistant
Shows planning and memory capabilities:
- Creates research plans
- Uses web search (mock)
- Remembers findings
- Summarizes information

### Interactive Chat
Full conversational agent with:
- Streaming responses
- Persistent memory
- Tool access
- Conversation history

## Creating Your Own Agent

```clojure
(require '[agent.core :as agent]
         '[agent.tools :as tools]
         '[co.poyo.clj-llm.backends.openai :as openai])

;; Create LLM backend
(def llm (openai/backend {:api-key-env "OPENAI_API_KEY"}))

;; Create agent
(def my-agent 
  (agent/create-agent
    {:llm-provider llm
     :system-prompt "You are a helpful assistant"
     :tools [tools/calculator-tool
             tools/search-tool]
     :memory (agent/make-memory)}))

;; Use the agent
(agent/think my-agent "What is 2+2?")
(agent/converse my-agent [{:role "user" :content "Hello!"}])
```

## Creating Custom Tools

```clojure
(def my-tool
  (agent/make-tool
    {:name "my-tool"
     :description "Does something useful"
     :schema [:map [:param :string]]
     :fn (fn [{:keys [param]}]
           {:result (str "Processed: " param)})}))
```

## Agent Capabilities

### Tool Calling
Agents can detect when to use tools and call them with appropriate parameters:
```clojure
(agent/think weather-agent "What's the weather in Tokyo?")
;; Agent will use the weather tool automatically
```

### Memory
Agents can store and retrieve information:
```clojure
(agent/remember memory :user-name "Alice")
(agent/recall memory :user-name) ;; => "Alice"
```

### Planning
Agents can create structured plans:
```clojure
(agent/plan research-agent "Research quantum computing")
;; Returns structured plan with steps and dependencies
```

### Chain of Thought
Step-by-step reasoning:
```clojure
(agent/think-step-by-step agent "Solve this logic puzzle...")
```

## Mock Tools

The example tools are mocked for demonstration. In production, you would:
- Replace weather-tool with real weather API calls
- Replace search-tool with actual web search
- Add real email sending capability
- Connect to actual databases

## Error Handling

The agent system inherits clj-llm's comprehensive error handling:
- Tool execution errors are caught and reported
- LLM errors are propagated with context
- Agents can recover from failures

## Future Enhancements

Ideas for extending the agent system:
- Tool composition and chaining
- Multi-agent collaboration
- Persistent memory storage
- Real tool implementations
- Agent templates/personalities
- Conversation branching
- Tool authentication/authorization