(ns co.poyo.clj-llm.schema
  (:require [malli.core :as m]
            [malli.error :as me]
            [clojure.string :as str]))

(declare malli->json-schema)

(defn- get-schema-description [schema]
  (let [compiled-schema (try (m/schema schema) (catch Exception _ nil))]
    (get (m/properties compiled-schema) :description "")))

(defn- extract-properties [map-schema]
  (let [compiled-map-schema (if (m/schema? map-schema) map-schema (m/schema map-schema))
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
                                        (cond-> (malli->json-schema schema-val)
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
  [schema]
  (if-not schema
    {:type "null"}
    (let [compiled-schema (m/schema schema)
          compiled-schema (if (m/properties compiled-schema) (nth (m/form compiled-schema) 2) compiled-schema)
          schema-type (when compiled-schema (m/type compiled-schema))
          description (when compiled-schema (get (m/properties compiled-schema) :description ""))
          base-schema (cond-> {} (not (str/blank? description)) (assoc :description description))]
      (case schema-type
        :string (assoc base-schema :type "string")
        :int (assoc base-schema :type "integer")
        :double (assoc base-schema :type "number")
        :boolean (assoc base-schema :type "boolean")
        :any (assoc base-schema :type "object")
        :nil (assoc base-schema :type "null")

        (:vector :sequential)
        (let [items-schema (first (m/children compiled-schema))]
          (assoc base-schema :type "array" :items (malli->json-schema (m/form items-schema))))

        :tuple
        (let [item-schemas (m/children compiled-schema)]
          (assoc base-schema :type "array" :items (mapv #(malli->json-schema (m/form %)) item-schemas)
                 :minItems (count item-schemas) :maxItems (count item-schemas)))

        :map
        (merge base-schema {:type "object"} (extract-properties compiled-schema))

        :enum
        (let [values (m/children compiled-schema)]
          (assoc base-schema :type (cond (every? string? values) "string" (every? number? values) "number" :else "string")
                 :enum values))

        (:> :>= :< :<= := :not=)
        (malli->json-schema (m/form (first (m/children compiled-schema))))

        :re
        (let [pattern (first (m/children compiled-schema))]
          (assoc base-schema :type "string" :pattern (str pattern)))

        ;; Basic support for :maybe T -> maps to T but non-required (handled in extract-properties)
        :maybe (malli->json-schema (m/form (first (m/children compiled-schema))))

        ;; Could add more type handlers here (:and, :or, :ref, :map-of, etc.)

        (assoc base-schema :type "object")))))


;; sometimes we have this kind of abstract function
;; (meta f)
;; #:malli.instrument{:original #function[co.poyo.clj-llm.sandbox/transfer-money]}

(defn f->meta [f]
  (let [str-f (str (or (:malli.instrument/original (meta f)) f))]
         (-> str-f
             (clojure.repl/demunge)
             (clojure.string/replace #"@.*$" "")
             (clojure.string/replace #"^#'" "")
             (symbol)
             (find-var)
             (meta))))

(defn get-schema-from-malli-function-registry
  [f]
  (let [f-meta (f->meta f)
        fn-namespace-sym (-> f-meta :ns str symbol)
        fn-name-sym (-> f-meta :name)]
    (m/deref (get-in (m/function-schemas) [fn-namespace-sym fn-name-sym :schema]))))

(defn instrumented-function->json-schema
  [f]
  (let [f-meta (f->meta f)
        f-name (or (-> f-meta :name str) "unnamed-function")
        f-doc (or (:doc f-meta) "")
        full-f-schema (get-schema-from-malli-function-registry f)]
    (when-not full-f-schema
        (throw (ex-info "No schema found via m/=> registry" {:fn f-name})))
    (when-not
        (and (= :=> (first (m/form full-f-schema))) (>= (count (m/form full-f-schema)) 3))
      (throw (ex-info "Schema is not a valid :=> schema" {:fn f-name :schema full-f-schema})))
    (let [input-cat-schema (second (m/form full-f-schema))]
      (when-not (and (vector? input-cat-schema)
                     (= :cat (first input-cat-schema))
                     (= 2 (count input-cat-schema)))
        (throw (ex-info "Input schema structure not [:cat [:map ...]]" {:fn f-name :schema full-f-schema})))
      (malli->json-schema
       (second input-cat-schema)))))
