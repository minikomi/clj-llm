(ns co.poyo.clj-llm.schema-test
  (:require [clojure.test :refer [deftest testing is]]
            [co.poyo.clj-llm.schema :as schema]))

;; ════════════════════════════════════════════════════════════════════
;; Primitives
;; ════════════════════════════════════════════════════════════════════

(deftest test-primitive-types
  (testing "string"
    (is (= {:type "string"} (schema/malli->json-schema :string 1))))
  (testing "int"
    (is (= {:type "integer"} (schema/malli->json-schema :int 1))))
  (testing "double"
    (is (= {:type "number"} (schema/malli->json-schema :double 1))))
  (testing "boolean"
    (is (= {:type "boolean"} (schema/malli->json-schema :boolean 1))))
  (testing "nil"
    (is (= {:type "null"} (schema/malli->json-schema :nil 1))))
  (testing "any"
    (is (= {:type "object"} (schema/malli->json-schema :any 1))))
  (testing "uuid"
    (let [result (schema/malli->json-schema :uuid 1)]
      (is (= "string" (:type result)))
      (is (string? (:pattern result))))))

(deftest test-null-schema
  (is (= {:type "null"} (schema/malli->json-schema nil 1))))

;; ════════════════════════════════════════════════════════════════════
;; Collections
;; ════════════════════════════════════════════════════════════════════

(deftest test-vector-schema
  (let [result (schema/malli->json-schema [:vector :string] 1)]
    (is (= "array" (:type result)))
    (is (= {:type "string"} (:items result)))))

(deftest test-sequential-schema
  (let [result (schema/malli->json-schema [:sequential :int] 1)]
    (is (= "array" (:type result)))
    (is (= {:type "integer"} (:items result)))))

(deftest test-tuple-schema
  (let [result (schema/malli->json-schema [:tuple :string :int :boolean] 1)]
    (is (= "array" (:type result)))
    (is (= 3 (:minItems result)))
    (is (= 3 (:maxItems result)))
    (is (= [{:type "string"} {:type "integer"} {:type "boolean"}] (:items result)))))

;; ════════════════════════════════════════════════════════════════════
;; Enum
;; ════════════════════════════════════════════════════════════════════

(deftest test-enum-strings
  (let [result (schema/malli->json-schema [:enum "a" "b" "c"] 1)]
    (is (= "string" (:type result)))
    (is (= ["a" "b" "c"] (:enum result)))))

(deftest test-enum-numbers
  (let [result (schema/malli->json-schema [:enum 1 2 3] 1)]
    (is (= "number" (:type result)))
    (is (= [1 2 3] (:enum result)))))

;; ════════════════════════════════════════════════════════════════════
;; Maybe (nullable)
;; ════════════════════════════════════════════════════════════════════

(deftest test-maybe-schema
  (let [result (schema/malli->json-schema [:maybe :string] 1)]
    (is (= {"anyOf" [{:type "string"} {"type" "null"}]} result))))

;; ════════════════════════════════════════════════════════════════════
;; Regex
;; ════════════════════════════════════════════════════════════════════

(deftest test-regex-schema
  (let [result (schema/malli->json-schema [:re "^[A-Z]+$"] 1)]
    (is (= "string" (:type result)))
    (is (= "^[A-Z]+$" (:pattern result)))))

;; ════════════════════════════════════════════════════════════════════
;; Comparison operators
;; ════════════════════════════════════════════════════════════════════

(deftest test-comparison-schemas
  (testing "> produces exclusiveMinimum"
    (let [result (schema/malli->json-schema [:> 0] 1)]
      (is (= "integer" (:type result)))
      (is (= 0 (:exclusiveMinimum result)))))
  (testing ">= produces minimum"
    (let [result (schema/malli->json-schema [:>= 1.5] 1)]
      (is (= "number" (:type result)))
      (is (= 1.5 (:minimum result)))))
  (testing "< produces exclusiveMaximum"
    (let [result (schema/malli->json-schema [:< 100] 1)]
      (is (= 100 (:exclusiveMaximum result)))))
  (testing "<= produces maximum"
    (let [result (schema/malli->json-schema [:<= 99] 1)]
      (is (= 99 (:maximum result)))))
  (testing "= produces const"
    (let [result (schema/malli->json-schema [:= 42] 1)]
      (is (= 42 (:const result))))))

;; ════════════════════════════════════════════════════════════════════
;; Map schemas
;; ════════════════════════════════════════════════════════════════════

(deftest test-map-depth-0-wraps-in-function
  (let [result (schema/malli->json-schema [:map [:name :string] [:age :int]] 0)]
    (is (= "function" (:type result)))
    (is (string? (get-in result [:function :name])))
    (is (string? (get-in result [:function :description])))
    (is (= "object" (get-in result [:function :parameters :type])))
    (is (= {:type "string"} (get-in result [:function :parameters :properties "name"])))
    (is (= {:type "integer"} (get-in result [:function :parameters :properties "age"])))))

(deftest test-map-depth-1-plain-object
  (let [result (schema/malli->json-schema [:map [:x :double] [:y :double]] 1)]
    (is (= "object" (:type result)))
    (is (= {:type "number"} (get-in result [:properties "x"])))
    (is (= {:type "number"} (get-in result [:properties "y"])))))

(deftest test-map-required-fields
  (let [result (schema/malli->json-schema
                 [:map [:required-field :string] [:optional-field {:optional true} :string]] 1)]
    (is (= ["required-field"] (get result :required)))))

(deftest test-map-custom-name-description
  (let [result (schema/malli->json-schema
                 [:map {:name "my_func" :description "My function"}
                  [:field :string]] 0)]
    (is (= "my_func" (get-in result [:function :name])))
    (is (= "My function" (get-in result [:function :description])))))

(deftest test-nested-maps
  (let [result (schema/malli->json-schema
                 [:map [:address [:map [:city :string] [:zip :string]]]] 1)]
    (is (= "object" (:type result)))
    (is (= "object" (get-in result [:properties "address" :type])))
    (is (= {:type "string"} (get-in result [:properties "address" :properties "city"])))))

(deftest test-field-descriptions
  (let [result (schema/malli->json-schema
                 [:map [:name {:description "The person's name"} :string]] 1)]
    (is (= "The person's name" (get-in result [:properties "name" :description])))))

;; ════════════════════════════════════════════════════════════════════
;; Auto-generated function names
;; ════════════════════════════════════════════════════════════════════

(deftest test-auto-name-sanitizes-hyphens
  (let [result (schema/malli->json-schema [:map [:full-name :string] [:age :int]] 0)]
    (is (not (re-find #"-" (get-in result [:function :name])))
        "Function name should not contain hyphens")))

(deftest test-auto-name-truncates-long-names
  (let [fields (mapv (fn [i] [(keyword (str "field-" i)) :string]) (range 20))
        schema (into [:map] fields)
        result (schema/malli->json-schema schema 0)]
    (is (<= (count (get-in result [:function :name])) 64)
        "Function name should be at most 64 chars")))
