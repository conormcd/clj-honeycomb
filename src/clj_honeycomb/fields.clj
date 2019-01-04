(ns clj-honeycomb.fields
  "Fields are the data that are sent as part of an event. Events are just maps,
   where the keys are either keywords or strings and the values may be anything.
   Since clj-honeycomb wraps the Java Honeycomb SDK, we must transform some of
   the values from Clojure types into more Java-friendly types where possible.

   Only the ->ValueSupplier function in this namespace should be considered part
   of the public API for clj-honeycomb."
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

(defn- stringify-key
  "Field names must be strings but Clojure maps typically have keywords for keys
   so here we coalesce strings and keywords to strings."
  [k]
  (cond (keyword? k) (if-let [n (namespace k)]
                       (str n "/" (name k))
                       (name k))
        (string? k) k
        :else (throw (IllegalArgumentException. "Field names must be keywords or strings"))))

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
                    [(stringify-key k) (realize-value v)]))
             (into {}))

        (set? v)
        (mapv realize-value v)

        (ratio? v)
        (float v)

        (keyword? v)
        (str v)

        :else v))

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

(defn separate
  "Given a map, stringify the keys and convert any values that should be
   ValueSuppliers into them. Then return a tuple where the first item is the
   submap of the input where no values are ValueSuppliers and the second item is
   the submap of the input where all the values are ValueSuppliers."
  [m]
  (let [m (->> m
               (map (fn [[k v]]
                      [(stringify-key k) (maybe-value-supplier v)]))
               (into {}))]
    [(->> m (remove (comp (partial instance? ValueSupplier) val)) (into {}))
     (->> m (filter (comp (partial instance? ValueSupplier) val)) (into {}))]))

(defn realize
  "Given a map, stringify the keys and realize all the values. This must be done
   at the last minute before sending an event so that any dynamic/delayed fields
   are computed as late as possible."
  [m]
  (->> m
       (map (fn [[k v]]
              [(stringify-key k) (realize-value v)]))
       (into {})))
