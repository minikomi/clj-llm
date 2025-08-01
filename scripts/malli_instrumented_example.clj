#!/usr/bin/env clojure -M

(ns malli-instrumented-example
  "Example of using Malli instrumented functions with clj-llm for structured extraction.
   
   This demonstrates how to:
   1. Define functions with Malli schemas
   2. Extract those schemas for use with LLMs
   3. Use LLMs to extract structured data that matches your function signatures"
  (:require [co.poyo.clj-llm.core :as llm]
            [co.poyo.clj-llm.backends.openai :as openai]
            [co.poyo.clj-llm.schema :as schema]
            [malli.core :as m]
            [malli.instrument :as mi]
            [clojure.pprint :as pp]))

;; ==========================================
;; Define functions with Malli schemas
;; ==========================================

;; A function for money transfers
(m/=> transfer-money
      [:=> [:cat [:map
                  [:from :string]
                  [:to :string]
                  [:amount :double]
                  [:currency {:optional true} [:enum "USD" "EUR" "GBP"]]]]
       [:map [:success :boolean] [:message :string]]])

(defn transfer-money
  "Process a money transfer between accounts"
  [{:keys [from to amount currency]}]
  {:success true
   :message (str "Transferred " amount " " (or currency "USD") 
                 " from " from " to " to)})

;; A function for creating users
(m/=> create-user
      [:=> [:cat [:map
                  [:name :string]
                  [:email [:re #".+@.+\..+"]]
                  [:age [:int {:min 0 :max 150}]]
                  [:roles [:vector [:enum "admin" "user" "developer" "manager"]]]]]
       [:map [:id :uuid] [:created-at inst?]]])

(defn create-user
  "Create a new user in the system"
  [{:keys [name email age roles]}]
  {:id (random-uuid)
   :created-at (java.util.Date.)
   :name name
   :email email
   :age age
   :roles (set roles)})

;; A function for processing orders
(m/=> process-order
      [:=> [:cat [:map
                  [:customer-id :uuid]
                  [:items [:vector [:map
                                    [:sku :string]
                                    [:quantity [:int {:min 1}]]
                                    [:price [:double {:min 0}]]]]]
                  [:shipping-address [:map
                                      [:street :string]
                                      [:city :string]
                                      [:country :string]]]]]
       [:map [:order-id :uuid] [:total :double] [:status :string]]])

(defn process-order
  "Process a customer order"
  [{:keys [customer-id items shipping-address]}]
  (let [total (reduce + (map #(* (:quantity %) (:price %)) items))]
    {:order-id (random-uuid)
     :customer-id customer-id
     :total total
     :status "pending"
     :items items
     :shipping-address shipping-address}))

;; Instrument all functions
(mi/instrument!)

;; ==========================================
;; Set up LLM backend
;; ==========================================

(def ai (openai/backend {:api-key-env "OPENAI_API_KEY"
                         :default-model "gpt-4.1-mini"}))

;; ==========================================
;; Example 1: Money Transfer
;; ==========================================

(println "Example 1: Money Transfer Extraction")
(println "====================================\n")

(let [schema (schema/instrumented-function->malli-schema transfer-money)
      prompt "Extract from this message: Hey, can you transfer $1,500 from Alice's account to Bob's account? Thanks!"]
  
  (println "Input text:" prompt)
  (println "\nExtracted schema from function:")
  (pp/pprint (m/form schema))
  
  (let [extracted-data (llm/generate ai prompt {:schema schema})]
    (println "\nExtracted data:")
    (pp/pprint extracted-data)
    
    (println "\nCalling function with extracted data:")
    (pp/pprint (transfer-money extracted-data))))

;; ==========================================
;; Example 2: User Creation
;; ==========================================

(println "\n\nExample 2: User Creation")
(println "========================\n")

(let [schema (schema/instrumented-function->malli-schema create-user)
      prompt "Please create an account for John Doe, email: john.doe@example.com, 32 years old, should have admin and developer access"]
  
  (println "Input text:" prompt)
  
  (let [extracted-data (llm/generate ai prompt {:schema schema})]
    (println "\nExtracted data:")
    (pp/pprint extracted-data)
    
    (println "\nCalling function with extracted data:")
    (pp/pprint (create-user extracted-data))))

;; ==========================================
;; Example 3: Order Processing
;; ==========================================

(println "\n\nExample 3: Order Processing")
(println "===========================\n")

(let [schema (schema/instrumented-function->malli-schema process-order)
      prompt "Customer 550e8400-e29b-41d4-a716-446655440000 wants to order 2 laptops (SKU: LAPTOP-001) at $999 each and 1 mouse (SKU: MOUSE-002) at $29. Ship to 123 Main St, San Francisco, USA"]
  
  (println "Input text:" prompt)
  
  (try
    (let [extracted-data (llm/generate ai prompt {:schema schema})]
      (println "\nExtracted data:")
      (pp/pprint extracted-data)
      
      (println "\nCalling function with extracted data:")
      (pp/pprint (process-order extracted-data)))
    (catch Exception e
      (println "Error:" (.getMessage e))
      (when-let [data (ex-data e)]
        (println "Details:" data)))))

;; ==========================================
;; Example 4: Show JSON Schema conversion
;; ==========================================

(println "\n\nExample 4: JSON Schema for OpenAI")
(println "=================================\n")

(println "Transfer function as JSON Schema:")
(pp/pprint (schema/malli->json-schema 
            (schema/instrumented-function->malli-schema transfer-money)))

(println "\n\nDone!")
(System/exit 0)