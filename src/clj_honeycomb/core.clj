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
  (:use [clojure.future])
  (:require [clojure.spec.alpha :as s]

            [clj-honeycomb.fields :as fields])
  (:import (java.net URI)
           (io.honeycomb.libhoney Event
                                  EventPostProcessor
                                  HoneyClient
                                  LibHoney
                                  Options
                                  ResponseObserver
                                  TransportOptions)
           (clj_honeycomb Client)))

(def ^:private pos-java-int?
  "Like pos-int? except within the range of numbers of java.lang.Integer."
  (s/int-in 1 Integer/MAX_VALUE))

(def ^:private response-observer-fn
  "A spec for functions in the ResponseObserver."
  (s/with-gen fn?
    #(s/gen #{nil
              (fn [_] nil)})))

(s/def ::additional-user-agent (s/and string? seq))
(s/def ::api-host (s/and string? seq))
(s/def ::batch-size pos-java-int?)
(s/def ::batch-timeout-millis pos-java-int?)
(s/def ::buffer-size (s/int-in 1024 Integer/MAX_VALUE))
(s/def ::connect-timeout pos-java-int?)
(s/def ::connection-request-timeout pos-java-int?)
(s/def ::data-set (s/and string? seq))
(s/def ::event-post-processor (s/with-gen (partial instance? EventPostProcessor)
                                #(s/gen #{(reify EventPostProcessor
                                            (process [_this _event-data]
                                              nil))})))
(s/def ::event-pre-processor (s/with-gen fn?
                               #(s/gen #{(fn [event-data options]
                                           [event-data options])})))
(s/def ::global-fields map?)
(s/def ::io-thread-count (s/int-in 1 (.availableProcessors (Runtime/getRuntime))))
(s/def ::max-connections pos-java-int?)
(s/def ::max-connections-per-api-host pos-java-int?)
(s/def ::maximum-http-request-shutdown-wait pos-int?)
(s/def ::maximum-pending-batch-requests pos-java-int?)
(s/def ::metadata map?)
(s/def ::on-client-rejected response-observer-fn)
(s/def ::on-server-accepted response-observer-fn)
(s/def ::on-server-rejected response-observer-fn)
(s/def ::on-unknown response-observer-fn)
(s/def ::pre-sampled boolean?)
(s/def ::queue-capacity pos-java-int?)
(s/def ::sample-rate pos-java-int?)
(s/def ::socket-timeout pos-java-int?)
(s/def ::timestamp int?)
(s/def ::write-key (s/and string? seq))
(s/def ::response-observer
  (s/keys :opt-un [::on-client-rejected
                   ::on-server-accepted
                   ::on-server-rejected
                   ::on-unknown]))
(s/def ::transport-options
  (s/keys :opt-un [::additional-user-agent
                   ::batch-size
                   ::batch-timeout-millis
                   ::buffer-size
                   ::connection-request-timeout
                   ::connect-timeout
                   ::io-thread-count
                   ::max-connections
                   ::max-connections-per-api-host
                   ::maximum-http-request-shutdown-wait
                   ::maximum-pending-batch-requests
                   ::queue-capacity
                   ::socket-timeout]))
(s/def ::client-options
  (s/keys :req-un [::data-set
                   ::write-key]
          :opt-un [::api-host
                   ::event-post-processor
                   ::event-pre-processor
                   ::global-fields
                   ::response-observer
                   ::sample-rate
                   ::transport-options]))
(s/def ::create-event-options
  (s/keys :opt-un [::api-host
                   ::data-set
                   ::metadata
                   ::sample-rate
                   ::timestamp
                   ::write-key]))
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
  (let [[static-fields dynamic-fields] (fields/separate (or global-fields {}))]
    (.build (cond-> (LibHoney/options)
              api-host (.setApiHost (URI. api-host))
              data-set (.setDataset data-set)
              event-post-processor (.setEventPostProcessor event-post-processor)
              (not-empty static-fields) (.setGlobalFields static-fields)
              (not-empty dynamic-fields) (.setGlobalDynamicFields dynamic-fields)
              sample-rate (.setSampleRate sample-rate)
              write-key (.setWriteKey write-key)))))

(s/fdef transport-options
  :args (s/cat :options ::transport-options)
  :ret (partial instance? TransportOptions))

(defn- transport-options
  "Turn a map into a TransportOptions object to initialize the LibHoney client."
  [{:keys [additional-user-agent
           batch-size
           batch-timeout-millis
           buffer-size
           connection-request-timeout
           connect-timeout
           io-thread-count
           max-connections
           max-connections-per-api-host
           maximum-http-request-shutdown-wait
           maximum-pending-batch-requests
           queue-capacity
           socket-timeout]}]
  (.build (cond-> (LibHoney/transportOptions)
            additional-user-agent (.setAdditionalUserAgent additional-user-agent)
            batch-size (.setBatchSize batch-size)
            batch-timeout-millis (.setBatchTimeoutMillis batch-timeout-millis)
            buffer-size (.setBufferSize buffer-size)
            connection-request-timeout (.setConnectionRequestTimeout connection-request-timeout)
            connect-timeout (.setConnectTimeout connect-timeout)
            io-thread-count (.setIoThreadCount io-thread-count)
            max-connections (.setMaxConnections max-connections)
            max-connections-per-api-host (.setMaxConnectionsPerApiHost max-connections-per-api-host)
            maximum-http-request-shutdown-wait (.setMaximumHttpRequestShutdownWait maximum-http-request-shutdown-wait)
            maximum-pending-batch-requests (.setMaximumPendingBatchRequests maximum-pending-batch-requests)
            queue-capacity (.setQueueCapacity queue-capacity)
            socket-timeout (.setSocketTimeout socket-timeout))))

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

