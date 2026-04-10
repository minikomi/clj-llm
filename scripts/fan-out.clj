;; Fan-out research: decomposes a question into parallel LLM subtasks via
;; a runtime-built core.async/flow graph, then synthesizes the results.
;;
;; Usage:
;;   echo "Compare the food cultures of Osaka, Lyon, and Mexico City" | clojure -M scripts/fan-out.clj
;;   clojure -M scripts/fan-out.clj < question.txt
;;
;; Env vars:
;;   OPENROUTER_KEY  — use OpenRouter (default)
;;   OPENAI_API_KEY  — use OpenAI
;;   LLM_MODEL       — override model (default: google/gemini-2.0-flash-lite-001)

(require
 '[clojure.core.async :as a]
 '[clojure.core.async.flow :as flow]
 '[clojure.string :as str]
 '[malli.core :as m]
 '[co.poyo.clj-llm.core :as llm]
 '[co.poyo.clj-llm.backend.openai :as openai]
 '[co.poyo.clj-llm.backend.openrouter :as openrouter])

(def ai
  (cond-> (if (System/getenv "OPENROUTER_KEY")
            (openrouter/backend)
            (openai/backend))
    (System/getenv "LLM_MODEL")
    (assoc-in [:defaults :model] (System/getenv "LLM_MODEL"))
    (not (System/getenv "LLM_MODEL"))
    (assoc-in [:defaults :model] "google/gemini-2.0-flash-lite-001")))

;; ── Process templates ─────────────────────────────────────────────

(defn llm-worker-fn
  ([] {:ins {:in "prompt map {:id str :prompt str}"} :outs {:out "answer string"} :params {}})
  ([_] {})
  ([state _] state)
  ([state _port {:keys [id prompt]}]
   (binding [*out* *err*] (println (str "  [" id "] running...")))
   (let [answer (:text (llm/generate ai
                         {:system-prompt "You are a concise research assistant. Answer in 2-4 sentences."}
                         prompt))]
     (binding [*out* *err*] (println (str "  [" id "] done")))
     [state {:out [answer]}])))

(defn make-aggregator-fn [n aggregation-prompt]
  (fn
    ([] {:ins {:in (str "result from any of " n " workers")} :outs {:out "synthesized answer"} :params {}})
    ([_] {:results []})
    ([state _] state)
    ([{:keys [results] :as state} _port msg]
     (let [results' (conj results msg)]
       (if (= (count results') n)
         (do
           (binding [*out* *err*] (println (str "  [aggregate] all " n " in — synthesizing...")))
           (let [body   (str/join "\n\n---\n\n"
                                  (map-indexed (fn [i r] (str "Result " (inc i) ":\n" r)) results'))
                 answer (:text (llm/generate ai
                                 {:system-prompt "You are a research synthesizer. Combine the provided results into a coherent, well-structured answer. Eliminate redundancy. Be clear and concise."}
                                 (str aggregation-prompt "\n\nResearch results:\n\n" body)))]
             [(assoc state :results results') {:out [answer]}]))
         [(assoc state :results results') {}])))))

(defn sink-fn
  ([] {:ins {:in "final answer"} :outs {} :params {:out-chan "result channel"}})
  ([{:keys [out-chan]}] {:out-chan out-chan})
  ([state _] state)
  ([state _port msg] (a/>!! (:out-chan state) msg) [state {}]))

;; ── Graph builder ─────────────────────────────────────────────────

(def TaskPlan
  [:map
   [:tasks
    [:vector {:min-count 2 :max-count 8}
     [:map
      [:id     {:description "short unique id like task-1, task-2"} :string]
      [:prompt {:description "self-contained research question for one parallel worker"} :string]]]]
   [:aggregation-prompt
    {:description "instruction for synthesizing the parallel results into a final answer"}
    :string]])

(defn run-fan-out [{:keys [tasks aggregation-prompt] :as plan}]
  (when-let [err (m/explain TaskPlan plan)]
    (throw (ex-info "Invalid task plan" {:explain err})))
  (let [n          (count tasks)
        out-chan   (a/chan 1)
        worker-ids (mapv #(keyword (:id %)) tasks)
        cfg {:procs
             (into
              {:aggregate {:proc (flow/process (make-aggregator-fn n aggregation-prompt) {:workload :io})}
               :sink      {:proc (flow/process sink-fn {:workload :io})
                           :args {:out-chan out-chan}}}
              (map (fn [{:keys [id]}]
                     [(keyword id) {:proc (flow/process llm-worker-fn {:workload :io})}])
                   tasks))
             :conns
             (into [[[:aggregate :out] [:sink :in]]]
                   (map (fn [wid] [[wid :out] [:aggregate :in]]) worker-ids))}
        g   (flow/create-flow cfg)
        {:keys [error-chan]} (flow/start g)]
    (a/go-loop []
      (when-let [err (a/<! error-chan)]
        (binding [*out* *err*] (println "FLOW ERROR:" (pr-str err)))
        (recur)))
    (flow/resume g)
    (doseq [{:keys [id prompt]} tasks]
      (flow/inject g [(keyword id) :in] [{:id id :prompt prompt}]))
    (let [result (a/<!! out-chan)]
      (flow/stop g)
      result)))

;; ── Tool for run-agent ────────────────────────────────────────────

(defn fan-out-research
  {:malli/schema
   [:=> [:cat
         [:map {:name "fan_out_research"
                :description "Decompose a complex question into 3-6 independent parallel research subtasks and synthesize the results. Use when the question has clearly distinct sub-aspects that benefit from parallel investigation."}
          [:tasks
           {:description "2-8 independent subtasks. Each prompt must be fully self-contained — a worker sees only its own prompt."}
           [:vector {:min-count 2 :max-count 8}
            [:map
             [:id     {:description "short unique id like task-1, task-2"} :string]
             [:prompt {:description "complete self-contained research question"} :string]]]]
          [:aggregation-prompt
           {:description "instruction telling the synthesizer what final output to produce from the parallel results"}
           :string]]]
        :string]}
  [{:keys [tasks] :as plan}]
  (binding [*out* *err*]
    (println (str "\n[fan-out] " (count tasks) " parallel workers"))
    (doseq [{:keys [id prompt]} tasks]
      (println (str "  " id ": " (subs prompt 0 (min 72 (count prompt))) "...")))
    (println))
  (run-fan-out plan))

;; ── Entry point ───────────────────────────────────────────────────

(let [question (str/trim (slurp *in*))]
  (when (str/blank? question)
    (binding [*out* *err*] (println "Usage: echo \"your question\" | clojure -M scripts/fan-out.clj"))
    (System/exit 1))
  (binding [*out* *err*] (println "Question:" question "\n"))
  (let [result (llm/run-agent
                ai
                {:tools        [#'fan-out-research]
                 :system-prompt "You are a research coordinator. When given a question, use fan_out_research to decompose it into independent parallel subtasks covering distinct aspects, then return the synthesized result. Always use the tool — never answer directly."
                 :max-steps    3}
                question)]
    (println (:text result))))
