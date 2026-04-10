# LLMs as Graph Architects

*What if the LLM doesn't execute the work — it designs the pipeline?*

Most LLM tool-use follows the same pattern: the model decides which function to call, the runtime calls it, the result comes back. The model is an orchestrator of individual function calls.

`core.async/flow` opens a different door. A flow graph is a *data structure* — a map of processes and connections. That means an LLM can generate one. Not Clojure code. Not eval. Just a plain map that says: "run these three things in parallel, fan the results into this aggregator."

---

## The fork that matters

There are two ways to let an LLM build a concurrent pipeline:

**Code generation** — the LLM writes functions, you eval them.

```clojure
;; ❌ requires eval, output is opaque, errors arrive at runtime
(eval (read-string (llm/generate ai "write a Clojure fn that...")))
```

**Data generation** — the LLM fills in a plan, fixed templates do the work.

```clojure
;; ✅ pure data, Malli-validated, inspectable before execution
{:tasks [{:id "task-1" :prompt "Research Rust's ecosystem maturity..."}
         {:id "task-2" :prompt "Research Go's concurrency model..."}
         {:id "task-3" :prompt "Research Clojure's JVM trade-offs..."}]
 :aggregation-prompt "Synthesize these into a comparative analysis."}
```

The data-gen path fits everything Clojure stands for. The plan is a value. You validate it before touching any process. The process logic is code you own — the LLM only fills in *what to compute*, not *how to compute it*.

---

## What the LLM generates

A `TaskPlan` — two fields, Malli-validated:

```clojure
(def TaskPlan
  [:map
   [:tasks
    [:vector {:min-count 2 :max-count 6}
     [:map
      [:id     {:description "short unique id like task-1, task-2"} :string]
      [:prompt {:description "self-contained research question for one parallel worker"} :string]]]]
   [:aggregation-prompt
    {:description "instruction for synthesizing the parallel results into a final answer"}
    :string]])
```

The schema doubles as documentation for the LLM (via `:description` metadata) and as a runtime guard. If the model produces something malformed, `m/explain` catches it before a single process is spawned.

---

## Two process templates

The entire runtime machinery is two reusable process definitions.

**`llm-worker-fn`** — receives a `{:id :prompt}` map, calls `generate`, emits a string:

```clojure
(defn llm-worker-fn
  ([] {:ins    {:in  "prompt map {:id str :prompt str}"}
       :outs   {:out "answer string"}
       :params {}})
  ([_params] {})
  ([state _coord] state)
  ([state _port {:keys [id prompt]}]
   (println (str "  [worker " id "] running..."))
   (let [answer (:text (llm/generate ai
                         {:system-prompt "You are a concise research assistant. Answer in 2-3 sentences."}
                         prompt))]
     (println (str "  [worker " id "] done"))
     [state {:out [answer]}])))
```

**`make-aggregator-fn`** — a closure over `n` and `aggregation-prompt`. Accumulates results in `state` across N messages, then fires synthesis:

```clojure
(defn make-aggregator-fn [n aggregation-prompt]
  (fn
    ([] {:ins    {:in  (str "result from any of " n " workers")}
         :outs   {:out "synthesized final answer"}
         :params {}})
    ([_] {:n n :prompt aggregation-prompt :results []})
    ([state _] state)
    ([{:keys [results] :as state} _port msg]
     (let [results' (conj results msg)]
       (if (= (count results') n)
         (let [numbered  (str/join "\n\n---\n\n"
                                   (map-indexed
                                     (fn [i r] (str "Result " (inc i) ":\n" r))
                                     results'))
               answer    (:text (llm/generate ai
                                  {:system-prompt "Synthesize these research results into a coherent answer."}
                                  (str aggregation-prompt "\n\n" numbered)))]
           [(assoc state :results results') {:out [answer]}])
         [(assoc state :results results') {}])))))
```

