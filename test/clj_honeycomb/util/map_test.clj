(ns clj-honeycomb.util.map-test
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.spec.test.alpha :refer (with-instrument-disabled)]
            [clojure.string :as str]
            [clojure.test :refer (are deftest is testing)]

            [clj-honeycomb.util.map :as util-map]))

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
    (doseq [m (gen/sample (s/gen map?))]
      (let [result (util-map/stringify-keys m)]
        (is (map? result))
        (is (every? string? (keys result)))))))

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
    (doseq [m (gen/sample (s/gen map?))]
      (let [paths (util-map/paths m)]
        (is (sequential? paths))
        (is (every? vector? paths))))))

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
    (doseq [f (gen/sample (s/gen #{identity}))]
      (doseq [m (gen/sample (s/gen map?))]
        (let [res (util-map/flatten f m)]
          (is (map? res))
          (doseq [[k v] res]
            (is (vector? k))
            (is (not (map? v)))))))))

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
    (doseq [prefix (gen/sample (s/gen string?))]
      (doseq [m (gen/sample (s/gen map?))]
        (let [res (util-map/flatten-and-stringify prefix m)]
          (is (map? res))
          (doseq [[k v] res]
            (is (string? k))
            (is (str/starts-with? k prefix))
            (is (not (map? v)))))))))
