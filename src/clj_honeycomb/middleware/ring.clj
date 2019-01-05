(ns clj-honeycomb.middleware.ring
  "Ring middleware to turn every request/response into a Honeycomb event."
  (:require [clj-honeycomb.core :as honeycomb]
            [clj-honeycomb.util.map :as map-util]))

(defn- default-extract-request-fields
  "Convert a Ring request into a map of fields to be added to the event.

   request  The Ring request map."
  [request]
  (map-util/flatten-and-stringify "ring.request." (dissoc request :body)))

(defn- default-extract-response-fields
  "Convert a Ring response into a map of fields to be added to the event.

   response  The Ring response map."
  [response]
  (map-util/flatten-and-stringify "ring.response."
                                  (some-> response
                                          (select-keys [:headers :status]))))

(defn with-honeycomb-event
  "Ring middleware to turn every request/response into a Honeycomb event. By
   default every item in the request map and the status and headers from the
   response map are included in the event. If you have sensitive data in your
   request or response maps you may wish to implement your own
   :extract-request-fields or :extract-response-fields functions to prevent them
   from leaking to Honeycomb.

   options  A map with any of the following items:

            :extract-request-fields  A function which takes one argument, the
                                     Ring request map and returns a map of data
                                     to be added to the event.
            :extract-response-fields A function which takes one argument, the
                                     Ring response map and returns a map of data
                                     to be added to the event.
            :honeycomb-event-data    Fields to be added to the event regardless
                                     of request or response. Will be overridden
                                     by anything added by extract-request-fields
                                     or extract-response-fields.
            :honeycomb-event-options Options to be passed to
                                     clj-honeycomb.core/send as the options
                                     argument. See that function for full
                                     documentation as to what's supported.
   handler  The Ring handler function."
  ([handler]
   (with-honeycomb-event {} handler))
  ([options handler]
   (let [make-event-data (fn [request]
                           (merge {}
                                  (:honeycomb-event-data options)
                                  (let [f (or (:extract-request-fields options)
                                              default-extract-request-fields)]
                                    (f request))))
         add-data-from-response (fn [response]
                                  (let [f (or (:extract-response-fields options)
                                              default-extract-response-fields)]
                                    (honeycomb/add-to-event (f response)))
                                  response)
         event-options (or (:honeycomb-event-options options) {})]
     (fn
       ([request]
        (honeycomb/with-event (make-event-data request) event-options
          (add-data-from-response (handler request))))
       ([request respond raise]
        (handler request
                 (fn [response]
                   (honeycomb/with-event (make-event-data request) event-options
                     (add-data-from-response (respond response))))
                 (fn [exception]
                   (honeycomb/with-event (make-event-data request) event-options
                     (honeycomb/add-to-event {:exception exception})
                     (raise exception)))))))))