The 4-arity transform is called once per incoming message. `state` persists between calls — that's flow's accumulation mechanism, and it's exactly what fan-in needs. No atoms, no channels, no coordination code. Just a count check.

All N workers share the single `:in` port on the aggregator. Flow multiplexes them — the aggregator doesn't need to know how many channels are upstream.

---

## Building the graph at runtime

`tasks->flow-config` translates a validated plan into a `create-flow` config. This is pure data transformation — no side effects, no threads started yet:

```clojure
(defn tasks->flow-config [{:keys [tasks aggregation-prompt]} out-chan]
  (let [n          (count tasks)
        worker-ids (mapv #(keyword (:id %)) tasks)

        worker-procs
        (into {}
              (map (fn [{:keys [id]}]
                     [(keyword id) {:proc (flow/process llm-worker-fn {:workload :io})}])
                   tasks))

        agg-proc  {:proc (flow/process (make-aggregator-fn n aggregation-prompt)
                                       {:workload :io})}
        sink-proc {:proc (flow/process sink-fn {:workload :io})
                   :args {:out-chan out-chan}}

        ;; all N workers connect to the single :in port — flow multiplexes them
        worker->agg (mapv (fn [wid] [[wid :out] [:aggregate :in]]) worker-ids)
        agg->sink   [[[:aggregate :out] [:sink :in]]]]

    {:procs (merge worker-procs {:aggregate agg-proc :sink sink-proc})
     :conns (into worker->agg agg->sink)}))
```

For 3 tasks this produces a graph like:

```
[task-1] ──┐
[task-2] ──┼──► [aggregate] ──► [sink]
[task-3] ──┘
```

For 5 tasks, 5 workers feed the same aggregator. The topology is generated, but the process templates never change.

---

## The tool

`fan-out-research` wraps all of this into a single tool `run-agent` can call. The Malli schema in the metadata is what the LLM sees as its calling convention:

```clojure
(defn fan-out-research
  {:malli/schema
   [:=> [:cat
         [:map {:name        "fan_out_research"
                :description "Decompose a complex question into parallel research subtasks and synthesize the results."}
          [:tasks
           {:description "2-6 independent subtasks. Each prompt must be fully self-contained."}
           [:vector {:min-count 2 :max-count 6}
            [:map
             [:id     {:description "short unique id like task-1, task-2"} :string]
             [:prompt {:description "complete self-contained research question"} :string]]]]
          [:aggregation-prompt
           {:description "instruction telling the synthesizer what final output to produce"}
           :string]]]
        :string]}
  [{:keys [tasks] :as plan}]
  (when-let [err (m/explain TaskPlan plan)]
    (throw (ex-info "Invalid task plan from LLM" {:explain err})))

  (let [out-chan (a/chan 1)
        g        (flow/create-flow (tasks->flow-config plan out-chan))
        {:keys [error-chan]} (flow/start g)]
    (a/go-loop []
      (when-let [err (a/<! error-chan)]
        (println "FLOW ERROR:" (pr-str err))
        (recur)))
    (flow/resume g)
    (doseq [{:keys [id prompt]} tasks]
      (flow/inject g [(keyword id) :in] [{:id id :prompt prompt}]))
    (let [result (a/<!! out-chan)]
      (flow/stop g)
      result)))
```

From `run-agent`'s perspective this is just a tool call that returns a string. The entire parallel subgraph — N HTTP calls, fan-in, synthesis — is invisible to the outer agent loop.

---

## Putting it together

```clojure
(llm/run-agent
  ai
  {:tools        [#'fan-out-research]
   :system-prompt "You are a technical research coordinator. When given a complex question,
                   use fan_out_research to decompose it into 3-5 independent parallel subtasks.
                   Each task should cover a clearly distinct aspect. Decompose once, return the result."
   :max-steps    3}
  "What are the key trade-offs between Rust, Go, and Clojure for building a
   high-throughput data pipeline in 2025?")
```

At runtime:

