(ns clj-honeycomb.middleware.ring-test
  (:require [clj-honeycomb.global-fixtures :refer (sample-ring-request
                                                   sample-ring-request-extracted
                                                   sample-ring-response
                                                   sample-ring-response-extracted)]

            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.spec.test.alpha :refer (check)]
            [clojure.test :refer (are deftest is testing)]

            [clj-honeycomb.fields :as fields]
            [clj-honeycomb.middleware.ring :as middle]
            [clj-honeycomb.testing-utils :refer (validate-events)])
  (:import (io.honeycomb.libhoney Event)))

(deftest default-extract-request-fields-works
  (testing "Known values"
    (are [input expected]
         (= expected (#'middle/default-extract-request-fields input))

      {} {}
      sample-ring-request sample-ring-request-extracted))
  (testing "Randomly generated data"
    (check `middle/default-extract-request-fields)))

(deftest default-extract-response-fields-works
  (testing "Known values"
    (are [input expected]
         (= expected (#'middle/default-extract-response-fields input))

      {} {}
      sample-ring-response sample-ring-response-extracted))
  (testing "Randomly generated data"
    (check `middle/default-extract-response-fields)))

(deftest with-honeycomb-event-works
  (let [happy-handler (fn
                        ([request]
                         (is (= sample-ring-request request))
                         sample-ring-response)
                        ([request respond _raise]
                         (is (= sample-ring-request request))
                         (respond sample-ring-response)))
        sample-exception (Exception. "Thrown from sad-handler")
        sad-handler (fn
                      ([request]
                       (is (= sample-ring-request request))
                       (throw sample-exception))
                      ([request _respond raise]
                       (is (= sample-ring-request request))
                       (raise sample-exception)))]
    (testing "Default options (happy path)"
      (validate-events
       (fn []
         ((middle/with-honeycomb-event happy-handler) sample-ring-request))
       (fn [events errors]
         (is (empty? errors))
         (is (= 1 (count events)))
         (let [event (some->> events seq first (#(.getFields ^Event %)) (into {}))]
           (is (float? (get event "elapsed-ms")))
           (is (= (->> (merge sample-ring-request-extracted
                              sample-ring-response-extracted)
                       fields/realize)
                  (dissoc event "elapsed-ms")))))))
    (testing "Default options (sad path)"
      (validate-events
       (fn []
         (is (thrown? Exception
                      ((middle/with-honeycomb-event sad-handler) sample-ring-request))))
       (fn [events errors]
         (is (empty? errors))
         (is (= 1 (count events)))
         (let [event (some->> events first (#(.getFields ^Event %)) (into {}))]
           (is (float? (get event "elapsed-ms")))
           (is (= (->> (merge sample-ring-request-extracted
                              {:exception sample-exception})
                       fields/realize)
                  (dissoc event "elapsed-ms")))))))
    (testing "Try out all the options"
      (validate-events
       (fn []
         ((middle/with-honeycomb-event
            {:extract-request-fields (fn [request]
                                       {:method (:request-method request)})
             :extract-response-fields (fn [response]
                                        {:status (:status response)})
             :honeycomb-event-data {:fixed "field"}
             :honeycomb-event-options {:data-set "another"}}
            happy-handler)
          sample-ring-request))
       (fn [events errors]
         (is (empty? errors))
         (is (= 1 (count events)))
         (when-let [^Event event (first events)]
           (is (= "another" (.getDataset event)))
           (let [fields (into {} (.getFields event))]
             (is (float? (get fields "elapsed-ms")))
             (is (= {"fixed" "field"
                     "method" ":get"
                     "status" 200}
                    (dissoc fields "elapsed-ms"))))))))
    (testing "Async handler (happy path)"
      (validate-events
       (fn []
         ((middle/with-honeycomb-event happy-handler)
          sample-ring-request
          (fn [response]
            (is (= sample-ring-response response))
            response)
          (fn [exception]
            (is (nil? exception)))))
       (fn [events errors]
         (is (empty? errors))
         (is (= 1 (count events)))
         (when-let [^Event event (first events)]
           (is (= {"ring.request.headers.host" "localhost"
                   "ring.request.protocol" "HTTP/1.1"
                   "ring.request.query-string" "bar=bar&baz=baz"
                   "ring.request.remote-addr" "localhost"
                   "ring.request.request-method" ":get"
                   "ring.request.scheme" ":http"
                   "ring.request.server-name" "localhost"
                   "ring.request.server-port" 80
                   "ring.request.uri" "/foo"
                   "ring.response.headers.Content-Type" "text/plain"
                   "ring.response.headers.some-other-header" ":ring-is-funny"
                   "ring.response.status" 200}
                  (dissoc (into {} (.getFields event)) "elapsed-ms")))))))
    (testing "Async handler (sad path)"
      (validate-events
       (fn []
         ((middle/with-honeycomb-event sad-handler)
          sample-ring-request
          (fn [response]
            (is (nil? response))
            response)
          (fn [exception]
            (is (= sample-exception exception)))))
       (fn [events errors]
         (is (empty? errors))
         (is (= 1 (count events)))
         (when-let [^Event event (first events)]
           (is (= {"ring.request.headers.host" "localhost"
                   "ring.request.protocol" "HTTP/1.1"
                   "ring.request.query-string" "bar=bar&baz=baz"
                   "ring.request.remote-addr" "localhost"
                   "ring.request.request-method" ":get"
                   "ring.request.scheme" ":http"
                   "ring.request.server-name" "localhost"
                   "ring.request.server-port" 80
                   "ring.request.uri" "/foo"
                   "exception" sample-exception}
                  (dissoc (into {} (.getFields event)) "elapsed-ms")))))))
    (testing "Randomly generated input (happy path)"
      (doseq [options (gen/sample (s/gen :clj-honeycomb.middleware.ring/with-honeycomb-event-options))]
        (validate-events
         (fn []
           ((middle/with-honeycomb-event options happy-handler)
            sample-ring-request))
         (fn [events errors]
           (if (empty? errors)
             (do
               (is (empty? errors))
               (is (= 1 (count events))))
             (do
               (is (empty? events))
               (is (= 1 (count errors)))
               (is (< 1 (:sample-rate (:honeycomb-event-options options))))))))))))

(deftest nonsense-tests-just-to-cover-spec
  (s/explain-data :clj-honeycomb.middleware.ring/with-honeycomb-event-options {:foo {:bar :baz}}))
