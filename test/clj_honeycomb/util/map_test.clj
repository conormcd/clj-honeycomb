(ns clj-honeycomb.util.map-test
  (:require [clojure.spec.test.alpha :refer (check with-instrument-disabled)]
            [clojure.test :refer (are deftest is testing)]

            [clj-honeycomb.fixtures :refer (use-fixtures)]
            [clj-honeycomb.util.map :as util-map]))

(use-fixtures)

(deftest stringify-keys-works
  (testing "Valid input"
    (are [input expected]
         (= expected (util-map/stringify-keys input))

      {} {}
      {:foo :bar} {"foo" :bar}
      {:foo {:bar :baz}} {"foo" {"bar" :baz}}
      {{:foo :bar} :baz} {{"foo" :bar} :baz}
      {:foo/bar :baz/quux} {"foo/bar" :baz/quux}))
  (testing "Invalid input"
    (with-instrument-disabled
      (is (thrown? IllegalArgumentException (util-map/stringify-keys nil)))
      (is (thrown? IllegalArgumentException (util-map/stringify-keys [])))))
  (testing "Randomly generated input"
    (check `util-map/stringify-keys)))

(deftest paths-works
  (testing "Valid input"
    (are [input expected]
         (= expected (util-map/paths input))

      {} []
      {:foo :bar} [[:foo]]
      {:foo :bar :bar {:baz :quux}} [[:foo] [:bar :baz]]))
  (testing "Invalid input"
    (with-instrument-disabled
      (is (thrown? IllegalArgumentException (util-map/paths nil)))
      (is (thrown? IllegalArgumentException (util-map/paths [])))))
  (testing "Randomly generated input"
    (check `util-map/paths)))

(deftest flatten-works
  (testing "The basics work"
    (are [input expected]
         (= expected (util-map/flatten identity input))

      {} {}
      {:foo :bar} {[:foo] :bar}
      {:foo :bar :bar {:baz :quux}} {[:foo] :bar [:bar :baz] :quux}))
  (testing "Invalid input"
    (with-instrument-disabled
      (is (thrown? IllegalArgumentException (util-map/flatten nil {})))
      (is (thrown? IllegalArgumentException (util-map/flatten identity nil)))))
  (testing "Randomly generated input"
    (check `util-map/flatten)))

(deftest flatten-and-stringify-works
  (testing "Valid input"
    (are [input expected]
         (= expected (util-map/flatten-and-stringify "map." input))

      {} {}
      {:foo :bar} {"map.foo" :bar}
      {:foo :bar :bar {:baz :quux}} {"map.foo" :bar "map.bar.baz" :quux}
      {:foo :bar :bar {1 :quux}} {"map.foo" :bar "map.bar.1" :quux}))
  (testing "Invalid input"
    (with-instrument-disabled
      (is (thrown? IllegalArgumentException
                   (util-map/flatten-and-stringify nil {})))
      (is (thrown? IllegalArgumentException
                   (util-map/flatten-and-stringify "map." nil)))
      (is (thrown? IllegalArgumentException
                   (util-map/flatten-and-stringify "map." [])))))
  (testing "Randomly generated input"
    (check `util-map/flatten-and-stringify)))
