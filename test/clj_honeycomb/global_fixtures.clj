(ns clj-honeycomb.global-fixtures
  "A combination of functions that must get called once and early during
   testing and some global testing data."
  (:require [clojure.spec.test.alpha :as stest]
            [ring.mock.request :as mock-request])
  (:import (java.util UUID)))

;; Functions which we want called once and early during testing.
(set! *warn-on-reflection* true)
(stest/instrument)

;; Notes for testing:
;;
;; IBlockingDeref means future/promise
;; IDeref means agent/atom/delay/future/promise/ref/volatile.
;; IPending means cycle/delay/iterate/lazy-seq
;;
;; With the above in mind, everything manipulating event fields should be
;; tested with at least one each of:
;;
;; - agent
;; - atom
;; - cycle
;; - delay
;; - future (both running and finished)
;; - iterate
;; - lazy-seq
;; - promise (both delivered and undelivered)
;; - range
;; - ref
;; - repeat
;; - volatile
;;
;; In addition to this, we need to test the following Clojure values:
;;
;; - double
;; - exception
;; - keyword
;; - list
;; - long
;; - map
;; - nil
;; - ratio
;; - set
;; - string
;; - vector
;;
;; To facilitate testing, we have a function to produce a "kitchen sink" map
;; containing all of the above.

(def ^:private exception (Exception. "An exception"))

(def ^:private uuid (UUID/randomUUID))

(defn make-kitchen-sink
  []
  (let [a (agent 0)
        p (promise)
        positive-numbers (fn x
                           [n]
                           (lazy-seq (cons n (x (inc n)))))]
    (send a inc)
    (deliver p "delivered")
    (-> {:agent a
         :atom (atom 2)
         :cycle (cycle [1 2])
         :delay (delay 3)
         :double 2.0
         :exception exception
         :future-finished (future 42)
         :future-running (future (while true (Thread/sleep 10000)))
         :iterate (iterate identity 13)
         :keyword :keyword
         :lazy-seq (positive-numbers 1)
         :list '(1 2 3)
         :long 1
         :nil nil
         :promise-delivered p
         :promise-pending (promise)
         :range (range)
         :ratio 1/2
         :ref (ref 50)
         :repeat (repeat 1)
         :set #{"foo" "bar"}
         :string "string"
         :uuid uuid
         :vector [1 2 3 4 5]
         :volatile (volatile! 3)}
        ((fn [x]
           (assoc x :map (dissoc x :exception)))))))

(def kitchen-sink-realized
  (-> {"agent" 1
       "atom" 2
       "cycle" (take 1000 (cycle [1 2]))
       "delay" 3
       "double" 2.0
       "exception" exception
       "future-finished" 42
       "future-running" nil
       "iterate" (take 1000 (repeat 13))
       "keyword" ":keyword"
       "lazy-seq" (range 1 1001)
       "list" '(1 2 3)
       "long" 1
       "nil" nil
       "promise-delivered" "delivered"
       "promise-pending" nil
       "range" (range 1000)
       "ratio" (float 1/2)
       "ref" 50
       "repeat" (take 1000 (repeat 1))
       "set" (vec #{"foo" "bar"})
       "string" "string"
       "uuid" (str uuid)
       "vector" [1 2 3 4 5]
       "volatile" 3}
      ((fn [x]
         (assoc x "map" (dissoc x "exception"))))))

(def sample-ring-request
  (mock-request/request :get "/foo?bar=bar&baz=baz"))

(def sample-ring-request-extracted
  {"ring.request.headers.host" "localhost"
   "ring.request.protocol" "HTTP/1.1"
   "ring.request.query-string" "bar=bar&baz=baz"
   "ring.request.remote-addr" "localhost"
   "ring.request.request-method" :get
   "ring.request.scheme" :http
   "ring.request.server-name" "localhost"
   "ring.request.server-port" 80
   "ring.request.uri" "/foo"})

(def sample-ring-response
  {:body "Hello world!"
   :headers {"Content-Type" "text/plain"
             :some-other-header :ring-is-funny}
   :status 200})

(def sample-ring-response-extracted
  {"ring.response.headers.Content-Type" "text/plain"
   "ring.response.headers.some-other-header" :ring-is-funny
   "ring.response.status" 200})
