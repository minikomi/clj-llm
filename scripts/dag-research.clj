;; DAG research: LLM generates a nodes+edges plan, translated into a
;; runtime core.async/flow graph. Nodes fire when all their inputs arrive.
;;
;; Usage:
;;   echo "your question" | clojure -M:flow scripts/dag-research.clj
;;
;; Env vars:
;;   OPENROUTER_KEY  — use OpenRouter (default)
;;   OPENAI_API_KEY  — use OpenAI
;;   KAGI_API_KEY    — enables grounded search (recommended)

(require
 '[clojure.core.async :as a]
 '[clojure.core.async.flow :as flow]
 '[clojure.string :as str]
 '[malli.core :as m]
 '[cheshire.core :as json]
 '[co.poyo.clj-llm.core :as llm]
 '[co.poyo.clj-llm.backend.openai :as openai]
 '[co.poyo.clj-llm.backend.openrouter :as openrouter])

(def kagi-key (System/getenv "KAGI_API_KEY"))

(def ai
  (cond-> (if (System/getenv "OPENROUTER_KEY") (openrouter/backend) (openai/backend))
    (System/getenv "LLM_MODEL") (assoc-in [:defaults :model] (System/getenv "LLM_MODEL"))
    true (assoc-in [:defaults :model] "anthropic/claude-haiku-4-5")))

;; ── Kagi FastGPT ─────────────────────────────────────────────────

(defn kagi-search! [query]
  (let [conn (doto (.openConnection (java.net.URL. "https://kagi.com/api/v0/fastgpt"))
               (.setRequestMethod "POST")
               (.setRequestProperty "Authorization" (str "Bot " kagi-key))
               (.setRequestProperty "Content-Type" "application/json")
               (.setDoOutput true))
        body (json/generate-string {:query query :cache false})
        _    (with-open [out (.getOutputStream conn)]
               (.write out (.getBytes body "UTF-8")))
        resp (with-open [in (.getInputStream conn)] (slurp in))]
    (or (get-in (json/parse-string resp true) [:data :output])
        (str "No results for: " query))))

;; ── Process constructors ──────────────────────────────────────────

(defn make-search-proc [id query]
  (fn
    ([] {:ins {:trigger "start signal"} :outs {:out "search result"} :params {}})
    ([_] {})
    ([state _] state)
    ([state _port _msg]
     (binding [*out* *err*] (println (str "  [" id "] searching...")))
     (let [result (if kagi-key
                    (kagi-search! query)
                    (str "No KAGI_API_KEY — skipped search for: " query))]
       (binding [*out* *err*] (println (str "  [" id "] done")))
       [state {:out [result]}]))))

(defn make-llm-proc [id in-ports prompt]
  (fn
    ([] {:ins  (into {} (map #(vector % (str "result from " (name %))) in-ports))
         :outs {:out "result"}
         :params {}})
    ([_] {:collected {}})
    ([state _] state)
    ([{:keys [collected] :as state} port msg]
     (let [collected' (assoc collected port msg)]
       (if (= (set (keys collected')) in-ports)
         (do
           (binding [*out* *err*] (println (str "  [" id "] all inputs arrived — generating...")))
           (let [context (str/join "\n\n---\n\n"
                                   (map #(str (name %) ":\n" (get collected' %))
                                        (sort in-ports)))
                 answer  (:text (llm/generate ai
                                  {:system-prompt "You are a concise research analyst."}
                                  (str prompt "\n\nContext:\n\n" context)))]
             (binding [*out* *err*] (println (str "  [" id "] done")))
             [(assoc state :collected {}) {:out [answer]}]))
         [(assoc state :collected collected') {}])))))

(defn sink-proc [out-chan]
  (fn
    ([] {:ins {:in "final result"} :outs {} :params {}})
    ([_] {})
    ([state _] state)
    ([state _port msg] (a/>!! out-chan msg) [state {}])))

;; ── Schema ────────────────────────────────────────────────────────

(def DagPlan
  [:map
   [:nodes [:vector {:min-count 2}
            [:or
             [:map [:id :string] [:type [:= "search"]] [:query :string]]
             [:map [:id :string] [:type [:= "llm"]]    [:prompt :string]]]]]
   [:edges [:vector {:min-count 1}
            [:map [:from :string] [:to :string]]]]])

;; ── Validation ────────────────────────────────────────────────────

(defn find-terminal [{:keys [nodes edges]}]
  (let [ids      (set (map :id nodes))
        all-froms (set (map :from edges))]
    (doseq [{:keys [from to]} edges]
      (when-not (ids from) (throw (ex-info (str "Unknown edge source: " from) {})))
      (when-not (ids to)   (throw (ex-info (str "Unknown edge target: " to) {}))))
    (let [terminals (filter (fn [n]
                              (and (not (all-froms (:id n)))
                                   (some (fn [e] (= (:to e) (:id n))) edges)))
                            nodes)]
      (when (not= 1 (count terminals))
        (throw (ex-info (str "Need exactly one terminal node, found: "
                             (mapv :id terminals)) {})))
      (first terminals))))

;; ── Tree renderer ─────────────────────────────────────────────────

(defn dag-tree [{:keys [nodes edges]}]
  (let [by-id    (into {} (map #(vector (:id %) %) nodes))
        terminal (find-terminal {:nodes nodes :edges edges})
        visited  (atom #{})]
    (letfn [(render [id prefix is-last?]
              (let [node   (by-id id)
                    seen?  (@visited id)
                    _      (swap! visited conj id)
                    branch (if is-last? "└── " "├── ")
                    label  (str "[" (:type node) "] " id (when seen? " *"))
                    child-p (str prefix (if is-last? "    " "│   "))
                    srcs   (when-not seen? (map :from (filter #(= (:to %) id) edges)))
                    n      (count srcs)]
                (cons (str prefix branch label)
                      (mapcat (fn [src i] (render src child-p (= i (dec n))))
                              srcs (range n)))))]
      (let [tid  (:id terminal)
            srcs (map :from (filter #(= (:to %) tid) edges))
            n    (count srcs)]
        (str/join "\n"
          (cons (str "[" (:type terminal) "] " tid)
                (mapcat (fn [src i] (render src "" (= i (dec n))))
                        srcs (range n))))))))

;; ── Graph builder ─────────────────────────────────────────────────

(defn plan->flow-config [{:keys [nodes edges]} out-chan]
  (let [terminal (find-terminal {:nodes nodes :edges edges})
        in-ports (reduce (fn [m {:keys [from to]}]
                           (update m to (fnil conj #{}) (keyword from)))
                         {} edges)
        procs    (into
                  {:sink {:proc (flow/process (sink-proc out-chan) {:workload :io})}}
                  (map (fn [{:keys [id type query prompt]}]
                         [(keyword id)
                          {:proc (flow/process
                                  (case type
                                    "search" (make-search-proc id query)
                                    "llm"    (make-llm-proc id (get in-ports id #{}) prompt))
                                  {:workload :io})}])
                       nodes))
        conns    (into
                  [[[(keyword (:id terminal)) :out] [:sink :in]]]
                  (map (fn [{:keys [from to]}]
                         [[(keyword from) :out] [(keyword to) (keyword from)]])
                       edges))]
    {:procs procs :conns conns}))

;; ── Runner ────────────────────────────────────────────────────────

(defn run-dag [plan]
  (when-let [err (m/explain DagPlan plan)]
    (throw (ex-info "Invalid DAG plan" {:explain err})))
  (let [out-chan (a/chan 1)
        cfg      (plan->flow-config plan out-chan)
        g        (flow/create-flow cfg)
        {:keys [error-chan]} (flow/start g)]
    (a/go-loop []
      (when-let [err (a/<! error-chan)]
        (binding [*out* *err*] (println "FLOW ERROR:" (pr-str err)))
        (recur)))
    (flow/resume g)
    (doseq [{:keys [id type]} (:nodes plan) :when (= type "search")]
      (flow/inject g [(keyword id) :trigger] [:go]))
    (let [result (a/<!! out-chan)]
      (flow/stop g)
      result)))

;; ── Tool ─────────────────────────────────────────────────────────

(defn execute-dag
  {:malli/schema
   [:=> [:cat
         [:map {:name        "execute_dag"
                :description "Build and execute a multi-stage research DAG. Define nodes and edges separately. Search nodes fire immediately in parallel. LLM nodes wait for all their incoming edges before generating. Use intermediate LLM nodes to analyse before a final synthesis node."}
          [:nodes
           {:description "Nodes: search (needs :query) or llm (needs :prompt). Must have exactly one terminal node (no outgoing edges)."}
           [:vector {:min-count 2}
            [:or
             [:map [:id {:description "unique kebab-case id"} :string]
                   [:type [:= "search"]]
                   [:query {:description "web search query"} :string]]
             [:map [:id {:description "unique kebab-case id"} :string]
                   [:type [:= "llm"]]
                   [:prompt {:description "generation instruction; all incoming results provided as labelled context"} :string]]]]]
          [:edges
           {:description "Directed edges. Each {:from A :to B} delivers A's output to B's named input port."}
           [:vector {:min-count 1}
            [:map
             [:from {:description "source node id"} :string]
             [:to   {:description "target node id"} :string]]]]]]
        :string]}
  [{:keys [nodes edges] :as plan}]
  (let [search-n (count (filter #(= (:type %) "search") nodes))
        llm-n    (count (filter #(= (:type %) "llm") nodes))]
    (binding [*out* *err*]
      (println (str "\n[dag] " (count nodes) " nodes — " search-n " search, " llm-n " llm"))
      (println (str "      " (count edges) " edges\n"))
      (println (dag-tree plan))
      (println)))
  (run-dag plan))

;; ── Entry point ───────────────────────────────────────────────────

(let [question (str/trim (slurp *in*))]
  (when (str/blank? question)
    (binding [*out* *err*]
      (println "Usage: echo \"your question\" | clojure -M:flow scripts/dag-research.clj"))
    (System/exit 1))
  (binding [*out* *err*] (println "Question:" question "\n"))
  (let [result (llm/run-agent
                ai
                {:tools        [#'execute-dag]
                 :system-prompt "You are a research coordinator. Call execute_dag immediately with a nodes+edges plan. Design search nodes to run in parallel, intermediate llm nodes to analyse per-topic, and a single terminal llm node to synthesise. Keep node ids short. Keep prompts concise. Always call the tool — never answer directly."
                 :max-steps    3
                 :max-tokens   4096}
                question)]
    (println (:text result))))
