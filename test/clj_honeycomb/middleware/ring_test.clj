(ns clj-honeycomb.middleware.ring-test
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.spec.test.alpha :refer (check)]
            [clojure.test :refer (are deftest is testing)]

            [clj-honeycomb.core :as honeycomb]
            [clj-honeycomb.fields :as fields]
            [clj-honeycomb.fixtures :refer (sample-ring-request
                                            sample-ring-request-extracted
                                            sample-ring-response
                                            sample-ring-response-extracted
                                            use-fixtures)]
            [clj-honeycomb.middleware.ring :as middle]
            [clj-honeycomb.propagation :as propagation]
            [clj-honeycomb.testing-utils :refer (validate-events)])
  (:import (io.honeycomb.libhoney Event)))

(use-fixtures)

(defn- with-deterministic-tracing-ids-fn
  [f]
  (let [trace-counter (atom 0)
        span-counter (atom 0)]
    (with-redefs [honeycomb/generate-trace-id
                  (fn []
                    (str "deterministic-trace-id-" (swap! trace-counter inc)))
                  honeycomb/generate-span-id
                  (fn []
                    (str "deterministic-span-id-" (swap! span-counter inc)))]
      (f))))

(defmacro with-deterministic-tracing-ids
  [& body]
  `(#'with-deterministic-tracing-ids-fn (fn [] ~@body)))

(deftest get-request-header-works
  (testing "Known values"
    (are [request header expected]
         (= expected (#'middle/get-request-header request header))

      {} "Host" nil
      {} "host" nil
      sample-ring-request "Host" "localhost"
      sample-ring-request "host" "localhost")))

(deftest trace-data-works
  (testing "Valid data"
    (doseq [trace-data (gen/sample (s/gen :clj-honeycomb.propagation/propagation-data))]
      (doseq [header-key [:X-Honeycomb-Trace
                          :x-honeycomb-trace
                          "X-Honeycomb-Trace"
                          "x-honeycomb-trace"]]
        (is (= trace-data
               (#'middle/trace-data {:headers {header-key (propagation/pack trace-data)}}))))))
  (testing "Invalid data"
    (is (= {} (#'middle/trace-data {:headers {:x-honeycomb-trace "corrupt"}})))
    (with-redefs [propagation/unpack (fn [_header-string]
                                       (throw (Exception. "Barf")))]
      (is (= {} (#'middle/trace-data {:headers {:x-honeycomb-trace "corrupt"}}))))))

(deftest default-extract-request-fields-works
  (testing "Known values"
    (with-deterministic-tracing-ids
      (are [input expected]
           (= expected (#'middle/default-extract-request-fields input))

        {} {}
        sample-ring-request sample-ring-request-extracted)))
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

(deftest ring-request->event-data-works
  (testing "Known values"
    (with-deterministic-tracing-ids
      (are [event-data extract-request-fields request expected]
           (= expected (#'middle/ring-request->event-data event-data extract-request-fields request))

        {}
        #'middle/default-extract-request-fields
        sample-ring-request
        (assoc sample-ring-request-extracted
               :name "GET /foo"
               :traceId "deterministic-trace-id-1")

        {:foo "bar"}
        #'middle/default-extract-request-fields
        sample-ring-request
        (assoc sample-ring-request-extracted
               :name "GET /foo"
               :traceId "deterministic-trace-id-2"
               :foo "bar")

        {}
        #'middle/default-extract-request-fields
        (assoc-in sample-ring-request
                  [:headers "X-Honeycomb-Trace"]
                  (propagation/pack {:trace-id "TRACE"
                                     :version 1}))
        (assoc sample-ring-request-extracted
               "ring.request.headers.X-Honeycomb-Trace" (propagation/pack
                                                         {:trace-id "TRACE"
                                                          :version 1})
               :name "GET /foo"
               :traceId "TRACE")

        {}
        #'middle/default-extract-request-fields
        (assoc-in sample-ring-request
                  [:headers "X-Honeycomb-Trace"]
                  (propagation/pack {:trace-id "TRACE"
                                     :parent-span-id "PARENT"
                                     :version 1}))
        (assoc sample-ring-request-extracted
               "ring.request.headers.X-Honeycomb-Trace" (propagation/pack
                                                         {:trace-id "TRACE"
                                                          :parent-span-id "PARENT"
                                                          :version 1})
               :name "GET /foo"
               :parentId "PARENT"
               :traceId "TRACE")

        {}
        #'middle/default-extract-request-fields
        (assoc-in sample-ring-request
                  [:headers "X-Honeycomb-Trace"]
                  (propagation/pack {:trace-id "TRACE"
                                     :context {"foo" "bar"}
                                     :version 1}))
        (assoc sample-ring-request-extracted
               "foo" "bar"
               "ring.request.headers.X-Honeycomb-Trace" (propagation/pack
                                                         {:trace-id "TRACE"
                                                          :context {"foo" "bar"}
                                                          :version 1})
               :name "GET /foo"
               :traceId "TRACE"))))
  (testing "Randomly generated data"
    (check `middle/ring-request->event-data)))

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
         (with-deterministic-tracing-ids
           ((middle/with-honeycomb-event happy-handler) sample-ring-request)))
       (fn [events errors]
         (is (empty? errors))
         (is (= 1 (count events)))
         (let [event (some->> events seq first (#(.getFields ^Event %)) (into {}))]
           (is (float? (get event "durationMs")))
           (is (= (->> (merge sample-ring-request-extracted
                              sample-ring-response-extracted
                              {"name" "GET /foo"
                               "traceId" "deterministic-trace-id-1"
                               "id" "deterministic-span-id-1"
                               "parentId" nil})
                       fields/realize)
                  (dissoc event "durationMs")))))))
    (testing "Default options (sad path)"
      (validate-events
       (fn []
         (with-deterministic-tracing-ids
           (is (thrown? Exception
                        ((middle/with-honeycomb-event sad-handler) sample-ring-request)))))
       (fn [events errors]
         (is (empty? errors))
         (is (= 1 (count events)))
         (let [event (some->> events first (#(.getFields ^Event %)) (into {}))]
           (is (float? (get event "durationMs")))
           (is (= (->> (merge sample-ring-request-extracted
                              {"name" "GET /foo"
                               "traceId" "deterministic-trace-id-1"
                               "id" "deterministic-span-id-1"
                               "parentId" nil
                               "exception" sample-exception})
                       fields/realize)
                  (dissoc event "durationMs")))))))
    (testing "Try out all the options"
      (validate-events
       (fn []
         (with-deterministic-tracing-ids
           ((middle/with-honeycomb-event
              {:extract-request-fields (fn [request]
                                         {:method (:request-method request)})
               :extract-response-fields (fn [response]
                                          {:status (:status response)})
               :honeycomb-event-data {:fixed "field"}
               :honeycomb-event-options {:data-set "another"}}
              happy-handler)
            sample-ring-request)))
       (fn [events errors]
         (is (empty? errors))
         (is (= 1 (count events)))
         (when-let [^Event event (first events)]
           (is (= "another" (.getDataset event)))
           (let [fields (into {} (.getFields event))]
             (is (float? (get fields "durationMs")))
             (is (= {"fixed" "field"
                     "id" "deterministic-span-id-1"
                     "method" ":get"
                     "name" "GET /foo"
                     "parentId" nil
                     "status" 200
                     "traceId" "deterministic-trace-id-1"}
                    (dissoc fields "durationMs"))))))))
    (testing "Async handler (happy path)"
      (validate-events
       (fn []
         (with-deterministic-tracing-ids
           ((middle/with-honeycomb-event happy-handler)
            sample-ring-request
            (fn [response]
              (is (= sample-ring-response response))
              response)
            (fn [exception]
              (is (nil? exception))))))
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
                   "ring.response.status" 200
                   "name" "GET /foo"
                   "traceId" "deterministic-trace-id-1"
                   "id" "deterministic-span-id-1"
                   "parentId" nil}
                  (dissoc (into {} (.getFields event)) "durationMs")))))))
    (testing "Async handler (sad path)"
      (validate-events
       (fn []
         (with-deterministic-tracing-ids
           ((middle/with-honeycomb-event sad-handler)
            sample-ring-request
            (fn [response]
              (is (nil? response))
              response)
            (fn [exception]
              (is (= sample-exception exception))))))
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
                   "exception" sample-exception
                   "name" "GET /foo"
                   "traceId" "deterministic-trace-id-1"
                   "id" "deterministic-span-id-1"
                   "parentId" nil}
                  (dissoc (into {} (.getFields event)) "durationMs")))))))
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
