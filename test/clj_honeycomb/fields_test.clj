(ns clj-honeycomb.fields-test
  (:use [clojure.future])
  (:require [clojure.spec.test.alpha :refer (check with-instrument-disabled)]
            [clojure.test :refer (are deftest is testing)]

            [clj-honeycomb.fields :as fields]
            [clj-honeycomb.fixtures :refer (kitchen-sink-realized
                                            make-kitchen-sink
                                            use-fixtures)])
  (:import (java.util UUID)
           (clojure.lang IBlockingDeref
                         IDeref
                         IPending
                         Repeat)
           (io.honeycomb.libhoney ValueSupplier)))

(set! *warn-on-reflection* true)

(use-fixtures)

(deftest ->ValueSupplier-works
  (testing "Simple no-arg function"
    (let [v (fields/->ValueSupplier (constantly 42))]
      (is (instance? ValueSupplier v))
      (is (= 42 (.supply ^ValueSupplier v)))))
  (testing "Function with args"
    (let [v (fields/->ValueSupplier inc 1)]
      (is (instance? ValueSupplier v))
      (is (= 2 (.supply ^ValueSupplier v))))))

(deftest prepare-value-for-json-works
  (testing "Known values"
    (let [uuid (UUID/randomUUID)]
      (are [input expected]
           (= expected (#'fields/prepare-value-for-json input))

        nil nil
        1 1
        1.0 1.0
        2/4 0.5
        "string" "string"
        :keyword ":keyword"
        uuid (str uuid))))
  (testing "Randomly generated values"
    (check `fields/prepare-value-for-json)))

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
    (is (= 2 (#'fields/realize-value (fields/->ValueSupplier inc 1)))))
  (testing "Randomly generated data"
    (check `fields/realize-value)))

(deftest maybe-value-supplier-works
  (testing "Known values"
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
               :string
               :uuid}
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
  (testing "Randomly generated data"
    (check `fields/maybe-value-supplier)))

(deftest separate-works
  (testing "Static fields are passed through unchanged"
    (is (= [{"foo" 42} {}] (fields/separate {:foo 42})))
    (is (= [{"foo" 42} {}] (fields/separate {"foo" 42}))))
  (testing "Dynamic fields are properly delivered"
    (let [[static-fields dynamic-fields] (fields/separate {:foo (atom 42)})]
      (is (= {} static-fields))
      (is (= 1 (count dynamic-fields)))
      (is (= 42 (.supply ^ValueSupplier (get dynamic-fields "foo"))))))
  (testing "Invalid input"
    (with-instrument-disabled
      (is (thrown? IllegalArgumentException (fields/separate nil)))
      (is (thrown? IllegalArgumentException (fields/separate 1)))
      (is (thrown? IllegalArgumentException (fields/separate [])))))
  (testing "Randomly generated data"
    (check `fields/separate)))

(deftest realize-works
  (testing "Test the kitchen sink"
    (is (= kitchen-sink-realized (fields/realize (make-kitchen-sink)))))
  (testing "Invalid input"
    (with-instrument-disabled
      (is (thrown? IllegalArgumentException (fields/realize nil)))
      (is (thrown? IllegalArgumentException (fields/realize 1)))
      (is (thrown? IllegalArgumentException (fields/realize [])))))
  (testing "Randomly generated data"
    (check `fields/realize)))
