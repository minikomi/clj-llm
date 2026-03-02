(ns co.poyo.clj-llm.schema
  "Malli schema → JSON Schema conversion for LLM tool/function calling.
   
   Structured output is implemented by sending the schema as a tool with
   tool_choice=required. This works across all providers but means the model
   thinks it's calling a function. A pragmatic choice for provider-agnostic behavior."
  (:require [malli.core :as m]
            [clojure.string :as str]))

(declare malli->json-schema)

;; Simple type mappings — no special handling needed
(def ^:private simple-types
  {:string  {:type "string"}
   :int     {:type "integer"}
   :double  {:type "number"}
   :boolean {:type "boolean"}
   :any     {:type "object"}
   :nil     {:type "null"}
   :uuid    {:type "string"
             :pattern "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"}})

(defn- extract-properties
  "Extract JSON Schema properties from a Malli :map schema."
  [compiled depth]
  (reduce
   (fn [acc child]
     (if-not (and (vector? child) (keyword? (first child)))
       acc
       (let [[k & rest] child
             [props schema-val] (if (= 2 (count rest))
                                 [(first rest) (second rest)]
                                 [{} (first rest)])
             prop-name (name k)
             js (cond-> (malli->json-schema schema-val (inc depth))
                  (:description props) (assoc :description (:description props)))]
         (cond-> (assoc-in acc [:properties prop-name] js)
           (not (:optional props)) (update :required conj prop-name)))))
   {:properties {} :required []}
   (m/children compiled)))

(defn malli->json-schema
  "Convert a Malli schema to a JSON Schema object."
  ([schema] (malli->json-schema schema 0))
  ([schema depth]
   (if-not schema
     {:type "null"}
     (let [compiled (m/schema schema)
           schema-type (m/type compiled)
           desc (some-> (m/properties compiled) :description)
           base (cond-> {} desc (assoc :description desc))]
       ;; Simple types first
       (if-let [simple (simple-types schema-type)]
         (merge base simple)
         ;; Complex types
         (case schema-type
           (:vector :sequential)
           (assoc base :type "array"
                  :items (malli->json-schema (m/form (first (m/children compiled))) (inc depth)))

           :tuple
           (let [children (m/children compiled)]
             (assoc base :type "array"
                    :items (mapv #(malli->json-schema (m/form %) (inc depth)) children)
                    :minItems (count children)
                    :maxItems (count children)))

           :map
           (let [{:keys [properties required]} (extract-properties compiled depth)]
             (cond-> (assoc base :type "object" :properties properties)
               (seq required) (assoc :required required)))

           :enum
           (let [values (m/children compiled)]
             (assoc base
                    :type (cond (every? string? values) "string"
                                (every? number? values) "number"
                                :else "string")
                    :enum values))

           (:> :>= :< :<= := :not=)
           (let [v (first (m/children compiled))
                 t (if (integer? v) "integer" "number")]
             (case schema-type
               :>    (assoc base :type t :exclusiveMinimum v)
               :>=   (assoc base :type t :minimum v)
               :<    (assoc base :type t :exclusiveMaximum v)
               :<=   (assoc base :type t :maximum v)
               :=    (assoc base :type t :const v)
               :not= (assoc base :type t)))

           :re
           (assoc base :type "string" :pattern (str (first (m/children compiled))))

           :maybe
           {"anyOf" [(malli->json-schema (m/form (first (m/children compiled))) (inc depth))
                     {"type" "null"}]}

           ;; Unknown → object
           (assoc base :type "object")))))))

(defn- infer-tool-name
  "Auto-generate a tool name from map schema field names."
  [compiled]
  (let [fields (->> (m/children compiled)
                    (filter vector?)
                    (map (comp name first)))]
    (-> (str "extract_" (str/join "_" fields))
        (str/replace #"[^a-zA-Z0-9_]" "_")
        (subs 0 (min 64 (count (str "extract_" (str/join "_" fields))))))))

(defn malli->tool-definition
  "Convert a Malli :map schema to a tool/function definition for LLM APIs.
   Returns {:type 'function' :function {:name ... :description ... :parameters ...}}."
  [schema]
  (let [compiled (m/schema schema)
        props (m/properties compiled)
        fields (->> (m/children compiled)
                    (filter vector?)
                    (map (comp name first)))
        tool-name (or (:name props) (infer-tool-name compiled))
        tool-desc (or (:description props)
                      (str "Extract " (str/join ", " fields) " from input"))]
    {:type "function"
     :function {:name tool-name
                :description tool-desc
                :parameters (malli->json-schema schema)}}))
