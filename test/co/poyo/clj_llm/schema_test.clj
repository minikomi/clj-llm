(ns co.poyo.clj-llm.schema-test
  (:require [clojure.test :refer [deftest testing is]]
            [co.poyo.clj-llm.schema :as schema]))

;; ════════════════════════════════════════════════════════════════════
;; Primitives
;; ════════════════════════════════════════════════════════════════════

(deftest test-primitive-types
  (testing "string"
    (is (= {:type "string"} (schema/malli->json-schema :string))))
  (testing "int"
    (is (= {:type "integer"} (schema/malli->json-schema :int))))
  (testing "double"
    (is (= {:type "number"} (schema/malli->json-schema :double))))
  (testing "boolean"
    (is (= {:type "boolean"} (schema/malli->json-schema :boolean))))
  (testing "nil"
    (is (= {:type "null"} (schema/malli->json-schema :nil))))
  (testing "any"
    (is (= {:type "object"} (schema/malli->json-schema :any))))
  (testing "uuid"
    (let [result (schema/malli->json-schema :uuid)]
      (is (= "string" (:type result)))
      (is (string? (:pattern result))))))

(deftest test-null-schema
  (is (= {:type "null"} (schema/malli->json-schema nil))))

;; ════════════════════════════════════════════════════════════════════
;; Collections
;; ════════════════════════════════════════════════════════════════════

(deftest test-vector-schema
  (let [result (schema/malli->json-schema [:vector :string])]
    (is (= "array" (:type result)))
    (is (= {:type "string"} (:items result)))))

(deftest test-sequential-schema
  (let [result (schema/malli->json-schema [:sequential :int])]
    (is (= "array" (:type result)))
    (is (= {:type "integer"} (:items result)))))

(deftest test-tuple-schema
  (let [result (schema/malli->json-schema [:tuple :string :int :boolean])]
    (is (= "array" (:type result)))
    (is (= 3 (:minItems result)))
    (is (= 3 (:maxItems result)))
    (is (= [{:type "string"} {:type "integer"} {:type "boolean"}] (:items result)))))

;; ════════════════════════════════════════════════════════════════════
;; Enum
;; ════════════════════════════════════════════════════════════════════

(deftest test-enum-strings
  (let [result (schema/malli->json-schema [:enum "a" "b" "c"])]
    (is (= "string" (:type result)))
    (is (= ["a" "b" "c"] (:enum result)))))

(deftest test-enum-numbers
  (let [result (schema/malli->json-schema [:enum 1 2 3])]
    (is (= "number" (:type result)))
    (is (= [1 2 3] (:enum result)))))

;; ════════════════════════════════════════════════════════════════════
;; Maybe (nullable)
;; ════════════════════════════════════════════════════════════════════

(deftest test-maybe-schema
  (let [result (schema/malli->json-schema [:maybe :string])]
    (is (= {"anyOf" [{:type "string"} {"type" "null"}]} result))))

;; ════════════════════════════════════════════════════════════════════
;; Regex
;; ════════════════════════════════════════════════════════════════════

(deftest test-regex-schema
  (let [result (schema/malli->json-schema [:re "^[A-Z]+$"])]
    (is (= "string" (:type result)))
    (is (= "^[A-Z]+$" (:pattern result)))))

;; ════════════════════════════════════════════════════════════════════
;; Comparison operators
;; ════════════════════════════════════════════════════════════════════

(deftest test-comparison-schemas
  (testing "> produces exclusiveMinimum"
    (let [result (schema/malli->json-schema [:> 0])]
      (is (= "integer" (:type result)))
      (is (= 0 (:exclusiveMinimum result)))))
  (testing ">= produces minimum"
    (let [result (schema/malli->json-schema [:>= 1.5])]
      (is (= "number" (:type result)))
      (is (= 1.5 (:minimum result)))))
  (testing "< produces exclusiveMaximum"
    (let [result (schema/malli->json-schema [:< 100])]
      (is (= 100 (:exclusiveMaximum result)))))
  (testing "<= produces maximum"
    (let [result (schema/malli->json-schema [:<= 99])]
      (is (= 99 (:maximum result)))))
  (testing "= produces const"
    (let [result (schema/malli->json-schema [:= 42])]
      (is (= 42 (:const result))))))

(deftest test-not-equal-schema
  (testing ":not= produces not-const constraint"
    (let [result (schema/malli->json-schema [:not= 5])]
      (is (= "integer" (:type result)))
      (is (= {:const 5} (:not result))))))

;; ════════════════════════════════════════════════════════════════════
;; Map schemas
;; ════════════════════════════════════════════════════════════════════

(deftest test-map-depth-0-is-plain-object
  (let [result (schema/malli->json-schema [:map [:name :string] [:age :int]])]
    (is (= "object" (:type result)))
    (is (= {:type "string"} (get-in result [:properties "name"])))
    (is (= {:type "integer"} (get-in result [:properties "age"])))))

(deftest test-tool-definition-wraps-in-function
  (let [result (schema/malli->tool-definition [:map [:name :string] [:age :int]])]
    (is (= "function" (:type result)))
    (is (string? (get-in result [:function :name])))
    (is (string? (get-in result [:function :description])))
    (is (= "object" (get-in result [:function :parameters :type])))
    (is (= {:type "string"} (get-in result [:function :parameters :properties "name"])))
    (is (= {:type "integer"} (get-in result [:function :parameters :properties "age"])))))

(deftest test-map-depth-1-plain-object
  (let [result (schema/malli->json-schema [:map [:x :double] [:y :double]])]
    (is (= "object" (:type result)))
    (is (= {:type "number"} (get-in result [:properties "x"])))
    (is (= {:type "number"} (get-in result [:properties "y"])))))

(deftest test-map-required-fields
  (let [result (schema/malli->json-schema
                 [:map [:required-field :string] [:optional-field {:optional true} :string]])]
    (is (= ["required-field"] (get result :required)))))

(deftest test-tool-definition-custom-name-description
  (let [result (schema/malli->tool-definition
                 [:map {:name "my_func" :description "My function"}
                  [:field :string]])]
    (is (= "my_func" (get-in result [:function :name])))
    (is (= "My function" (get-in result [:function :description])))))

(deftest test-nested-maps
  (let [result (schema/malli->json-schema
                 [:map [:address [:map [:city :string] [:zip :string]]]])]
    (is (= "object" (:type result)))
    (is (= "object" (get-in result [:properties "address" :type])))
    (is (= {:type "string"} (get-in result [:properties "address" :properties "city"])))))

(deftest test-field-descriptions
  (let [result (schema/malli->json-schema
                 [:map [:name {:description "The person's name"} :string]])]
    (is (= "The person's name" (get-in result [:properties "name" :description])))))

;; ════════════════════════════════════════════════════════════════════
;; Auto-generated function names
;; ════════════════════════════════════════════════════════════════════

(deftest test-auto-name-sanitizes-hyphens
  (let [result (schema/malli->tool-definition [:map [:full-name :string] [:age :int]])]
    (is (not (re-find #"-" (get-in result [:function :name])))
        "Function name should not contain hyphens")))

(deftest test-auto-name-truncates-long-names
  (let [fields (mapv (fn [i] [(keyword (str "field-" i)) :string]) (range 20))
        schema (into [:map] fields)
        result (schema/malli->tool-definition schema)]
    (is (<= (count (get-in result [:function :name])) 64)
        "Function name should be at most 64 chars")))
