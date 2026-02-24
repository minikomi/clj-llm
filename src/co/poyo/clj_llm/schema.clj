(ns co.poyo.clj-llm.schema
  "Malli schema → JSON Schema conversion for LLM tool/function calling.
   
   NOTE: Structured output is currently implemented by sending the schema
   as a tool with tool_choice=required. This works across all providers
   (OpenAI, Anthropic, OpenAI-compatible) but means:
   - The model thinks it's calling a function, not filling a schema
   - OpenAI's native response_format JSON mode is not used
   - Tool names are auto-generated when not provided explicitly
   
   This is a pragmatic choice for provider-agnostic behavior."
  (:require [malli.core :as m]
            [clojure.string :as str]))

(declare malli->json-schema)

(defn- infer-tool-metadata
  "Auto-generate function name and description from schema properties"
  [compiled-schema]
  (let [children (m/children compiled-schema)
        field-names (map (comp name first) (filter vector? children))
        raw-name (str "extract_" (str/join "_" field-names))
        sanitized (-> raw-name
                      (str/replace #"[^a-zA-Z0-9_]" "_")
                      (subs 0 (min 64 (count raw-name))))
        description (str "Extract " (str/join ", " field-names) " from input")]
    {:name sanitized :description description}))

(defn- extract-properties [map-schema depth]
  (let [compiled (if (m/schema? map-schema) map-schema (m/schema map-schema))]
    (when (not= :map (m/type compiled))
      (throw (ex-info "Schema must be a Malli [:map …] schema"
                      {:expected '[:map ...]
                       :actual   (m/type compiled)
                       :schema   (m/form compiled)})))
    (let [result (reduce
                  (fn [acc child]
                    (if (and (vector? child) (keyword? (first child)))
                      (let [prop-name (name (first child))
                            [props schema-val] (if (= 3 (count child))
                                                 [(second child) (nth child 2)]
                                                 [{} (second child)])
                            json-schema (cond-> (malli->json-schema schema-val (inc depth))
                                          (contains? props :description)
                                          (assoc :description (:description props)))]
                        (cond-> (assoc-in acc [:properties prop-name] json-schema)
                          (not (:optional props)) (update :required conj prop-name)))
                      acc))
                  {:properties {} :required []}
                  (m/children compiled))]
      (cond-> result
        (empty? (:required result)) (dissoc :required)))))

(defn malli->json-schema
  "Convert a Malli schema to a JSON Schema object.
   Always returns a plain JSON Schema (no tool-calling wrapper)."
  ([schema] (malli->json-schema schema 0))
  ([schema depth]
   (if-not schema
     {:type "null"}
     (let [compiled-schema (m/schema schema)
           schema-type (when compiled-schema (m/type compiled-schema))
           description (when compiled-schema (:description (m/properties compiled-schema)))
           base-schema (cond-> {} (not (str/blank? description)) (assoc :description description))]
       (case schema-type
         :string (assoc base-schema :type "string")
         :int (assoc base-schema :type "integer")
         :double (assoc base-schema :type "number")
         :boolean (assoc base-schema :type "boolean")
         :any (assoc base-schema :type "object")
         :nil (assoc base-schema :type "null")
         :uuid (assoc base-schema :type "string" :pattern "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

         (:vector :sequential)
         (let [items-schema (first (m/children compiled-schema))]
           (assoc base-schema :type "array" :items (malli->json-schema (m/form items-schema) (inc depth))))

         :tuple
         (let [item-schemas (m/children compiled-schema)]
           (assoc base-schema :type "array" :items (mapv #(malli->json-schema (m/form %) (inc depth)) item-schemas)
                  :minItems (count item-schemas) :maxItems (count item-schemas)))
         :map
         (merge base-schema {:type "object"} (extract-properties compiled-schema depth))

         :enum
         (let [values (m/children compiled-schema)]
           (assoc base-schema :type (cond (every? string? values) "string" (every? number? values) "number" :else "string")
                  :enum values))

         (:> :>= :< :<= := :not=)
         (let [value (first (m/children compiled-schema))
               base-type (if (integer? value) "integer" "number")]
           (case schema-type
             :>    (assoc base-schema :type base-type :exclusiveMinimum value)
             :>=   (assoc base-schema :type base-type :minimum value)
             :<    (assoc base-schema :type base-type :exclusiveMaximum value)
             :<=   (assoc base-schema :type base-type :maximum value)
             :=    (assoc base-schema :type base-type :const value)
             :not= (assoc base-schema :type base-type)))

         :re
         (let [pattern (first (m/children compiled-schema))]
           (assoc base-schema :type "string" :pattern (str pattern)))

         :maybe
         (let [inner (malli->json-schema (m/form (first (m/children compiled-schema))) (inc depth))]
           {"anyOf" [inner {"type" "null"}]})

         (assoc base-schema :type "object"))))))


(defn malli->tool-definition
  "Convert a Malli :map schema to a tool/function definition for LLM APIs.
   Returns {:type 'function' :function {:name ... :description ... :parameters <json-schema>}}.
   Name and description are taken from schema properties or auto-generated from field names."
  [schema]
  (let [compiled (m/schema schema)
        properties (m/properties compiled)
        auto-info (infer-tool-metadata compiled)
        tool-name (:name properties (:name auto-info))
        tool-desc (:description properties (:description auto-info))
        json-schema (malli->json-schema schema)]
    {:type "function"
     :function {:name tool-name
                :description tool-desc
                :parameters json-schema}}))
