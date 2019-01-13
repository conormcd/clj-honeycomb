(ns clj-honeycomb.testing-utils-test
  (:require [clj-honeycomb.global-fixtures :refer (kitchen-sink-realized make-kitchen-sink)]

            [clojure.test :refer (deftest is testing)]

            [clj-honeycomb.core :as honeycomb]
            [clj-honeycomb.testing-utils :as tu])
  (:import (io.honeycomb.libhoney Event
                                  HoneyClient
                                  ResponseObserver)
           (io.honeycomb.libhoney.responses ClientRejected
                                            ServerAccepted
                                            ServerRejected
                                            Unknown)))

(deftest no-op-client-works
  (testing "Default options work"
    (is (instance? HoneyClient (tu/no-op-client {}))))
  (testing "Sending events works"
    (with-open [client (tu/no-op-client {})]
      (honeycomb/send client {:foo "bar"}))))

(deftest recording-client-works
  (testing "Default options work"
    (is (instance? HoneyClient (tu/recording-client (atom []) {}))))
  (testing "Additional client options can be set"
    (with-open [client (tu/recording-client (atom [])
                                            {:api-host "http://127.0.0.1:123"
                                             :data-set "foo"
                                             :response-observer {:on-unknown (fn [_] nil)}
                                             :sample-rate 3
                                             :write-key "bar"})]
      (is (instance? HoneyClient client))))
  (testing "Global fields work"
    (let [events (atom [])]
      (with-open [client (tu/recording-client events
                                              {:data-set "foo"
                                               :global-fields (make-kitchen-sink)
                                               :write-key "bar"})]
        (is (instance? HoneyClient client))
        (honeycomb/send client {:foo "bar"}))
      (is (= 1 (count @events)))
      (let [expected (assoc kitchen-sink-realized "foo" "bar")
            event (into {} (.getFields ^Event (first @events)))]
        (is (= (sort (keys expected)) (sort (keys event))))
        (doseq [[k v] expected]
          (is (= v (get event k)) k)))))
  (testing "The first argument must be an atom-wrapped vector"
    (is (thrown? IllegalArgumentException (tu/recording-client nil {})))
    (is (thrown? IllegalArgumentException (tu/recording-client (atom nil) {})))))

(deftest recording-response-observer-works
  (testing "It records only the errors"
    (let [cr (reify ClientRejected)
          sa (reify ServerAccepted)
          sr (reify ServerRejected)
          u (reify Unknown)
          errors (atom [])
          ^ResponseObserver rro (#'tu/recording-response-observer errors)]
      (.onClientRejected rro cr)
      (.onServerAccepted rro sa)
      (.onServerRejected rro sr)
      (.onUnknown rro u)
      (is (= [cr sr u] @errors))))
  (testing "The first argument must be an atom-wrapped vector"
    (try
      (#'tu/recording-response-observer nil)
      (catch IllegalArgumentException e
        (is e)))))

(deftest validate-events-works
  (tu/validate-events
   (fn []
     (honeycomb/send (make-kitchen-sink)))
   (fn [events errors]
     (is (empty? errors))
     (is (= 1 (count events)))
     (is (= kitchen-sink-realized (.getFields ^Event (first events)))))))
