#!/usr/bin/env bb

;; Prefer IPv4 — many hosts don't have working IPv6
(System/setProperty "java.net.preferIPv4Stack" "true")

(require '[co.poyo.clj-llm.core :as llm]
         '[co.poyo.clj-llm.content :as content]
         '[co.poyo.clj-llm.backend.openai :as openai]
         '[babashka.http-client :as http]
         '[cheshire.core :as json]
         '[clojure.string :as str])

;; ── Provider ──────────────────────────────────────────────────────

(def ai
  (-> (openai/backend {:api-key  (System/getenv "OPENROUTER_KEY")
                       :api-base "https://openrouter.ai/api/v1"})
      (assoc :defaults {:model "gpt-4o-mini"})))

;; ── Tools ─────────────────────────────────────────────────────────

(defn search-web
  "Search the web for information."
  {:malli/schema [:=> [:cat [:map {:name "search_web"
                                   :description "Search the web for information about a topic"}
                             [:query {:description "Search query"} :string]]]
                  :string]}
  [{:keys [query]}]
  (try
    (let [resp (http/post "https://html.duckduckgo.com/html/"
                 {:headers {"User-Agent" "Mozilla/5.0"
                            "Content-Type" "application/x-www-form-urlencoded"}
                  :body (str "q=" (java.net.URLEncoder/encode query "UTF-8"))})
          body (:body resp)
          snippets (re-seq #"class=\"result__snippet\"[^>]*>(.+?)</a>" body)
          texts (->> snippets
                     (take 5)
                     (map second)
                     (map #(-> %
                               (str/replace #"<[^>]+>" "")
                               (str/replace #"&amp;" "&")
                               (str/replace #"&nbsp;" " ")
                               str/trim)))]
      (if (seq texts)
        (str/join "\n\n" (map-indexed #(str (inc %1) ". " %2) texts))
        (str "No results for: " query)))
    (catch Exception e (str "Search error: " (.getMessage e)))))

;; ── Agent ─────────────────────────────────────────────────────────

(def opts
  {:max-steps     5
   :system-prompt "You're an image research assistant. When given an image:
                   1. Identify what's in it (landmark, object, scene, etc.)
                   2. Search the web to find real facts about it
                   3. Respond with what it is and 3 interesting facts
                   Always use search_web — never guess at facts."
   :on-tool-calls (fn [{:keys [tool-calls]}]
                    (doseq [tc tool-calls]
                      (println "🛠️ " (:name tc) (:arguments tc))))
   :on-tool-result (fn [{:keys [result]}]
                     (println "  →" (subs (str result) 0 (min 120 (count (str result)))) "..."))})

(defn ask [image-path-or-url]
  (let [input [(content/image image-path-or-url {:max-edge 512})]]
    (llm/run-agent ai [#'search-web] opts input)))

;; ── Main ──────────────────────────────────────────────────────────

(let [src (first *command-line-args*)]
  (when-not src
    (println "Usage: bb scripts/agent-landmark.clj <image-path-or-url>")
    (System/exit 1))
  (println "🏛️  Analyzing image...\n")
  (let [result (ask src)]
    (println "\n---")
    (println (:text result))))