```
[fan-out] spawning 3 parallel workers
  task-1: Research Rust's ecosystem maturity for data pipelines...
  task-2: Research Go's concurrency model and operational trade-offs...
  task-3: Research Clojure's JVM characteristics and functional paradigm...

  [worker task-1] running...
  [worker task-2] running...
  [worker task-3] running...
  [worker task-1] done
  [worker task-3] done
  [worker task-2] done
  [aggregate] all 3 results in — synthesizing...
```

Three LLM calls in parallel, one synthesis call, one outer agent step. The outer model never sees the parallelism — it just gets back a synthesized string.

---

## Why this matters

The conventional view of LLM tool use is *orchestration*: the model calls tools sequentially, one at a time. This pattern breaks that constraint without adding any agentic complexity to the outer loop.

The decomposition is *structural*, not behavioral. The LLM decides how to split the problem; the runtime decides how to execute it. Those are different concerns, and keeping them separate is what makes this composable.

A few things follow naturally from this design:

**Validation is free.** The plan is a value before any process runs. `m/explain` on `TaskPlan` catches malformed decompositions immediately, with structured error data pointing to the problem field.

**The graph is inspectable.** `tasks->flow-config` returns a plain map. You can print it, log it, diff it against a previous run, or unit test it without starting a single thread.

**The templates are reusable.** `llm-worker-fn` and `make-aggregator-fn` work for any domain. Swap the system prompt, swap the schema, the plumbing stays the same.

**The recursion case is obvious.** Each `llm-worker` could itself be a `run-agent` call with access to `fan-out-research`. Depth is a counter in the task args, decremented at each level, refusing to fan-out at zero. Subgraphs spawning subgraphs, all coordinated by data, all stoppable.

The LLM doesn't need to know Clojure. It just needs to know the schema.

---

## Beyond fan-out: arbitrary DAGs

The fan-out pattern has a constraint built into it: the LLM generates a flat list of tasks, all parallel, all feeding the same aggregator. That's useful, but it's not the full shape of what `create-flow` can express.

A more interesting question is whether the LLM can generate a genuine DAG — one where intermediate nodes depend on earlier nodes, producing a multi-stage pipeline where some analysis waits on other analysis.

The key change is giving the plan a dependency field:

```clojure
{:nodes
 [{:id "search-rust"   :type "search" :query "Rust data pipeline performance 2025"}
  {:id "search-go"     :type "search" :query "Go data pipeline performance 2025"}
  {:id "analyze-rust"  :type "llm"    :deps ["search-rust"]
   :prompt "Summarize Rust's strengths and weaknesses for data pipelines."}
  {:id "analyze-go"    :type "llm"    :deps ["search-go"]
   :prompt "Summarize Go's strengths and weaknesses for data pipelines."}
  {:id "compare"       :type "llm"    :deps ["analyze-rust" "analyze-go"]
   :prompt "Compare these analyses. What are the key trade-offs?"}
  {:id "final"         :type "llm"    :deps ["compare"]
   :prompt "Write a concrete recommendation based on the comparison."}]}
```

Two node types. `search` nodes fire immediately at start — they have no deps. `llm` nodes wait on named ports, one per dependency, and fire when all have arrived.

### Named ports for multi-dep nodes

In the flat fan-out, the aggregator has a single `:in` port and flow multiplexes all workers onto it. That works when the aggregator doesn't care which result came from where.

In a DAG, a node like `compare` receives `analyze-rust` and `analyze-go` as distinct inputs — it needs to know which is which to write a coherent prompt. So each dep gets its own named port:

