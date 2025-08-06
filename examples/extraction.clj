(ns examples.extraction
  "Advanced example showing data extraction with nested Malli schemas"
  (:require [co.poyo.clj-llm.core :as llm]
            [co.poyo.clj-llm.backends.openai :as openai]))

;; ════════════════════════════════════════════════════════════════════
;; Backend Setup with Extraction-Optimized Defaults
;; ════════════════════════════════════════════════════════════════════

;; Create a backend with sensible defaults for extraction tasks
(def extraction-ai 
  (openai/backend 
    {:backend {:default-model "gpt-4o-mini"}
     :defaults {:temperature 0.2      ; Low temperature for consistency
                :max-tokens 2000      ; Enough for complex extractions
                :top-p 0.95}}))       ; Slightly constrained sampling

;; ════════════════════════════════════════════════════════════════════
;; Complex Nested Schema Definition
;; ════════════════════════════════════════════════════════════════════

(def company-analysis-schema
  [:map
   {:name "company_analysis"
    :description "Extract structured information about a company from text"}
   [:company-name :string]
   [:industry [:enum "technology" "finance" "healthcare" "retail" "manufacturing" "other"]]
   [:founded-year {:optional true} [:int {:min 1800 :max 2024}]]
   
   ;; Nested structure for key people
   [:key-people 
    [:vector
     [:map
      [:name :string]
      [:role :string]
      [:tenure-years {:optional true} [:int {:min 0}]]]]]
   
   ;; Nested financial data
   [:financials
    {:optional true}
    [:map
     [:revenue {:optional true} 
      [:map
       [:amount [:double {:min 0}]]
       [:currency [:enum "USD" "EUR" "GBP" "JPY"]]
       [:period [:enum "annual" "quarterly"]]]]
     [:profitable :boolean]
     [:funding-rounds {:optional true}
      [:vector
       [:map
        [:round-type [:enum "seed" "series-a" "series-b" "series-c" "ipo"]]
        [:amount [:double {:min 0}]]
        [:year [:int {:min 1900 :max 2024}]]]]]]]
   
   ;; Products/services as nested array
   [:products
    [:vector
     [:map
      [:name :string]
      [:category :string]
      [:target-market {:optional true} :string]]]]
   
   ;; Summary and sentiment
   [:summary :string]
   [:sentiment [:enum "positive" "neutral" "negative"]]])

;; ════════════════════════════════════════════════════════════════════
;; Example Text for Extraction
;; ════════════════════════════════════════════════════════════════════

(def company-text 
  "Apple Inc. was founded by Steve Jobs and Steve Wozniak in 1976. 
   Tim Cook has been CEO since 2011, taking over from Jobs. The company 
   reported $394.3 billion in revenue for fiscal 2022, maintaining strong 
   profitability throughout its history.
   
   Their main products include the iPhone (smartphones), iPad (tablets), 
   and Mac computers, targeting both consumer and enterprise markets. The 
   company also offers services like iCloud and Apple Music.
   
   Apple went public in 1980 and has had several funding rounds before that,
   including an initial seed investment of $250,000 in 1977.
   
   The company is seen as an innovation leader in the technology industry,
   with a loyal customer base and strong brand recognition.")

;; ════════════════════════════════════════════════════════════════════
;; Extraction Examples
;; ════════════════════════════════════════════════════════════════════

;; 1. Extract with backend defaults
(comment
  (def extracted-with-defaults
    (llm/generate extraction-ai
                  company-text
                  {:schema company-analysis-schema}))
  
  ;; Result would be something like:
  ;; {:company-name "Apple Inc."
  ;;  :industry "technology"
  ;;  :founded-year 1976
  ;;  :key-people [{:name "Steve Jobs" :role "Co-founder"}
  ;;               {:name "Steve Wozniak" :role "Co-founder"}
  ;;               {:name "Tim Cook" :role "CEO" :tenure-years 12}]
  ;;  :financials {:revenue {:amount 394.3 :currency "USD" :period "annual"}
  ;;               :profitable true
  ;;               :funding-rounds [{:round-type "seed" :amount 250000.0 :year 1977}]}
  ;;  :products [{:name "iPhone" :category "smartphones" :target-market "consumer and enterprise"}
  ;;             {:name "iPad" :category "tablets" :target-market "consumer and enterprise"}
  ;;             {:name "Mac" :category "computers" :target-market "consumer and enterprise"}
  ;;             {:name "iCloud" :category "services"}
  ;;             {:name "Apple Music" :category "services"}]
  ;;  :summary "Apple Inc. is a technology company founded in 1976..."
  ;;  :sentiment "positive"}
  )

;; 2. Extract with overrides for different strategies
(comment
  ;; More creative interpretation with higher temperature
  (def extracted-creative
    (llm/generate extraction-ai
                  company-text
                  {:schema company-analysis-schema
                   :temperature 0.5      ; Override: bit more creative
                   :max-tokens 3000      ; Override: allow longer response
                   :seed 42}))           ; Add reproducibility
  
  ;; Ultra-precise extraction with minimal temperature
  (def extracted-precise
    (llm/generate extraction-ai
                  company-text
                  {:schema company-analysis-schema
                   :temperature 0.0      ; Deterministic
                   :top-p 0.9}))         ; Even more constrained
  )

;; 3. Extraction with conversation context
(comment
  (def conversation-extraction
    (llm/generate extraction-ai
                  nil  ; No prompt, using messages
                  {:messages [{:role :system 
                              :content "You are a financial analyst. Extract company information precisely. Pay special attention to financial metrics and growth indicators."}
                             {:role :user 
                              :content company-text}]
                   :schema company-analysis-schema
                   :temperature 0.1}))  ; Very low temp for maximum precision
  )

;; ════════════════════════════════════════════════════════════════════
;; Different Schema Examples
;; ════════════════════════════════════════════════════════════════════

;; Schema for extracting customer feedback
(def feedback-schema
  [:map
   [:overall-sentiment [:enum "very-positive" "positive" "neutral" "negative" "very-negative"]]
   [:rating [:int {:min 1 :max 5}]]
   [:themes 
    [:vector 
     [:map
      [:theme :string]
      [:sentiment [:enum "positive" "negative" "neutral"]]
      [:quotes [:vector :string]]]]]
   [:actionable-insights [:vector :string]]
   [:requires-followup :boolean]])

;; Schema for extracting meeting notes
(def meeting-notes-schema
  [:map
   [:meeting-date :string]
   [:attendees [:vector :string]]
   [:agenda-items
    [:vector
     [:map
      [:topic :string]
      [:discussion-summary :string]
      [:decisions [:vector :string]]
      [:action-items 
       [:vector
        [:map
         [:task :string]
         [:assignee :string]
         [:due-date {:optional true} :string]]]]]]]
   [:next-meeting {:optional true} :string]])

;; ════════════════════════════════════════════════════════════════════
;; REPL Exploration
;; ════════════════════════════════════════════════════════════════════

(comment
  ;; Check what options are being used
  extraction-ai
  ;; => #OpenAI[model: gpt-4o-mini, timeout: 60000ms, defaults: {:temperature 0.2, :max-tokens 2000, :top-p 0.95}]
  
  ;; Validate a schema
  (llm/valid? {:temperature 0.2 :max-tokens 2000})
  ;; => true
  
  ;; See all available options
  (llm/describe-options)
  
  ;; Test schema generation
  (require '[malli.core :as m])
  (m/validate company-analysis-schema
              {:company-name "Test Inc."
               :industry "technology"
               :key-people []
               :products []
               :summary "A test company"
               :sentiment "neutral"})
  ;; => true
  )