(ns clj-honeycomb.util.keyword-test
  (:require [clojure.spec.test.alpha :refer (check with-instrument-disabled)]
            [clojure.test :refer (are deftest is testing)]

            [clj-honeycomb.fixtures :refer (use-fixtures)]
            [clj-honeycomb.util.keyword :as util-keyword]))

(use-fixtures)

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
    (check `util-keyword/stringify-keyword)))
