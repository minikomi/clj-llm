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
