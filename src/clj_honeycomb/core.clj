(ns clj-honeycomb.core
  "A thin wrapper over libhoney-java."
  (:refer-clojure :exclude [send])
  (:use clojure.future)
  (:require [clojure.spec.alpha :as s]

            [clj-honeycomb.fields :as fields])
  (:import (java.net URI)
           (io.honeycomb.libhoney EventPostProcessor
                                  HoneyClient
                                  LibHoney
                                  Options
                                  ResponseObserver)))

(s/def ::api-host string?)
(s/def ::data-set string?)
(s/def ::event-post-processor (partial instance? EventPostProcessor))
(s/def ::global-fields map?)
(s/def ::metadata map?)
(s/def ::on-client-rejected fn?)
(s/def ::on-server-accepted fn?)
(s/def ::on-server-rejected fn?)
(s/def ::on-unknown fn?)
(s/def ::pre-sampled boolean?)
(s/def ::sample-rate int?)
(s/def ::timestamp int?)
(s/def ::write-key string?)
(s/def ::response-observer
  (s/keys :opt-un [::on-client-rejected
                   ::on-server-accepted
                   ::on-server-rejected
                   ::on-unknown]))
(s/def ::client-options
  (s/keys :req-un [::data-set
                   ::write-key]
          :opt-un [::api-host
                   ::event-post-processor
                   ::global-fields
                   ::response-observer
                   ::sample-rate]))

(s/fdef client-options
  :args (s/cat :options ::client-options)
  :ret (partial instance? Options))

(defn- client-options
  "Turn a map into an Options object to initialize the LibHoney client."
  [{:keys [api-host
           data-set
           event-post-processor
           global-fields
           sample-rate
           write-key]}]
  (let [[static-fields dynamic-fields] (fields/separate global-fields)]
    (cond-> (LibHoney/options)
      api-host (.setApiHost (URI. api-host))
      data-set (.setDataset data-set)
      event-post-processor (.setEventPostProcessor event-post-processor)
      (not-empty static-fields) (.setGlobalFields static-fields)
      (not-empty dynamic-fields) (.setGlobalDynamicFields dynamic-fields)
      sample-rate (.setSampleRate sample-rate)
      write-key (.setWriteKey write-key)
      true (.build))))

(s/fdef response-observer
  :args (s/cat :response-observer ::response-observer)
  :ret (partial instance? ResponseObserver))

(defn- response-observer
  "Take the map of functions passed in the :response-observer client option and
   turn it into a ResponseObserver to attach to the HoneyClient."
  [{:keys [on-client-rejected
           on-server-accepted
           on-server-rejected
           on-unknown]}]
  (reify ResponseObserver
    (onClientRejected [_this client-rejected]
      (when on-client-rejected
        (on-client-rejected client-rejected)))
    (onServerAccepted [_this server-accepted]
      (when on-server-accepted
        (on-server-accepted server-accepted)))
    (onServerRejected [_this server-rejected]
      (when on-server-rejected
        (on-server-rejected server-rejected)))
    (onUnknown [_this unknown]
      (when on-unknown
        (on-unknown unknown)))))

(s/fdef client
  :args (s/cat :options ::client-options)
  :ret (partial instance? HoneyClient))

(defn client
  "Construct a HoneyClient from a map of options."
  [options]
  (let [client (LibHoney/create (client-options options))]
    (when-let [ro (:response-observer options)]
      (.addResponseObserver client (response-observer ro)))
    client))

(def ^:private ^:dynamic *client* nil)

(s/fdef init
  :args (s/cat :options ::client-options)
  :ret (partial instance? HoneyClient))

(defn init
  "Initialise this library by creating a client with the supplied set of
   options."
  [options]
  (let [c (client options)]
    (.closeOnShutdown c)
    (alter-var-root #'*client* (constantly c))
    c))

(s/fdef create-event
  :args (s/cat :honeycomb-client (partial instance? HoneyClient)
               :event-data map?
               :options (s/keys :opt-un [::api-host
                                         ::data-set
                                         ::metadata
                                         ::sample-rate
                                         ::timestamp
                                         ::write-key])))

(defn- create-event
  "Create a Honeycomb event object."
  [honeycomb-client event-data {:keys [api-host
                                       data-set
                                       metadata
                                       sample-rate
                                       timestamp
                                       write-key]}]
  (cond-> (.createEvent honeycomb-client)
    api-host (.setApiHost (URI. api-host))
    data-set (.setDataset data-set)
    (not-empty event-data) (.addFields (fields/realize event-data))
    (not-empty metadata) (.addMetadata metadata)
    sample-rate (.setSampleRate sample-rate)
    timestamp (.setTimestamp timestamp)
    write-key (.setWriteKey write-key)))

(s/fdef send
  :args (s/alt :one (s/cat :event-data map?)
               :two (s/cat :honeycomb-client (partial instance? HoneyClient)
                           :event-data map?)
               :three (s/cat :honeycomb-client (partial instance? HoneyClient)
                             :event-data map?
                             :options (s/keys :opt-un [::api-host
                                                       ::data-set
                                                       ::metadata
                                                       ::pre-sampled
                                                       ::sample-rate
                                                       ::timestamp
                                                       ::write-key]))))

(defn send
  "Send an event to Honeycomb.io."
  ([event-data]
   (if (some? *client*)
     (send *client* event-data)
     (throw
      (IllegalStateException.
       "Can't use implicit client without calling init first."))))
  ([honeycomb-client event-data]
   (send honeycomb-client event-data {}))
  ([honeycomb-client event-data {:keys [pre-sampled] :as options}]
   (let [event (create-event honeycomb-client event-data options)]
     (if pre-sampled
       (.sendPresampled event)
       (.send event)))))

(def ^:dynamic *event-data* (atom {}))

(defn add-to-event
  "From within a with-event form, add further fields to the event."
  ([m]
   (swap! *event-data* merge m))
  ([k v]
   (swap! *event-data* assoc k v)))

(defmacro with-event
  "Wrap some code and send an event when the code is done.

   initial-event-data Fields to add to the event.
   event-options      Any options which you might need to pass to the third
                      argument to the send function."
  [initial-event-data event-options & body]
  `(binding [*event-data* (atom ~initial-event-data)]
     (let [start# (System/nanoTime)]
       (try
         (do ~@body)
         (catch Throwable t#
           (add-to-event :exception t#)
           (throw t#))
         (finally
           (add-to-event :elapsed-ms (/ (- (System/nanoTime) start#) 1e6))
           (send @#'*client* @*event-data* ~event-options))))))
