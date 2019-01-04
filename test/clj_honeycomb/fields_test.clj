(ns clj-honeycomb.fields-test
  (:require [clj-honeycomb.global-fixtures :refer (kitchen-sink-realized make-kitchen-sink)]

            [clojure.test :refer (are deftest is testing)]

            [clj-honeycomb.fields :as fields])
  (:import (io.honeycomb.libhoney ValueSupplier)))

(deftest ->ValueSupplier-works
  (testing "Simple no-arg function"
    (let [v (fields/->ValueSupplier (constantly 42))]
      (is (instance? ValueSupplier v))
      (is (= 42 (.supply v)))))
  (testing "Function with args"
    (let [v (fields/->ValueSupplier inc 1)]
      (is (instance? ValueSupplier v))
      (is (= 2 (.supply v))))))

(deftest stringify-key-works
  (testing "Happy path"
    (are [input expected]
         (= expected (#'fields/stringify-key input))

      :key "key"
      :key/name "key/name"
      "String" "String"))
  (testing "Sad path"
    (is (thrown? IllegalArgumentException (#'fields/stringify-key nil)))
    (is (thrown? IllegalArgumentException (#'fields/stringify-key 1)))
    (is (thrown? IllegalArgumentException (#'fields/stringify-key [1])))))

(deftest realize-value-works
  (testing "Testing the kitchen sink"
    (let [kitchen-sink (atom (make-kitchen-sink))
          expected (atom kitchen-sink-realized)]
      ; Make sure we have the same keys in both
      (is (= (sort (keys @expected)) (sort (map name (keys @kitchen-sink)))))

      ; Don't bother testing the `:map` key since it's a repeat of the parent
      (swap! kitchen-sink dissoc :map)
      (swap! expected dissoc "map")

      ; Test each key separately to make failures easier to debug
      (doseq [[k v] @expected]
        (is (= v (#'fields/realize-value (get @kitchen-sink (keyword k)))))
        (swap! kitchen-sink dissoc k)
        (swap! expected dissoc k))))
  (testing "Make sure we cover ValueSuppliers too"
    (is (= 2 (#'fields/realize-value (fields/->ValueSupplier inc 1))))))

(deftest maybe-value-supplier-works
  (let [m (->> (make-kitchen-sink)
               (map (fn [[k v]]
                      [k (#'fields/maybe-value-supplier v)]))
               (into {}))]
    (is (= #{:double
             :exception
             :keyword
             :long
             :nil
             :ratio
             :string}
           (->> m
                (remove (comp (partial instance? ValueSupplier) val))
                (map key)
                set)))
    (is (= #{:agent
             :atom
             :cycle
             :delay
             :future-finished
             :future-running
             :iterate
             :lazy-seq
             :list
             :map
             :promise-delivered
             :promise-pending
             :range
             :ref
             :repeat
             :set
             :vector
             :volatile}
           (->> m
                (filter (comp (partial instance? ValueSupplier) val))
                (map key)
                set)))))

(deftest separate-works
  (testing "Static fields are passed through unchanged"
    (is (= [{"foo" 42} {}] (fields/separate {:foo 42})))
    (is (= [{"foo" 42} {}] (fields/separate {"foo" 42}))))
  (testing "Dynamic fields are properly delivered"
    (let [[static-fields dynamic-fields] (fields/separate {:foo (atom 42)})]
      (is (= {} static-fields))
      (is (= 1 (count dynamic-fields)))
      (is (= 42 (.supply ^ValueSupplier (get dynamic-fields "foo"))))))
  (testing "Keys must be keywords or strings"
    (is (thrown? IllegalArgumentException (fields/separate {1 1})))))

(deftest realize-works
  (is (= kitchen-sink-realized (fields/realize (make-kitchen-sink)))))
