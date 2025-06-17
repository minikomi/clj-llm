(ns agent.tools
  "Common tools for agents"
  (:require [agent.core :as agent]
            [clojure.string :as str]
            [cheshire.core :as json]))

;; ──────────────────────────────────────────────────────────────
;; Calculator Tool
;; ──────────────────────────────────────────────────────────────

(def calculator-tool
  (agent/make-tool
   {:name "calculator"
    :description "Perform basic mathematical calculations"
    :schema [:map
             [:expression :string]]
    :fn (fn [{:keys [expression]}]
          (try
            ;; Very basic calculator - in production use a proper expression parser
            (let [result (load-string expression)]
              (str result))
            (catch Exception e
              (str "Error: " (.getMessage e)))))}))

;; ──────────────────────────────────────────────────────────────
;; Weather Tool (Mock)
;; ──────────────────────────────────────────────────────────────

(def weather-tool
  (agent/make-tool
   {:name "get-weather"
    :description "Get current weather for a location"
    :schema [:map
             [:location :string]]
    :fn (fn [{:keys [location]}]
          ;; Mock weather data - in production, call a real weather API
          (let [temps (range 60 85)
                conditions ["sunny" "partly cloudy" "cloudy" "rainy"]]
            {:location location
             :temperature (rand-nth temps)
             :condition (rand-nth conditions)
             :humidity (+ 40 (rand-int 40))}))}))

;; ──────────────────────────────────────────────────────────────
;; Web Search Tool (Mock)
;; ──────────────────────────────────────────────────────────────

(def search-tool
  (agent/make-tool
   {:name "web-search"
    :description "Search the web for information"
    :schema [:map
             [:query :string]
             [:num-results {:optional true} :int]]
    :fn (fn [{:keys [query num-results]
              :or {num-results 3}}]
          ;; Mock search results - in production, use a real search API
          {:query query
           :results (vec (for [i (range num-results)]
                           {:title (str "Result " (inc i) " for: " query)
                            :snippet (str "This is a mock search result about " query "...")
                            :url (str "https://example.com/" (inc i))}))})}))

;; ──────────────────────────────────────────────────────────────
;; File Operations Tools
;; ──────────────────────────────────────────────────────────────

(def read-file-tool
  (agent/make-tool
   {:name "read-file"
    :description "Read the contents of a file"
    :schema [:map
             [:path :string]]
    :fn (fn [{:keys [path]}]
          (try
            {:content (slurp path)
             :success true}
            (catch Exception e
              {:error (.getMessage e)
               :success false})))}))

(def write-file-tool
  (agent/make-tool
   {:name "write-file"
    :description "Write content to a file"
    :schema [:map
             [:path :string]
             [:content :string]]
    :fn (fn [{:keys [path content]}]
          (try
            (spit path content)
            {:success true
             :message (str "Wrote " (count content) " characters to " path)}
            (catch Exception e
              {:error (.getMessage e)
               :success false})))}))

;; ──────────────────────────────────────────────────────────────
;; Time Tool
;; ──────────────────────────────────────────────────────────────

(def time-tool
  (agent/make-tool
   {:name "get-time"
    :description "Get the current date and time"
    :schema [:map]
    :fn (fn [_]
          (let [now (java.time.ZonedDateTime/now)]
            {:iso (str now)
             :date (str (.toLocalDate now))
             :time (str (.toLocalTime now))
             :timezone (str (.getZone now))}))}))

;; ──────────────────────────────────────────────────────────────
;; Email Tool (Mock)
;; ──────────────────────────────────────────────────────────────

(def send-email-tool
  (agent/make-tool
   {:name "send-email"
    :description "Send an email (mock - doesn't actually send)"
    :schema [:map
             [:to :string]
             [:subject :string]
             [:body :string]]
    :fn (fn [{:keys [to subject body]}]
          ;; Mock email sending
          {:success true
           :message (str "Email sent to " to)
           :id (str (java.util.UUID/randomUUID))
           :timestamp (str (java.time.Instant/now))})}))

;; ──────────────────────────────────────────────────────────────
;; Database Tool (Mock)
;; ──────────────────────────────────────────────────────────────

(def query-db-tool
  (agent/make-tool
   {:name "query-database"
    :description "Query a database (mock)"
    :schema [:map
             [:query :string]]
    :fn (fn [{:keys [query]}]
          ;; Mock database results
          (cond
            (str/includes? query "users")
            {:results [{:id 1 :name "Alice" :email "alice@example.com"}
                       {:id 2 :name "Bob" :email "bob@example.com"}]
             :count 2}

            (str/includes? query "orders")
            {:results [{:id 101 :user-id 1 :total 99.99 :status "shipped"}
                       {:id 102 :user-id 2 :total 149.99 :status "pending"}]
             :count 2}

            :else
            {:results []
             :count 0}))}))

;; ──────────────────────────────────────────────────────────────
;; Tool Collections
;; ──────────────────────────────────────────────────────────────

(def basic-tools
  "Basic set of tools for general agents"
  [calculator-tool
   weather-tool
   search-tool
   time-tool])

(def file-tools
  "Tools for file operations"
  [read-file-tool
   write-file-tool])

(def communication-tools
  "Tools for communication"
  [send-email-tool])

(def data-tools
  "Tools for data operations"
  [query-db-tool])

(def all-tools
  "All available tools"
  (concat basic-tools file-tools communication-tools data-tools))