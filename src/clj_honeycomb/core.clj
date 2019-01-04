(ns clj-honeycomb.core
  "A thin wrapper over libhoney-java. Use this to send events to Honeycomb.

   Require this namespace as honeycomb and then use it like this:

   ; Initialise this library
   (honeycomb/init {:data-set \"Your Honeycomb dataset\"
                    :write-key \"Your Honeycomb API key\"})

   ; Send an event
   (honeycomb/with-event {:initial :data} {}
     ... do your stuff ...
     (honeycomb/add-to-event :more \"event data\")
     ... do more of your stuff ...)"
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
(s/def ::send-options
  (s/keys :opt-un [::api-host
                   ::data-set
                   ::metadata
                   ::pre-sampled
                   ::sample-rate
                   ::timestamp
                   ::write-key]))

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
  "Construct a HoneyClient from a map of options. It is your responsibility to
   call .close on this object when you're finished with it. Failure to do so may
   result in a loss of events. It is recommended that you create this client
   using with-open or with a state management system like component or mount.

   Valid options are:

   :api-host             The base of the URL for all API calls to Honeycomb.
                         (String. Optional. Default: \"https://api.honeycomb.io/\")
   :data-set             The name of your Honeycomb dataset. (String. Required.)
   :event-post-processor An instance of the libhoney-java class
                         io.honeycomb.libhoney.EventPostProcessor which can be
                         used to post-process event data after sampling but before
                         sending the event to Honeycomb. (EventPostProcessor.
                         Optional.)
   :global-fields        A map of fields to include in every event. These fields
                         can have dynamic values either by using an
                         atom/delay/promise as its value or by supplying a
                         ValueSupplier object as the value. Fields added to a
                         given event will override these fields. (Map. Optional.)
   :response-observer    A map of functions which will be called during the
                         lifecycle of sending each event. (Map. Optional.)

                         Keys in this map are:

                         :on-client-rejected Called when the libhoney-java code
                                             fails to send an event to
                                             Honeycomb.
                         :on-server-accepted Called when the Honeycomb server
                                             has accepted the event. Be very
                                             wary of supplying a function for
                                             this, since it can be called a lot.
                         :on-server-rejected Called when the Honeycomb server
                                             rejects an event.
                         :on-unknown         Called for all other errors in the
                                             lifecycle of sending an event.
   :sample-rate          The global sample rate. This can be overridden on a
                         per-event basis. (Integer. Optional. Default: 1)
   :write-key            Your Honeycomb API key. (String. Required.)"
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
  "Initialize this library by creating an internal, implicit client with the
   supplied set of options.

   Valid options are:

   :api-host             The base of the URL for all API calls to Honeycomb.
                         (String. Optional. Default: \"https://api.honeycomb.io/\")
   :data-set             The name of your Honeycomb dataset. (String. Required.)
   :event-post-processor An instance of the libhoney-java class
                         io.honeycomb.libhoney.EventPostProcessor which can be
                         used to post-process event data after sampling but before
                         sending the event to Honeycomb. (EventPostProcessor.
                         Optional.)
   :global-fields        A map of fields to include in every event. These fields
                         can have dynamic values either by using an
                         atom/delay/promise as its value or by supplying a
                         ValueSupplier object as the value. Fields added to a
                         given event will override these fields. (Map. Optional.)
   :response-observer    A map of functions which will be called during the
                         lifecycle of sending each event. (Map. Optional.)

                         Keys in this map are:

                         :on-client-rejected Called when the libhoney-java code
                                             fails to send an event to
                                             Honeycomb.
                         :on-server-accepted Called when the Honeycomb server
                                             has accepted the event. Be very
                                             wary of supplying a function for
                                             this, since it can be called a lot.
                         :on-server-rejected Called when the Honeycomb server
                                             rejects an event.
                         :on-unknown         Called for all other errors in the
                                             lifecycle of sending an event.
   :sample-rate          The global sample rate. This can be overridden on a
                         per-event basis. (Integer. Optional. Default: 1)
   :write-key            Your Honeycomb API key. (String. Required.)"
  [options]
  (let [c (client options)]
    (.closeOnShutdown c)
    (alter-var-root #'*client* (constantly c))
    c))

(s/fdef initialized?
  :args (s/cat)
  :ret boolean?)

(defn initialized?
  "Report whether or not init has set up a client yet."
  []
  (some? *client*))

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
  :args (s/alt :implicit-no-options (s/cat :event-data map?)
               :implicit-with-options (s/cat :event-data map?
                                             :options ::send-options)
               :explicit-no-options (s/cat :honeycomb-client (partial instance? HoneyClient)
                                           :event-data map?)
               :explicit-with-options (s/cat :honeycomb-client (partial instance? HoneyClient)
                                             :event-data map?
                                             :options ::send-options)))

(def ^{:arglists '([event-data]
                   [event-data options]
                   [honeycomb-client event-data]
                   [honeycomb-client event-data options])}
  send
  "Send an event to Honeycomb.io.

   event-data       The fields to send for this event. These will be merged on
                    top of the global fields configured in the HoneyClient.
   honeycomb-client An optional HoneyClient instance to use as the API client.
                    This is required if you have not already called init.
   options          A map of event-specific options. Valid options are:
                    :api-host     Override the :api-host set in the client.
                                  (String. Optional.)
                    :data-set     Override the :data-set set in the client.
                                  (String. Optional.)
                    :metadata     A map of metadata which you can set on each
                                  event. This will not be sent to Honeycomb but
                                  will be returned in every call to a function
                                  in a ResponseObserver. This could allow you to
                                  match a response to an originating event.
                                  (Map. Optional.)
                    :pre-sampled  Set this to true if you've already sampled the
                                  data. Otherwise libhoney-java will sample it
                                  for you. If you set this, you should ensure
                                  that sample-rate is also supplied with a value
                                  that reflects the sampling you did. (Boolean.
                                  Optional. Default: false)
                    :sample-rate  The sample rate for this event. If not
                                  supplied, the sample rate configured for the
                                  client library will be used. (Integer.
                                  Optional.)
                    :timestamp    Set an explicit timestamp for this event,
                                  measured in milliseconds since the epoch.
                                  (Integer. Optional. Default:
                                  (System/currentTimeMillis))
                    :write-key    Override the :write-key set in the client.
                                  (String. Optional.)"
  (fn [& args]
    (let [[honeycomb-client event-data {:keys [pre-sampled] :as options}]
          (if (instance? HoneyClient (first args))
            args
            (concat [*client*] args))]
      (when (nil? honeycomb-client)
        (throw (IllegalStateException. "Either call init or pass a valid HoneyClient as the first argument.")))
      (let [event (create-event honeycomb-client event-data (or options {}))]
        (if pre-sampled
          (.sendPresampled event)
          (.send event))))))

(def ^:private ^:dynamic *event-data* (atom {}))

(defn add-to-event
  "From within a with-event form, add further fields to the event which will
   be sent at the end of the with-event.

   This can be called either with a single map to be (shallow) merged onto the
   event's current data or with a key and value which will be associated onto
   the event map."
  ([m]
   (swap! *event-data* merge m))
  ([k v]
   (swap! *event-data* assoc k v)))

(defn- with-event-fn
  "A function implementing with-event. See with-event for documentation."
  [event-data options f]
  (binding [*event-data* (atom event-data)]
    (let [start (System/nanoTime)]
      (try
        (f)
        (catch Throwable t
          (add-to-event :exception t)
          (throw t))
        (finally
          (add-to-event :elapsed-ms (/ (- (System/nanoTime) start) 1e6))
          (send *client* @*event-data* options))))))

(defmacro with-event
  "Wrap some code and send an event when the code is done.

   event-data       The fields to send for this event. These will be merged on
                    top of the global fields configured in the HoneyClient. The
                    following additional keys will be added to the event data
                    just before sending:
                    :elapsed-ms   The number of milliseconds it took to execute
                                  the body of this macro.
                    :exception    If an exception was thrown, it will be added
                                  to this field before being rethrown.
   options          A map of event-specific options. Valid options are:
                    :api-host     Override the :api-host set in the client.
                                  (String. Optional.)
                    :data-set     Override the :data-set set in the client.
                                  (String. Optional.)
                    :metadata     A map of metadata which you can set on each
                                  event. This will not be sent to Honeycomb but
                                  will be returned in every call to a function
                                  in a ResponseObserver. This could allow you to
                                  match a response to an originating event.
                                  (Map. Optional.)
                    :pre-sampled  Set this to true if you've already sampled the
                                  data. Otherwise libhoney-java will sample it
                                  for you. If you set this, you should ensure
                                  that sample-rate is also supplied with a value
                                  that reflects the sampling you did. (Boolean.
                                  Optional. Default: false)
                    :sample-rate  The sample rate for this event. If not
                                  supplied, the sample rate configured for the
                                  client library will be used. (Integer.
                                  Optional.)
                    :timestamp    Set an explicit timestamp for this event,
                                  measured in milliseconds since the epoch.
                                  (Integer. Optional. Default:
                                  (System/currentTimeMillis))
                    :write-key    Override the :write-key set in the client.
                                  (String. Optional.)"
  [event-data options & body]
  `(#'with-event-fn ~event-data ~options (fn [] ~@body)))
