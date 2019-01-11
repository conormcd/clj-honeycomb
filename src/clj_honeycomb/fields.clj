(ns clj-honeycomb.fields
  "Fields are the data that are sent as part of an event.

   Events are just maps, where the keys are either keywords or strings and the
   values may be anything. Since clj-honeycomb wraps the Java Honeycomb SDK, we
   must transform some of the values from Clojure types into more Java-friendly
   types where possible.

   Only the ->ValueSupplier function in this namespace should be considered part
   of the public API for clj-honeycomb."
  (:require [clj-honeycomb.util.map :as util-map])
  (:import (clojure.lang IBlockingDeref
                         IDeref
                         IPending
                         Repeat)
           (io.honeycomb.libhoney ValueSupplier)))

(defn ->ValueSupplier
  "Convert a function to a ValueSupplier so that it can act as a dynamic field."
  [f & args]
  (reify ValueSupplier
    (supply [_this]
      (apply f args))))

(defn- prepare-value-for-json
  "Transform a Clojure value into something that will serialise nicely into JSON
   when it's being sent to Honeycomb."
  [v]
  (cond (keyword? v) (str v)
        (ratio? v) (float v)
        :else v))

(defn- realize-value
  "Clojure has several types which are not trivially transformed into JSON in
   the libhoney-java library. Things we're trying to avoid here are:

   - Blocking on undelivered promises
   - Serializing infinite/lazy sequences
   - Sending Clojure ratios as clojure.lang.Ratio rather than a float"
  [v]
  (cond (instance? IBlockingDeref v)
        (realize-value (deref v 0 nil))

        (instance? IDeref v)
        (realize-value (deref v))

        (instance? Repeat v)
        (mapv realize-value (take 1000 v))

        (instance? ValueSupplier v)
        (realize-value (.supply ^ValueSupplier v))

        (and (instance? IPending v) (sequential? v))
        (mapv realize-value (take 1000 v))

        (sequential? v)
        (mapv realize-value v)

        (map? v)
        (->> v
             (map (fn [[k v]]
                    [k (realize-value v)]))
             (into {}))

        (set? v)
        (mapv realize-value v)

        :else (prepare-value-for-json v)))

(defn- maybe-value-supplier
  "Convert anything that is delay-like into a ValueSupplier for the Honeycomb
   Java SDK. This delays the evaluation of these things until the point of event
   creation."
  [x]
  (if (or (instance? IBlockingDeref x)
          (instance? IDeref x)
          (instance? IPending x)
          (instance? Repeat x)
          (map? x)
          (sequential? x)
          (set? x))
    (->ValueSupplier realize-value x)
    x))

(defn ^:no-doc separate
  "Given a map, stringify the keys and convert any values that should be
   ValueSuppliers into them. Then return a tuple where the first item is the
   submap of the input where no values are ValueSuppliers and the second item is
   the submap of the input where all the values are ValueSuppliers."
  [m]
  (let [m (->> m
               util-map/stringify-keys
               (map (fn [[k v]]
                      [k (prepare-value-for-json (maybe-value-supplier v))]))
               (into {}))]
    [(->> m (remove (comp (partial instance? ValueSupplier) val)) (into {}))
     (->> m (filter (comp (partial instance? ValueSupplier) val)) (into {}))]))

(defn ^:no-doc realize
  "Given a map, stringify the keys and realize all the values. This must be done
   at the last minute before sending an event so that any dynamic/delayed fields
   are computed as late as possible."
  [m]
  (->> m
       util-map/stringify-keys
       (map (fn [[k v]]
              [k (realize-value v)]))
       (into {})))
