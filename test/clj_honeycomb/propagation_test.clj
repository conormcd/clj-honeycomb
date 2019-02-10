(ns clj-honeycomb.propagation-test
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.spec.test.alpha :refer (check)]
            [clojure.test :refer (deftest is testing)]

            [clj-honeycomb.propagation :as prop]))

(deftest str->base64-works
  (check `prop/str->base64))

(deftest base64->str-works
  (check `prop/base64->str))

(deftest base64-functions-work
  (testing "Known values"
    (= "Zm9vCg==" (#'prop/str->base64 "foo"))
    (= "foo" (#'prop/base64->str "Zm9vCg==")))
  (testing "Generated values"
    (doseq [s (gen/sample (s/gen string?))]
      (= s (#'prop/base64->str (#'prop/str->base64 s))))))

(deftest pack-works
  (check `prop/pack))

(deftest unpack-works
  (check `prop/unpack))

(deftest pack-unpack-round-trip-with-generated-data
  (doseq [data (gen/sample (s/gen :clj-honeycomb.propagation/propagation-data))]
    (is (= data (prop/unpack (prop/pack data))))))

(deftest nonsense-tests-just-to-cover-spec
  (s/explain-data :clj-honeycomb.propagation/context {1 1})
  (s/explain-data :clj-honeycomb.propagation/propagation-data {})
  (s/explain-data :clj-honeycomb.propagation/propagation-data {:version 1}))
