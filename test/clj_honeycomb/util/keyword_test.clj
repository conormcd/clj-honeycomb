(ns clj-honeycomb.util.keyword-test
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.spec.test.alpha :refer (with-instrument-disabled)]
            [clojure.test :refer (are deftest is testing)]

            [clj-honeycomb.util.keyword :as util-keyword]))

(deftest stringify-keyword-works
  (testing "Valid input"
    (are [input expected]
         (= expected (util-keyword/stringify-keyword input))

      :key "key"
      :n/key "n/key"))
  (testing "Invalid input"
    (with-instrument-disabled
      (is (thrown? IllegalArgumentException
                   (util-keyword/stringify-keyword nil)))
      (is (thrown? IllegalArgumentException
                   (util-keyword/stringify-keyword 1)))))
  (testing "Randomly generated input"
    (doseq [k (gen/sample (s/gen keyword?))]
      (is (string? (util-keyword/stringify-keyword k))))))
