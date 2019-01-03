(ns clj-honeycomb.core-test
  (:require [clj-honeycomb.global-fixtures :refer (kitchen-sink-realized make-kitchen-sink)]

            [clojure.data.json :as json]
            [clojure.test :refer (deftest is testing)]

            [stub-http.core :as stub-http]

            [clj-honeycomb.core :as honeycomb]
            [clj-honeycomb.testing-utils :refer (recording-client validate-events)])
  (:import (clojure.lang ExceptionInfo)
           (io.honeycomb.libhoney EventPostProcessor
                                  HoneyClient
                                  Options
                                  ValueSupplier)
           (io.honeycomb.libhoney.responses ClientRejected
                                            ServerAccepted
                                            ServerRejected
                                            Unknown)))

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

(deftest response-observer-works
  (let [client-rejected (reify ClientRejected)
        server-accepted (reify ServerAccepted)
        server-rejected (reify ServerRejected)
        unknown (reify Unknown)]
    (testing "It still works OK if there are no actual handlers"
      (let [ro (#'honeycomb/response-observer {})]
        (.onClientRejected ro client-rejected)
        (.onServerAccepted ro server-accepted)
        (.onServerRejected ro server-rejected)
        (.onUnknown ro unknown)))
    (testing "Each function works as expected"
      (let [ro (#'honeycomb/response-observer
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
        (.onUnknown ro unknown)))))

(deftest client-works
  (testing "Without a ResponseObserver"
    (with-open [client (honeycomb/client {:data-set "data-set"
                                          :write-key "write-key"})]
      (is (instance? HoneyClient client))))
  (testing "With a ResponseObserver"
    (with-open [client (honeycomb/client {:data-set "data-set"
                                          :response-observer {:on-unknown (fn [_] nil)}
                                          :write-key "write-key"})]
      (is (instance? HoneyClient client)))))

(deftest init-and-initialized?-works
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

(deftest create-event-works
  (with-open [honeycomb-client (honeycomb/client {:data-set "data-set"
                                                  :write-key "write-key"})]
    (testing "Empty event"
      (let [event (#'honeycomb/create-event honeycomb-client {} {})]
        (is (= "https://api.honeycomb.io/" (str (.getApiHost event))))
        (is (= "data-set" (.getDataset event)))
        (is (= {} (.getFields event)))
        (is (= {} (.getMetadata event)))
        (is (= 1 (.getSampleRate event)))
        (is (nil? (.getTimestamp event)))
        (is (= "write-key" (.getWriteKey event)))))
    (testing "Fields get realized properly and infinite/lazy things don't block."
      (let [event (#'honeycomb/create-event honeycomb-client
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
               (.getFields event)))
        (is (= {} (.getMetadata event)))
        (is (= 1 (.getSampleRate event)))
        (is (nil? (.getTimestamp event)))
        (is (= "write-key" (.getWriteKey event))))
      (testing "Options get set on the event"
        (let [event (#'honeycomb/create-event honeycomb-client
                                              {}
                                              {:api-host "https://localhost:123/"
                                               :data-set "foo"
                                               :metadata {:event-id 42}
                                               :sample-rate 3
                                               :timestamp 123456
                                               :write-key "bar"})]
          (is (= "https://localhost:123/" (str (.getApiHost event))))
          (is (= "foo" (.getDataset event)))
          (is (= {} (.getFields event)))
          (is (= {:event-id 42} (.getMetadata event)))
          (is (= 3 (.getSampleRate event)))
          (is (= 123456 (.getTimestamp event)))
          (is (= "bar" (.getWriteKey event))))))))

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
         (is (= {"foo" "bar"} (.getFields (first events))))))))
  (testing "Two arguments works"
    (let [events (atom [])]
      (with-open [client (recording-client events {})]
        (honeycomb/send client {:foo "bar"}))
      (is (= 1 (count @events)))
      (is (= {"foo" "bar"} (.getFields (first @events))))))
  (testing "Three arguments works"
    (let [events (atom [])]
      (with-open [client (recording-client events {})]
        (honeycomb/send client {:foo "bar"} {:pre-sampled true}))
      (is (= 1 (count @events)))
      (is (= {"foo" "bar"} (.getFields (first @events))))))
  (testing "Global fields get sent"
    (let [dynamic-field (atom 1)
          events (atom [])]
      (with-open [client (recording-client events {:global-fields
                                                   {:dynamic dynamic-field
                                                    :static "static"}})]
        (honeycomb/send client {:foo "bar"})
        (swap! dynamic-field inc)
        (honeycomb/send client {:foo "bar"}))
      (is (= [{"dynamic" 1
               "foo" "bar"
               "static" "static"}
              {"dynamic" 2
               "foo" "bar"
               "static" "static"}]
             (map #(into {} (.getFields %)) @events))))))

(defn- capture-honeycomb-http-calls
  [data-set expected-calls timeout-ms f]
  (with-open [http-server (stub-http/start! {(str "/1/batch/" data-set)
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
       (let [event-data (some->> events first (.getFields) (into {}))]
         (is (= {"foo" "foo"
                 "bar" "bar"
                 "baz" "baz"}
                (dissoc event-data "elapsed-ms")))
         (is (< 100 (get event-data "elapsed-ms" -1) 120))))))
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
       (let [event-data (some->> events first (.getFields) (into {}))]
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
