(ns co.poyo.clj-llm.schema
  (:require [malli.core :as m]
            [clojure.string :as str]))

(declare malli->json-schema)

(defn- auto-generate-function-info
  "Auto-generate function name and description from schema properties"
  [compiled-schema]
  (let [children (m/children compiled-schema)
        field-names (map (comp name first) (filter vector? children))
        name (str "extract_" (clojure.string/join "_" field-names))
        description (str "Extract " (clojure.string/join ", " field-names) " from input")]
    {:name name :description description}))

(defn- extract-properties [map-schema depth]
  (let [compiled-map-schema (if (m/schema? map-schema) map-schema (m/schema map-schema))
        _ (when (not= :map (m/type compiled-map-schema))
            (throw (ex-info "Schema must be a Malli [:map …] schema"
                            {:expected '[:map ...]
                             :actual   (m/type compiled-map-schema)
                             :schema   (m/form compiled-map-schema)})))
        children (m/children compiled-map-schema)
        properties (reduce
                    (fn [acc child]
                      (if (and (vector? child) (keyword? (first child)))
                        (let [k (first child)
                              prop-name (name k)
                              [props schema-val] (if (= 3 (count child))
                                                   [(second child) (nth child 2)]
                                                   [{} (second child)])
                              required? (not (:optional props false))]
                          (-> acc
                              (assoc-in [:properties prop-name]
                                        (cond-> (malli->json-schema schema-val (inc depth))
                                                (contains? props :description)
                                                (assoc :description (:description props))))
                              (cond-> required? (update :required conj prop-name))))
                        acc))
                    {:properties {}, :required []}
                    children)]
    (if (empty? (:required properties))
      (dissoc properties :required)
      properties)))

(defn malli->json-schema
  ([schema] (malli->json-schema schema 0))
  ([schema depth]
   (if-not schema
     {:type "null"}
     (let [compiled-schema (m/schema schema)
           schema-type (when compiled-schema (m/type compiled-schema))
           description (when compiled-schema (get (m/properties compiled-schema) :name ""))
           description (when compiled-schema (get (m/properties compiled-schema) :description ""))
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
         (let [properties (m/properties compiled-schema)
               base-map (merge base-schema {:type "object"} (extract-properties compiled-schema depth))]
           (if (zero? depth)
             (let [auto-info (auto-generate-function-info compiled-schema)
                   name (:name properties (:name auto-info))
                   description (:description properties (:description auto-info))]
               {:type "function"
                :function {:name name
                           :description description
                           :parameters base-map}})
             base-map))

         :enum
         (let [values (m/children compiled-schema)]
           (assoc base-schema :type (cond (every? string? values) "string" (every? number? values) "number" :else "string")
                  :enum values))

         (:> :>= :< :<= := :not=)
         (malli->json-schema (m/form (first (m/children compiled-schema))) (inc depth))

         :re
         (let [pattern (first (m/children compiled-schema))]
           (assoc base-schema :type "string" :pattern (str pattern)))

         :maybe (malli->json-schema (m/form (first (m/children compiled-schema)))
                                    (inc depth))

         (assoc base-schema :type "object"))))))

