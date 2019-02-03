(ns clj-honeycomb.core-test
  (:require [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.spec.test.alpha :refer (check with-instrument-disabled)]
            [clojure.test :refer (are deftest is testing)]

            [stub-http.core :as stub-http]

            [clj-honeycomb.core :as honeycomb]
            [clj-honeycomb.fixtures :refer (kitchen-sink-realized
                                            make-kitchen-sink
                                            use-fixtures)]
            [clj-honeycomb.testing-utils :refer (no-op-client recording-client validate-events)])
  (:import (clojure.lang ExceptionInfo)
           (stub_http.core NanoFakeServer)
           (io.honeycomb.libhoney Event
                                  EventPostProcessor
                                  HoneyClient
                                  Options
                                  ResponseObserver
                                  TransportOptions
                                  ValueSupplier)
           (io.honeycomb.libhoney.responses ClientRejected
                                            ServerAccepted
                                            ServerRejected
                                            Unknown)))

(use-fixtures)

(defn- event->fields
  "A helper to extract the fields from an event while avoiding NPEs and ensuring
   that we get a legit Clojure map out at the end."
  [^Event event]
  (when event
    (into {} (.getFields event))))

(deftest client-options-random-generations
  (doseq [co (gen/sample (s/gen :clj-honeycomb.core/client-options))]
    (is (instance? Options (#'honeycomb/client-options co)))))

(deftest client-options-works
  (testing "It fails when it's missing both data-set and write-key"
    (try
      (#'honeycomb/client-options {})
      (catch ExceptionInfo e
        (is (:clojure.spec.alpha/problems (ex-data e))))))
  (testing "It fails when it's missing data-set"
    (try
      (#'honeycomb/client-options {:write-key "foo"})
      (catch ExceptionInfo e
        (is (:clojure.spec.alpha/problems (ex-data e))))))
  (testing "It fails when it's missing write-key"
    (try
      (#'honeycomb/client-options {:data-set "foo"})
      (catch ExceptionInfo e
        (is (:clojure.spec.alpha/problems (ex-data e))))))
  (testing "Minimum viable arguments"
    (let [^Options options (#'honeycomb/client-options {:data-set "data-set"
                                                        :write-key "write-key"})]
      (is (= "https://api.honeycomb.io/" (str (.getApiHost options))))
      (is (= "data-set" (.getDataset options)))
      (is (nil? (.getEventPostProcessor options)))
      (is (= {} (.getGlobalFields options)))
      (is (= {} (.getGlobalDynamicFields options)))
      (is (= 1 (.getSampleRate options)))
      (is (= "write-key" (.getWriteKey options)))))
  (testing "Can set API host"
    (let [^Options options (#'honeycomb/client-options {:api-host "https://localhost:12345/"
                                                        :data-set "data-set"
                                                        :write-key "write-key"})]
      (is (= "https://localhost:12345/" (str (.getApiHost options))))
      (is (= "data-set" (.getDataset options)))
      (is (nil? (.getEventPostProcessor options)))
      (is (= {} (.getGlobalFields options)))
      (is (= {} (.getGlobalDynamicFields options)))
      (is (= 1 (.getSampleRate options)))
      (is (= "write-key" (.getWriteKey options)))))
  (testing "Can set Event Post Processor"
    (let [epp (reify EventPostProcessor
                (process [_this _event-data]
                  nil))
          ^Options options (#'honeycomb/client-options {:data-set "data-set"
                                                        :event-post-processor epp
                                                        :write-key "write-key"})]
      (is (= "https://api.honeycomb.io/" (str (.getApiHost options))))
      (is (= "data-set" (.getDataset options)))
      (is (= epp (.getEventPostProcessor options)))
      (is (= {} (.getGlobalFields options)))
      (is (= {} (.getGlobalDynamicFields options)))
      (is (= 1 (.getSampleRate options)))
      (is (= "write-key" (.getWriteKey options)))))
  (testing "Can set global fields"
    (let [^Options options (#'honeycomb/client-options {:data-set "data-set"
                                                        :global-fields {:foo 1}
                                                        :write-key "write-key"})]
      (is (= "https://api.honeycomb.io/" (str (.getApiHost options))))
      (is (= "data-set" (.getDataset options)))
      (is (nil? (.getEventPostProcessor options)))
      (is (= {"foo" 1} (.getGlobalFields options)))
      (is (= {} (.getGlobalDynamicFields options)))
      (is (= 1 (.getSampleRate options)))
      (is (= "write-key" (.getWriteKey options)))))
  (testing "Can set global dynamic fields"
    (let [^Options options (#'honeycomb/client-options {:data-set "data-set"
                                                        :global-fields {:foo (delay 42)}
                                                        :write-key "write-key"})]
      (is (= "https://api.honeycomb.io/" (str (.getApiHost options))))
      (is (= "data-set" (.getDataset options)))
      (is (nil? (.getEventPostProcessor options)))
      (is (= {} (.getGlobalFields options)))
      (let [dynamic-fields (.getGlobalDynamicFields options)]
        (is (= 1 (count dynamic-fields)))
        (is (get dynamic-fields "foo"))
        (is (instance? ValueSupplier (get dynamic-fields "foo")))
        (is (= 42 (.supply ^ValueSupplier (get dynamic-fields "foo")))))
      (is (= 1 (.getSampleRate options)))
      (is (= "write-key" (.getWriteKey options)))))
  (testing "Can set sample rate"
    (let [^Options options (#'honeycomb/client-options {:data-set "data-set"
                                                        :sample-rate 42
                                                        :write-key "write-key"})]
      (is (= "https://api.honeycomb.io/" (str (.getApiHost options))))
      (is (= "data-set" (.getDataset options)))
      (is (nil? (.getEventPostProcessor options)))
      (is (= {} (.getGlobalFields options)))
      (is (= {} (.getGlobalDynamicFields options)))
      (is (= 42 (.getSampleRate options)))
      (is (= "write-key" (.getWriteKey options))))))

(deftest transport-options-random-generations
  (doseq [to (gen/sample (s/gen :clj-honeycomb.core/transport-options))]
    (is (instance? TransportOptions (#'honeycomb/transport-options to)))))

(deftest transport-options-works
  ; By hardcoding these transport options here, this test will also detect
  ; changes in the underlying libhoney-java library.
  (let [default-transport-options {:batchSize 50
                                   :batchTimeoutMillis 100
                                   :bufferSize 8192
                                   :connectTimeout 0
                                   :connectionRequestTimeout 0
                                   :ioThreadCount 4
                                   :maxConnections 200
                                   :maxHttpConnectionsPerApiHost 100
                                   :maxPendingBatchRequests 250
                                   :maximumHttpRequestShutdownWait 2000
                                   :queueCapacity 10000
                                   :socketTimeout 3000}
        checks-out (fn [expected input]
                     (let [^TransportOptions to (#'honeycomb/transport-options input)]
                       (and (instance? TransportOptions to)
                            (integer? (.getIoThreadCount to))
                            (pos? (.getIoThreadCount to))
                            (= (-> to bean (dissoc :class :ioThreadCount))
                               (dissoc expected :ioThreadCount)))))]
    (are [input expected]
         (checks-out expected input)

      {}
      default-transport-options

      {:batch-size 100}
      (assoc default-transport-options :batchSize 100)

      {:batch-timeout-millis 200}
      (assoc default-transport-options :batchTimeoutMillis 200)

      {:buffer-size 1024}
      (assoc default-transport-options :bufferSize 1024)

      {:connection-request-timeout 1}
      (assoc default-transport-options :connectionRequestTimeout 1)

      {:connect-timeout 1}
      (assoc default-transport-options :connectTimeout 1)

      {:io-thread-count 1}
      (assoc default-transport-options :ioThreadCount 1)

      {:max-connections 100}
      (assoc default-transport-options :maxConnections 100)

      {:max-connections-per-api-host 150}
      (assoc default-transport-options :maxHttpConnectionsPerApiHost 150)

      {:maximum-http-request-shutdown-wait 1000}
      (assoc default-transport-options :maximumHttpRequestShutdownWait 1000)

      {:maximum-pending-batch-requests 125}
      (assoc default-transport-options :maxPendingBatchRequests 125)

      {:queue-capacity 1000}
      (assoc default-transport-options :queueCapacity 1000)

      {:socket-timeout 1000}
      (assoc default-transport-options :socketTimeout 1000))))

(deftest response-observer-works
  (let [client-rejected (reify ClientRejected)
        server-accepted (reify ServerAccepted)
        server-rejected (reify ServerRejected)
        unknown (reify Unknown)]
    (testing "It still works OK if there are no actual handlers"
      (let [^ResponseObserver ro (#'honeycomb/response-observer {})]
        (.onClientRejected ro client-rejected)
        (.onServerAccepted ro server-accepted)
        (.onServerRejected ro server-rejected)
        (.onUnknown ro unknown)))
    (testing "Each function works as expected"
      (let [^ResponseObserver ro (#'honeycomb/response-observer
                                  {:on-client-rejected (fn [cr]
                                                         (is (= cr client-rejected)))
                                   :on-server-accepted (fn [sa]
                                                         (is (= sa server-accepted)))
                                   :on-server-rejected (fn [sr]
                                                         (is (= sr server-rejected)))
                                   :on-unknown (fn [u]
                                                 (is (= u unknown)))})]
        (.onClientRejected ro client-rejected)
        (.onServerAccepted ro server-accepted)
        (.onServerRejected ro server-rejected)
        (.onUnknown ro unknown)))
    (testing "Randomly generated values work"
      (check `honeycomb/response-observer))))

(deftest client-works
  (testing "Without a ResponseObserver"
    (with-open [client (honeycomb/client {:data-set "data-set"
                                          :write-key "write-key"})]
      (is (instance? HoneyClient client))))
  (testing "With a ResponseObserver"
    (with-open [client (honeycomb/client {:data-set "data-set"
                                          :response-observer {:on-unknown (fn [_] nil)}
                                          :write-key "write-key"})]
      (is (instance? HoneyClient client))))
  (testing "With empty transport options"
    (with-open [client (honeycomb/client {:data-set "data-set"
                                          :transport-options {}
                                          :write-key "write-key"})]
      (is (instance? HoneyClient client))))
  (testing "With non-empty transport options"
    (with-open [client (honeycomb/client {:data-set "data-set"
                                          :transport-options {:batch-size 10}
                                          :write-key "write-key"})]
      (is (instance? HoneyClient client))))
  (testing "We can add an event pre-processor"
    (with-open [client (honeycomb/client {:data-set "data-set"
                                          :event-pre-processor (fn [event-data options]
                                                                 [event-data options])
                                          :write-key "write-key"})]
      (is (instance? HoneyClient client)))))

(deftest init-and-initialized?-works
  (testing "Initialize with a map"
    (is (nil? @#'honeycomb/*client*))
    (is (not (honeycomb/initialized?)))
    (when (nil? @#'honeycomb/*client*)
      (try
        (let [options {:data-set "data-set"
                       :write-key "write-key"}]
          (with-open [client (honeycomb/client options)]
            (is (not (honeycomb/initialized?)))
            (is (= (bean client) (bean (honeycomb/init options))))
            (is (= (bean client) (bean @#'honeycomb/*client*)))
            (is (honeycomb/initialized?))))
        (finally
          (alter-var-root #'honeycomb/*client* (constantly nil)))))
    (is (not (honeycomb/initialized?)))
    (is (nil? @#'honeycomb/*client*)))
  (testing "Initialize with a client"
    (is (nil? @#'honeycomb/*client*))
    (is (not (honeycomb/initialized?)))
    (when (nil? @#'honeycomb/*client*)
      (try
        (let [options {:data-set "data-set"
                       :write-key "write-key"}]
          (with-open [client (honeycomb/client options)]
            (is (not (honeycomb/initialized?)))
            (is (= client (honeycomb/init client)))
            (is (= client @#'honeycomb/*client*))
            (is (honeycomb/initialized?))))
        (finally
          (alter-var-root #'honeycomb/*client* (constantly nil)))))
    (is (not (honeycomb/initialized?)))
    (is (nil? @#'honeycomb/*client*)))
  (testing "The argument must be a client or a map"
    (with-instrument-disabled
      (is (thrown? IllegalArgumentException (honeycomb/init nil))))))

(deftest create-event-works
  (with-open [honeycomb-client (honeycomb/client {:data-set "data-set"
                                                  :write-key "write-key"})]
    (testing "Empty event"
      (let [^Event event (#'honeycomb/create-event honeycomb-client {} {})]
        (is (= "https://api.honeycomb.io/" (str (.getApiHost event))))
        (is (= "data-set" (.getDataset event)))
        (is (= {} (event->fields event)))
        (is (= {} (.getMetadata event)))
        (is (= 1 (.getSampleRate event)))
        (is (nil? (.getTimestamp event)))
        (is (= "write-key" (.getWriteKey event)))))
    (testing "Fields get realized properly and infinite/lazy things don't block."
      (let [^Event event (#'honeycomb/create-event honeycomb-client
                                                   {:nil nil
                                                    :string "string"
                                                    :integer 42
                                                    :float Math/E
                                                    :fraction 22/7
                                                    :atom (atom 3)
                                                    :delay (delay 4)}
                                                   {})]
        (is (= "https://api.honeycomb.io/" (str (.getApiHost event))))
        (is (= "data-set" (.getDataset event)))
        (is (= {"nil" nil
                "string" "string"
                "integer" 42
                "float" Math/E
                "fraction" (float 22/7)
                "atom" 3
                "delay" 4}
               (event->fields event)))
        (is (= {} (.getMetadata event)))
        (is (= 1 (.getSampleRate event)))
        (is (nil? (.getTimestamp event)))
        (is (= "write-key" (.getWriteKey event)))))
    (testing "Options get set on the event"
      (let [^Event event (#'honeycomb/create-event honeycomb-client
                                                   {}
                                                   {:api-host "https://localhost:123/"
                                                    :data-set "foo"
                                                    :metadata {:event-id 42}
                                                    :sample-rate 3
                                                    :timestamp 123456
                                                    :write-key "bar"})]
        (is (= "https://localhost:123/" (str (.getApiHost event))))
        (is (= "foo" (.getDataset event)))
        (is (= {} (event->fields event)))
        (is (= {:event-id 42} (.getMetadata event)))
        (is (= 3 (.getSampleRate event)))
        (is (= 123456 (.getTimestamp event)))
        (is (= "bar" (.getWriteKey event)))))
    (testing "Event pre-processor runs"
      (with-open [client (honeycomb/client {:data-set "data-set"
                                            :event-pre-processor (fn [event-data options]
                                                                   [(assoc event-data :integer 1) options])
                                            :write-key "write-key"})]
        (let [^Event event (#'honeycomb/create-event client
                                                     {:nil nil
                                                      :string "string"
                                                      :integer 42
                                                      :float Math/E
                                                      :fraction 22/7
                                                      :atom (atom 3)
                                                      :delay (delay 4)}
                                                     {})]
          (is (= "https://api.honeycomb.io/" (str (.getApiHost event))))
          (is (= "data-set" (.getDataset event)))
          (is (= {"nil" nil
                  "string" "string"
                  "integer" 1
                  "float" Math/E
                  "fraction" (float 22/7)
                  "atom" 3
                  "delay" 4}
                 (event->fields event)))
          (is (= {} (.getMetadata event)))
          (is (= 1 (.getSampleRate event)))
          (is (nil? (.getTimestamp event)))
          (is (= "write-key" (.getWriteKey event))))))))

(deftest send-works
  (testing "One argument"
    (testing "requires a client from init first"
      (binding [honeycomb/*client* nil]
        (is (thrown? IllegalStateException (honeycomb/send {:foo "bar"})))))
    (testing "works when there's a global client set"
      (validate-events
       (fn []
         (honeycomb/send {:foo "bar"}))
       (fn [events errors]
         (is (empty? errors))
         (is (= 1 (count events)))
         (is (= {"foo" "bar"} (event->fields (first events))))))))
  (testing "Two arguments works"
    (let [events (atom [])]
      (with-open [client (recording-client events {:data-set "data-set"
                                                   :write-key "write-key"})]
        (honeycomb/send client {:foo "bar"}))
      (is (= 1 (count @events)))
      (is (= {"foo" "bar"} (event->fields (first @events))))))
  (testing "Three arguments works"
    (let [events (atom [])]
      (with-open [client (recording-client events {:data-set "data-set"
                                                   :write-key "write-key"})]
        (honeycomb/send client {:foo "bar"} {:pre-sampled true}))
      (is (= 1 (count @events)))
      (is (= {"foo" "bar"} (event->fields (first @events))))))
  (testing "Global fields get sent"
    (let [dynamic-field (atom 1)
          events (atom [])]
      (with-open [client (recording-client events {:data-set "data-set"
                                                   :global-fields
                                                   {:dynamic dynamic-field
                                                    :static "static"}
                                                   :write-key "write-key"})]
        (honeycomb/send client {:foo "bar"})
        (swap! dynamic-field inc)
        (honeycomb/send client {:foo "bar"}))
      (is (= [{"dynamic" 1
               "foo" "bar"
               "static" "static"}
              {"dynamic" 2
               "foo" "bar"
               "static" "static"}]
             (map event->fields @events)))))
  (testing "Randomly generated send options"
    (doseq [send-options (gen/sample (s/gen :clj-honeycomb.core/send-options))]
      (validate-events
       (fn []
         (honeycomb/send {:foo "bar"} send-options))
       (fn [events errors]
         (if (empty? errors)
           (do
             (is (= 1 (count events)))
             (is (= {"foo" "bar"} (event->fields (first events)))))
           (do
             (is (= 1 (count errors)))
             (is (instance? ClientRejected (first errors)))
             (is  (= "NOT_SAMPLED" (str (.getReason ^ClientRejected (first errors)))))))))))
  (testing "Random everything"
    (doseq [client-options (gen/sample (s/gen :clj-honeycomb.core/client-options))]
      (doseq [send-options (gen/sample (s/gen :clj-honeycomb.core/send-options))]
        (let [events (atom [])]
          (with-open [client (recording-client events client-options)]
            (honeycomb/send client {:foo "bar"} send-options))
          (cond (= 0 (count @events))
                (is (or (< 1 (or (:sample-rate client-options) 1))
                        (< 1 (or (:sample-rate send-options) 1))))

                (= 1 (count @events))
                (is (contains? (event->fields (first @events)) "foo"))

                :else
                (is (empty? @events))))))))

(defn- capture-honeycomb-http-calls
  [data-set expected-calls timeout-ms f]
  (with-open [^NanoFakeServer http-server (stub-http/start! {(str "/1/batch/" data-set)
                                                             {:status 200
                                                              :body ""}})]
    (f (:uri http-server))
    (let [deadline (+ (System/nanoTime) (* timeout-ms 1e6))]
      (loop []
        (let [recordings (->> http-server
                              :routes
                              deref
                              (mapcat :recordings)
                              (map #(update-in % [:request :body "postData"] json/read-str)))]
          (cond (= expected-calls (count recordings))
                recordings

                (< expected-calls (count recordings))
                (do
                  (is (= expected-calls (count recordings)))
                  recordings)

                (> expected-calls (count recordings))
                (if (< deadline (System/nanoTime))
                  (throw (ex-info "Deadline exceeded when waiting for replies" {}))
                  (do
                    (Thread/sleep 100)
                    (recur)))))))))

(deftest send-makes-the-expected-http-calls
  (let [data-set "data-set"
        write-key "write-key"
        prune-nils (fn pn [m]
                     (if (map? m)
                       (->> m
                            (remove (comp nil? val))
                            (map (fn [[k v]]
                                   [k (pn v)]))
                            (into {}))
                       m))]
    (-> (capture-honeycomb-http-calls
         data-set
         1
         10000
         (fn [uri]
           (with-open [client (honeycomb/client {:api-host uri
                                                 :data-set data-set
                                                 :write-key write-key})]
             (honeycomb/send client (make-kitchen-sink)))))
        ((fn [requests]
           (let [request (:request (first requests))]
             (is (= "POST" (:method request)))
             (is (= (str "/1/batch/" data-set) (:path request)))
             (is (= write-key (-> request :headers :x-honeycomb-team)))
             (is (= 1 (count (get-in request [:body "postData"]))))
             (let [body (first (get-in request [:body "postData"]))]
               (is (= 1 (get body "samplerate")))
               (let [data (atom (get body "data"))
                     expected (atom (prune-nils kitchen-sink-realized))]
                 ; Make sure they have the same keys
                 (is (= (sort (keys @expected))
                        (sort (keys @data))))

                 ; Check each key in turn so that the errors are more readable
                 (doseq [[k v] @expected]
                   (if (instance? Throwable v)
                     (do
                       (is (= "An exception" (get-in @data [(name k) "message"])))
                       (is (get-in @data [(name k) "stackTrace"])))
                     (is (= v (get @data (name k))))))))))))))

(deftest send-pre-sampled-makes-the-expected-http-calls
  (let [data-set "data-set"
        write-key "write-key"]
    (-> (capture-honeycomb-http-calls
         data-set
         1
         10000
         (fn [uri]
           (with-open [client (honeycomb/client {:api-host uri
                                                 :data-set data-set
                                                 :write-key write-key})]
             (honeycomb/send client {:foo "bar"} {:pre-sampled true}))))
        ((fn [requests]
           (let [request (:request (first requests))]
             (is (= "POST" (:method request)))
             (is (= (str "/1/batch/" data-set) (:path request)))
             (is (= write-key (-> request :headers :x-honeycomb-team)))
             (is (= 1 (count (get-in request [:body "postData"]))))
             (let [body (first (get-in request [:body "postData"]))]
               (is (= 1 (get body "samplerate")))
               (is (= {"foo" "bar"} (get body "data"))))))))))

(deftest with-event-works
  (testing "Code that doesn't throw still returns AND sends the event"
    (validate-events
     (fn []
       (is (= :foo (honeycomb/with-event {:foo "foo"} {}
                     (honeycomb/add-to-event {:bar "bar"})
                     (Thread/sleep 100)
                     (honeycomb/add-to-event :baz "baz")
                     :foo))))
     (fn [events errors]
       (is (empty? errors))
       (is (= 1 (count events)))
       (let [event-data (some->> events first event->fields)]
         (is (= {"foo" "foo"
                 "bar" "bar"
                 "baz" "baz"}
                (dissoc event-data "elapsed-ms")))
         (is (< 100 (get event-data "elapsed-ms" -1)))))))
  (testing "Code that throws both throws AND sends the event"
    (validate-events
     (fn []
       (is (thrown? Exception
                    (honeycomb/with-event {:foo "foo"} {}
                      (honeycomb/add-to-event {:bar "bar"})
                      (honeycomb/add-to-event :baz "baz")
                      (throw (Exception. "An exception"))))))
     (fn [events errors]
       (is (empty? errors))
       (is (= 1 (count events)))
       (let [event-data (some->> events first event->fields)]
         (is (= {"foo" "foo"
                 "bar" "bar"
                 "baz" "baz"}
                (dissoc event-data "exception" "elapsed-ms")))
         (is (some-> (get event-data "elapsed-ms") pos?))
         (is (instance? Exception (get event-data "exception"))))))))

(deftest live-test-against-honeycomb
  ; This has no assertions. It's here purely so that we can generate some known
  ; events and inspect them in the Honeycomb.io UI to detect unexpected
  ; serialisation issues.
  (let [data-set (System/getenv "HONEYCOMB_DATA_SET")
        write-key (System/getenv "HONEYCOMB_WRITE_KEY")
        kitchen-sink (make-kitchen-sink)]
    (when (and data-set write-key)
      (testing "The primitives work"
        (with-open [client (honeycomb/client {:data-set data-set
                                              :write-key write-key})]
          (honeycomb/send client kitchen-sink)))
      (testing "Using init and with-event works"
        (let [original-client @#'honeycomb/*client*]
          (try
            (honeycomb/init {:data-set data-set
                             :write-key write-key})
            (honeycomb/with-event {:foo "foo"} {}
              (honeycomb/add-to-event {:bar "bar"})
              (honeycomb/add-to-event :baz "baz"))
            (finally
              (alter-var-root #'honeycomb/*client* (constantly original-client)))))))))

(deftest nonsense-tests-just-to-cover-spec
  ; We need to call explain-data on these to ensure that the macroexpanded form
  ; generated by s/keys is covered properly.
  (s/explain-data :clj-honeycomb.core/create-event-options {:foo {:bar :baz}})
  (s/explain-data :clj-honeycomb.core/response-observer {:foo {:bar :baz}})
  (s/explain-data :clj-honeycomb.core/send-options {:foo {:bar :baz}})
  (s/explain-data :clj-honeycomb.core/transport-options {:foo {:bar :baz}}))
