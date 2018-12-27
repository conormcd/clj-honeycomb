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
   atom-wrapped vector supplied."
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
   validation function."
  ([fn-that-sends-events fn-to-validate-events]
   (validate-events {}
                    fn-that-sends-events
                    fn-to-validate-events))
  ([client-options fn-that-sends-events fn-to-validate-events]
   (let [events (atom [])
         errors (atom [])]
     (with-open [client (recording-client events client-options)]
       (.addResponseObserver client (recording-response-observer errors))
       (binding [honeycomb/*client* client]
         (fn-that-sends-events)))
     (fn-to-validate-events @events @errors))))
