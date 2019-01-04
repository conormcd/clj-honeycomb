(ns clj-honeycomb.testing-utils
  "Functions to make it easier to test code that uses clj-honeycomb."
  (:require [clj-honeycomb.core :as honeycomb])
  (:import (clojure.lang Atom)
           (io.honeycomb.libhoney HoneyClient
                                  ResponseObserver)
           (io.honeycomb.libhoney.responses ResponseObservable)
           (io.honeycomb.libhoney.transport Transport)))

(defn recording-client
  "Create a HoneyClient which records all events sent by conj'ing them onto the
   atom-wrapped vector supplied.

   events         This must be an (atom []) which will be used to store all
                  events sent using this client. Each element added will be an
                  instance of io.honeycomb.libhoney.eventdata.ResolvedEvent.
   client-options A map of options to pass to the client. This is the same as
                  the options which can be passed to clj-honeycomb.core/init and
                  clj-honeycomb.core/client. See the documentation for those
                  functions for further details. (Map. Optional.)"
  [events client-options]
  (when-not (and (instance? Atom events) (vector? @events))
    (throw (IllegalArgumentException. "events must be a vector wrapped in an atom")))
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
                      (swap! events conj event)
                      true))
        ro (when (:response-observer client-options)
             (#'honeycomb/response-observer (:response-observer client-options)))
        client-options (#'honeycomb/client-options
                        (merge {:data-set "data-set"
                                :write-key "write-key"}
                               client-options))
        client (HoneyClient. client-options transport)]
    (when ro
      (.addResponseObserver client ro))
    client))

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
   (validate-events {}
                    fn-that-sends-events
                    fn-to-validate-events))
  ([client-options fn-that-sends-events fn-to-validate-events]
   (let [events (atom [])
         errors (atom [])]
     (try
       (with-open [client (recording-client events client-options)]
         (.addResponseObserver client (recording-response-observer errors))
         (binding [honeycomb/*client* client]
           (fn-that-sends-events)))
       (finally
         (fn-to-validate-events @events @errors))))))
