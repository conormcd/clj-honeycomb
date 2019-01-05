(ns clj-honeycomb.util.keyword-test
  (:require [clojure.test :refer (are deftest)]

            [clj-honeycomb.util.keyword :as util-keyword]))

(deftest stringify-keyword-works
  (are [input expected]
       (= expected (util-keyword/stringify-keyword input))

    nil nil
    :key "key"
    :n/key "n/key"))