```clojure
(defn make-llm-proc [id deps prompt]
  (let [port-set (set (map keyword deps))]
    (fn
      ([] {:ins  (into {} (map #(vector (keyword %) (str "result from " %)) deps))
           :outs {:out "result"}
           :params {}})
      ([_] {:collected {}})
      ([state _] state)
      ([{:keys [collected] :as state} port msg]
       (let [collected' (assoc collected port msg)]
         (if (= (set (keys collected')) port-set)
           ;; all deps in — build context and generate
           (let [context (str/join "\n\n---\n\n"
                           (map #(str (name %) ":\n" (get collected' (keyword %))) deps))
                 answer  (:text (llm/generate ai
                                  {:system-prompt "You are a research analyst."}
                                  (str prompt "\n\nContext:\n\n" context)))]
             [(assoc state :collected {}) {:out [answer]}])
           ;; still waiting
           [(assoc state :collected collected') {}]))))))
```

State accumulates one message per dep across transform calls. When the set of collected keys equals the set of expected deps, the node fires. The context string preserves which result came from which node — `analyze-rust:` followed by that content, `analyze-go:` followed by its content.

### Wiring the graph

`dag->flow-config` walks the node list and emits one connection per edge — `dep :out` to `child :(keyword dep)`:

```clojure
(defn dag->flow-config [{:keys [nodes]} out-chan]
  (let [terminal (find-terminal nodes)   ;; the one node nothing depends on
        procs    (into {:sink ...}
                       (map (fn [{:keys [id type query prompt deps]}]
                              [(keyword id)
                               {:proc (flow/process
                                       (case type
                                         "search" (make-search-proc id query)
                                         "llm"    (make-llm-proc id deps prompt))
                                       {:workload :io})}])
                            nodes))
        ;; one conn per edge: dep's :out → child's :(dep-id) port
        conns    (for [{:keys [id deps]} nodes, dep (or deps [])]
                   [[(keyword dep) :out] [(keyword id) (keyword dep)]])]
    {:procs procs
     :conns (into (vec conns) [[[(keyword (:id terminal)) :out] [:sink :in]]])}))
```

The loop at the end injects a trigger message to every `search` node to start the graph. Everything else fires automatically as data flows through.

### What the LLM actually generates

Given the question *"What are the key trade-offs between Rust and Go for high-throughput data pipelines?"* with a tool prompt asking for multi-stage research, Claude produces a 9-node plan:

```
[llm] final-recommendation
└── [llm] comparison
    ├── [llm] analyze-rust
    │   ├── [search] search-rust-performance
    │   └── [search] search-rust-ecosystem
    ├── [llm] analyze-go
    │   ├── [search] search-go-performance
    │   └── [search] search-go-ecosystem
    └── [search] search-rust-vs-go
```

Five search nodes fire in parallel at the start. `analyze-rust` waits for its two searches; `analyze-go` waits for its two. `comparison` waits for both analyses and the cross-cutting comparison search. `final-recommendation` waits on `comparison`.

The runtime output confirms this:

```
  [search-rust-performance] searching...
  [search-rust-ecosystem] searching...
  [search-go-performance] searching...
  [search-go-ecosystem] searching...
  [search-rust-vs-go] searching...
  [search-rust-performance] done
  [search-rust-ecosystem] done
  [analyze-rust] all deps arrived — generating...
  [search-go-performance] done
  [search-go-ecosystem] done
  [analyze-go] all deps arrived — generating...
  [analyze-rust] done
  [analyze-go] done
  [comparison] all deps arrived — generating...
  [comparison] done
  [final-recommendation] all deps arrived — generating...
```

The model didn't just decompose into independent parallel tasks — it designed a pipeline with intermediate stages, where earlier analysis feeds into later synthesis. `comparison` receives two focused summaries rather than four raw search dumps.

### What the schema affords

The node vocabulary is a fixed set you define. The LLM picks from `"search"` and `"llm"`. You could add `"critique"` (takes a draft and a set of search results, returns criticism), `"format"` (takes structured data, returns markdown), or any other type that makes sense for your domain. The LLM's job is graph architecture, not implementation.

Validation before execution covers the structural properties: unknown deps, search nodes missing queries, llm nodes missing prompts or deps, more than one terminal node. These are caught as data errors before a single process starts.

