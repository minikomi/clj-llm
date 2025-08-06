#!/usr/bin/env bb

(ns malli-schemas-example
  "Example of using Malli schemas with clj-llm for structured extraction.
   
   This Babashka-compatible script shows how to:
   1. Define Malli schemas that match your function signatures
   2. Use them for structured data extraction
   3. Call your functions with the extracted data"
  (:require [co.poyo.clj-llm.core :as llm]
            [co.poyo.clj-llm.backends.openai :as openai]
            [co.poyo.clj-llm.schema :as schema]
            [clojure.pprint :as pp]))

;; ==========================================
;; Define schemas and corresponding functions
;; ==========================================

;; Schema for money transfers
(def transfer-schema
  [:map {:name "transfer_money"
         :description "Extract money transfer details from text"}
   [:from {:description "Sender's name"} :string]
   [:to {:description "Recipient's name"} :string]
   [:amount {:description "Amount to transfer"} :double]
   [:currency {:optional true 
               :description "Currency code (defaults to USD)"} 
    [:enum "USD" "EUR" "GBP"]]])

(defn transfer-money
  "Process a money transfer"
  [{:keys [from to amount currency]}]
  {:success true
   :message (str "Transferred " amount " " (or currency "USD") 
                 " from " from " to " to)
   :timestamp (str (java.time.Instant/now))})

;; Schema for user creation
(def user-schema
  [:map {:name "create_user"
         :description "Extract user information for account creation"}
   [:name {:description "Full name"} :string]
   [:email {:description "Email address"} :string]
   [:age {:description "Age in years"} :int]
   [:roles {:description "User roles/permissions"} 
    [:vector [:enum "admin" "user" "developer" "manager"]]]])

(defn create-user
  "Create a new user account"
  [{:keys [name email age roles]}]
  {:id (str (random-uuid))
   :name name
   :email email
   :age age
   :roles roles
   :created-at (str (java.time.Instant/now))})

;; Schema for meeting scheduling
(def meeting-schema
  [:map {:name "schedule_meeting"
         :description "Extract meeting details"}
   [:title :string]
   [:attendees [:vector :string]]
   [:date :string]
   [:time :string]
   [:duration-minutes {:optional true :default 60} :int]
   [:location {:optional true} :string]])

(defn schedule-meeting
  "Schedule a meeting"
  [{:keys [title attendees date time duration-minutes location]}]
  {:meeting-id (str (random-uuid))
   :title title
   :attendees attendees
   :scheduled-for (str date " at " time)
   :duration (str (or duration-minutes 60) " minutes")
   :location (or location "TBD")
   :status "scheduled"})

;; ==========================================
;; Set up LLM backend
;; ==========================================

(def ai (openai/backend {:api-key-env "OPENAI_API_KEY"
                         :default-model "gpt-4.1-nano"}))

;; ==========================================
;; Example 1: Money Transfer
;; ==========================================

(println "Example 1: Money Transfer")
(println "========================\n")

(let [prompt "Please transfer $1,500 from Alice to Bob"]
  (println "Input:" prompt)
  
  (let [data (llm/generate ai prompt {:schema transfer-schema})]
    (println "\nExtracted:")
    (pp/pprint data)
    
    (println "\nResult:")
    (pp/pprint (transfer-money data))))

;; ==========================================
;; Example 2: User Creation
;; ==========================================

(println "\n\nExample 2: User Creation")
(println "=======================\n")

(let [prompt "Create account for Jane Smith (jane@example.com), 28 years old, needs admin and developer access"]
  (println "Input:" prompt)
  
  (let [data (llm/generate ai prompt {:schema user-schema})]
    (println "\nExtracted:")
    (pp/pprint data)
    
    (println "\nResult:")
    (pp/pprint (create-user data))))

;; ==========================================
;; Example 3: Meeting Scheduling
;; ==========================================

(println "\n\nExample 3: Meeting Scheduling")
(println "============================\n")

(let [prompt "Schedule a project sync meeting with Alice, Bob, and Charlie on Friday Dec 15th at 2:30 PM for 90 minutes in Conference Room A"]
  (println "Input:" prompt)
  
  (let [data (llm/generate ai prompt {:schema meeting-schema})]
    (println "\nExtracted:")
    (pp/pprint data)
    
    (println "\nResult:")
    (pp/pprint (schedule-meeting data))))

;; ==========================================
;; Example 4: Batch Processing
;; ==========================================

(println "\n\nExample 4: Batch Processing")
(println "==========================\n")

(def requests
  ["Transfer 200 EUR from John to Sarah"
   "Send $50 from Mike to Emma"  
   "Transfer £1000 from David to Lisa"])

(println "Processing multiple transfer requests:\n")

(doseq [req requests]
  (println "Request:" req)
  (try
    (let [data (llm/generate ai req {:schema transfer-schema})
          result (transfer-money data)]
      (println "→" (:message result)))
    (catch Exception e
      (println "→ Error:" (.getMessage e))))
  (println))

;; ==========================================
;; Example 5: Show schema details
;; ==========================================

(println "\nExample 5: Schema as JSON")
(println "========================\n")

(println "Transfer schema converted to OpenAI format:")
(pp/pprint (schema/malli->json-schema transfer-schema))

(println "\n\nDone!")
