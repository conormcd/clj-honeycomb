(ns clj-honeycomb.util.map-test
  (:require [clojure.string :as str]
            [clojure.test :refer (are deftest is testing)]

            [clj-honeycomb.util.map :as util-map]))

(deftest stringify-keys-works
  (are [input expected]
       (= expected (util-map/stringify-keys input))

    nil nil
    {} {}
    {:foo :bar} {"foo" :bar}
    {:foo {:bar :baz}} {"foo" {"bar" :baz}}
    {{:foo :bar} :baz} {{"foo" :bar} :baz}
    {:foo/bar :baz/quux} {"foo/bar" :baz/quux}))

(deftest paths-works
  (are [input expected]
       (= expected (util-map/paths input))

    nil []
    {} []
    {:foo :bar} [[:foo]]
    {:foo :bar :bar {:baz :quux}} [[:foo] [:bar :baz]]))

(deftest flatten-works
  (testing "The basics work"
    (are [input expected]
         (= expected (util-map/flatten identity input))

      nil nil
      {} {}
      {:foo :bar} {[:foo] :bar}
      {:foo :bar :bar {:baz :quux}} {[:foo] :bar [:bar :baz] :quux}))
  (testing "The key creation function works as expected"
    (is (= {"foo" :bar
            "bar.baz" :quux}
           (util-map/flatten (fn [path]
                               (str/join "." (map name path)))
                             {:foo :bar
                              :bar {:baz :quux}})))))

(deftest flatten-and-stringify-works
  (are [input expected]
       (= expected (util-map/flatten-and-stringify "map." input))

    nil nil
    {} {}
    {:foo :bar} {"map.foo" :bar}
    {:foo :bar :bar {:baz :quux}} {"map.foo" :bar "map.bar.baz" :quux}
    {:foo :bar :bar {1 :quux}} {"map.foo" :bar "map.bar.1" :quux}))
