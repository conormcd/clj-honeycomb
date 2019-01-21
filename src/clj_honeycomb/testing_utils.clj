(ns clj-honeycomb.testing-utils
  "Functions to make it easier to test code that uses clj-honeycomb."
  (:use [clojure.future])
  (:require [clojure.spec.alpha :as s]

            [clj-honeycomb.core :as honeycomb])
  (:import (clojure.lang Atom)
           (io.honeycomb.libhoney HoneyClient
                                  Options
                                  ResponseObserver)
           (io.honeycomb.libhoney.responses ResponseObservable)
           (io.honeycomb.libhoney.transport Transport)
           (clj_honeycomb Client)))

(set! *warn-on-reflection* true)

(s/fdef dummy-client
  :args (s/cat :client-options :clj-honeycomb.core/client-options
               :submission-fn fn?)
  :ret (partial instance? HoneyClient))

(defn- dummy-client
  "Create a HoneyClient that behaves entirely like a regular one but instead
   of sending the event it calls submission-fn with the event. This happens as
   deep into libhoney-java as possible, so it exercises most of the library.

   client-options A map of options to pass to the client. This is the same as
                  the options which can be passed to clj-honeycomb.core/init and
                  clj-honeycomb.core/client. See the documentation for those
                  functions for further details. (Map.)
   submission-fn  A function (fn [event] ...) to \"send\" the events sent via
                  this client."
  [client-options submission-fn]
  (let [ro (ResponseObservable.)
        transport (reify Transport
                    (close [_this]
                      nil)
                    (getResponseObservable [_this]
                      ro)
                    (submit [_this event]
                      (.markEnqueueTime event)
                      (.markStartOfHttpRequest event)
                      (.markEndOfHttpRequest event)
                      (submission-fn event)
                      true))
        client (Client. ^Options (#'honeycomb/client-options client-options)
                        ^Transport transport)]
    (when-let [epp (:event-pre-processor client-options)]
      (.setEventPreProcessor client epp))
    (when-let [ro (:response-observer client-options)]
      (.addResponseObserver client (#'honeycomb/response-observer ro)))
    client))

(s/fdef no-op-client
  :args (s/cat :client-options :clj-honeycomb.core/client-options)
  :ret (partial instance? HoneyClient))

(defn ^Client no-op-client
  "Create a HoneyClient that drops every event on the floor. Useful both for
   testing and possibly also for production code that needs a valid client but
   wants to disable event sending for some reason.

   client-options A map of options to pass to the client. This is the same as
                  the options which can be passed to clj-honeycomb.core/init and
                  clj-honeycomb.core/client. See the documentation for those
                  functions for further details. (Map.)"
  [client-options]
  (dummy-client client-options (fn [_event] nil)))

(s/fdef recording-client
  :args (s/cat :events (partial instance? Atom)
               :client-options :clj-honeycomb.core/client-options)
  :ret (partial instance? HoneyClient))

(defn ^Client recording-client
  "Create a HoneyClient which records all events sent by conj'ing them onto the
   atom-wrapped vector supplied.

   events         This must be an (atom []) which will be used to store all
                  events sent using this client. Each element added will be an
                  instance of io.honeycomb.libhoney.eventdata.ResolvedEvent.
   client-options A map of options to pass to the client. This is the same as
                  the options which can be passed to clj-honeycomb.core/init and
                  clj-honeycomb.core/client. See the documentation for those
                  functions for further details. (Map.)"
  [events client-options]
  (when-not (and (instance? Atom events) (vector? @events))
    (throw (IllegalArgumentException. "events must be a vector wrapped in an atom")))
  (dummy-client client-options (partial swap! events conj)))

(s/fdef recording-response-observer
  :args (s/cat :errors (partial instance? Atom))
  :ret (partial instance? ResponseObserver))

(defn- recording-response-observer
  "A ResponseObserver that will record all the received errors in the supplied
   atom-wrapped vector."
  [errors]
  (when-not (and (instance? Atom errors) (vector? @errors))
    (throw (IllegalArgumentException. "errors must be a vector wrapped in an atom")))
  (reify ResponseObserver
    (onClientRejected [_this cr]
      (swap! errors conj cr))
    (onServerAccepted [_this _sa]
      nil)
    (onServerRejected [_this sr]
      (swap! errors conj sr))
    (onUnknown [_this u]
      (swap! errors conj u))))

(s/fdef validate-events
  :args (s/alt :without-options (s/cat :fn-that-sends-events fn?
                                       :fn-to-validate-events fn?)
               :with-options (s/cat :client-options :clj-honeycomb.core/client-options
                                    :fn-that-sends-events fn?
                                    :fn-to-validate-events fn?)))

(defn validate-events
  "Execute some code that uses the implicit client created by
   clj-honeycomb.core/init but trap all events sent and pass them to a
   validation function.

   Example:

   (validate-events
     (fn []
       (with-event {\"foo\" \"bar\"}
         ... some code ...))
     (fn [events errors]
       (is (empty? errors))
       (is (= 1 (count events)))
       ... further validation of events ...))

   client-options        The options to pass to the client, if any. This is the
                         same as the options passed to clj-honeycomb.core/init
                         and clj-honeycomb.core/client. See the documentation
                         for those functions for further details. (Map.
                         Optional.)
   fn-that-sends-events  A 0-arity function which runs some code that may send
                         events using the implicit client created with
                         clj-honeycomb.core/init.
   fn-to-validate-events A function which takes two arguments. The first is a
                         vector of io.honeycomb.libhoney.eventdata.ResolvedEvent
                         objects for every event sent by the code in
                         fn-that-sends-events. The second argument is a vector
                         of io.honeycomb.libhoney.responses.Response for any
                         errors that occurred during the sending of the events."
  ([fn-that-sends-events fn-to-validate-events]
   (validate-events {:data-set "data-set"
                     :write-key "write-key"}
                    fn-that-sends-events
                    fn-to-validate-events))
  ([client-options fn-that-sends-events fn-to-validate-events]
   (let [events (atom [])
         errors (atom [])]
     (try
       (with-open [^Client client (recording-client events client-options)]
         (.addResponseObserver client (recording-response-observer errors))
         (binding [honeycomb/*client* client]
           (fn-that-sends-events)))
       (finally
         (fn-to-validate-events @events @errors))))))