(defn ^HoneyClient client
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
   :transport-options    A map of options which can be used to configure the
                         transport layer of libhoney-java. The defaults should
                         be good enough for most applications. You should read
                         and understand the documentation for
                         io.honeycomb.libhoney.TransportOptions before changing
                         any of these values. (Map. Optional.)

                         Valid keys in this map are as follows, see the
                         documentation for TransportOptions to understand their
                         exact meanings.

                         :additional-user-agent
                         :batch-size
                         :batch-timeout-millis
                         :buffer-size
                         :connection-request-timeout
                         :connect-timeout
                         :io-thread-count
                         :max-connections
                         :max-connections-per-api-host
                         :maximum-http-request-shutdown-wait
                         :maximum-pending-batch-requests
                         :queue-capacity
                         :socket-timeout
   :write-key            Your Honeycomb API key. (String. Required.)"
  [options]
  (let [^Options co (client-options options)
        ^TransportOptions to (some-> (:transport-options options) transport-options)
        client (if to
                 (Client. co to)
                 (Client. co))]
    (when-let [epp (:event-pre-processor options)]
      (.setEventPreProcessor client epp))
    (when-let [ro (:response-observer options)]
      (.addResponseObserver client (response-observer ro)))
    client))

(def ^:private ^:dynamic *client* nil)

(s/fdef init
  :args (s/alt :client (partial instance? HoneyClient)
               :options-map ::client-options)
  :ret (partial instance? HoneyClient))

(defn init
  "Initialize this library. Can be given either a HoneyClient instance to use
   for all send calls which don't specify a client or a map which will be
   passed to client to create a client.

   client-or-options  Either an instance of HoneyClient or a map to pass to
                      client to create one."
  [client-or-options]
  (let [^HoneyClient client (cond (instance? HoneyClient client-or-options)
                                  client-or-options

                                  (map? client-or-options)
                                  (client client-or-options)

                                  :else
                                  (throw
                                   (IllegalArgumentException.
                                    "client-or-options must be a HoneyClient or a map.")))]
    (.closeOnShutdown client)
    (alter-var-root #'*client* (constantly client))
    client))

(s/fdef initialized?
  :args (s/cat)
  :ret boolean?)

(defn initialized?
  "Report whether or not init has set up a client yet."
  []
  (some? *client*))

(defn- honeycomb-client->event-pre-processor
  [hc]
  (when (instance? Client hc)
    (.getEventPreProcessor ^Client hc)))

(s/fdef create-event
  :args (s/cat :honeycomb-client (partial instance? HoneyClient)
               :event-data map?
               :options ::create-event-options)
  :ret (partial instance? Event))

(defn- create-event
  "Create a Honeycomb event object."
  [^HoneyClient honeycomb-client event-data event-options]
  (let [[event-data {:keys [api-host
                            data-set
                            metadata
                            sample-rate
                            timestamp
                            write-key]}]
        (if-let [epp (honeycomb-client->event-pre-processor honeycomb-client)]
          (epp event-data event-options)
          [event-data event-options])]
    (let [^Event event (.createEvent honeycomb-client)]
      (when api-host
        (.setApiHost event (URI. api-host)))
      (when data-set
        (.setDataset event data-set))
      (when (seq event-data)
        (.addFields event (fields/realize event-data)))
      (when (seq metadata)
        (.addMetadata event metadata))
      (when sample-rate
        (.setSampleRate event ^Long sample-rate))
      (when timestamp
        (.setTimestamp event timestamp))
      (when write-key
        (.setWriteKey event write-key))
      event)))

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
      (let [^Event event (create-event honeycomb-client event-data (or options {}))]
        (if pre-sampled
          (.sendPresampled event)
          (.send event))))))

(def ^:private ^:dynamic *event-data* (atom {}))

(s/fdef add-to-event
  :args (s/alt :map (s/cat :map map?)
               :k-v (s/cat :k any?
                           :v any?))
  :ret map?)

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

(s/fdef with-event-fn
  :args (s/cat :event-data map?
               :options ::send-options
               :f fn?)
  :ret any?)

(defn- with-event-fn
  "A function implementing with-event. See with-event for documentation."
  [event-data options f]
  (binding [*event-data* (atom event-data)]
    (let [options (merge {:timestamp (System/currentTimeMillis)} options)
          start (System/nanoTime)]
      (try
        (f)
        (catch Throwable t
          (add-to-event :exception t)
          (throw t))
        (finally
          (add-to-event :durationMs (/ (- (System/nanoTime) start) 1e6))
          (send *client* @*event-data* options))))))

(defmacro with-event
  "Wrap some code and send an event when the code is done.

   event-data       The fields to send for this event. These will be merged on
                    top of the global fields configured in the HoneyClient. The
                    following additional keys will be added to the event data
                    just before sending:
                    :durationMs   The number of milliseconds it took to execute
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
