#!/usr/bin/env bb


(require
 '[clojure.string :as str]
 '[co.poyo.clj-llm.core :as llm]
 '[co.poyo.clj-llm.content :as content]
 '[co.poyo.clj-llm.backend.openrouter :as openrouter])

(defn print-receipt-table [structured-output]
  (let [{:keys [currency items total date store]} structured-output
        max-item-length (apply max (map #(count (:name %)) items))
        max-category-length (apply max (map #(count (:category %)) items))]
    (println "Store:" store)
    (println "Date:" date)
    (println (str/join (repeat 55 "-")))
    (println (format (str "%-" (inc max-item-length) "s %-" max-category-length "s %-8s %-10s %-10s %-10s")
                     "Name" "Category" "Qty" "Unit" "Price" "Total"))
    (println (str/join (repeat 55 "-")))
    (doseq [{:keys [name category quantity-of-value value total-price]} (sort-by :category items)]
      (let [{:keys [unit price]} value]
        (println (format (str "%-" (inc max-item-length) "s %-" max-category-length "s %-8.2f %-10s %-10.2f %-10.2f")
                         name
                         category
                         quantity-of-value
                         unit
                         price
                         total-price))))
    (println (str/join (repeat 55 "-")))
    (println "Total:" total currency)))

(def ai (openrouter/backend {:defaults {:model "google/gemini-3-flash-preview"}}))

(def ReceiptSchema
  [:map
   [:currency :string]
   [:items [:vector
            [:map
             [:name {:description "expand from abreviations, convert to english"} :string]
             [:category {:description "eg. dairy, produce, meat etc"} :string]
             [:value [:map
                      [:unit {:description "eg. kg, per-unit etc"} :string]
                      [:price :double]]]
             [:quantity-of-value :double]
             [:total-price :double]]]]
   [:total :double]
   [:date :string]
   [:store :string]])

(def system-prompt "Extract structured data from the receipt image and return it in the specified schema format.")

(def image-path (first *command-line-args*))

(let [result
      (llm/generate ai
                    {:schema ReceiptSchema
                     :system-prompt system-prompt}
                    [(content/image image-path  {:max-width 512})])]
  (print-receipt-table (:structured result)))
