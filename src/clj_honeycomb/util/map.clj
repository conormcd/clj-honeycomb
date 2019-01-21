(ns ^:no-doc clj-honeycomb.util.map
  "Some semi-generic map operations. We can't use clojure.walk for these
   operations since it consumes seqs like doall. These map operations work on
   maps *only*."
  (:refer-clojure :exclude [flatten])
  (:use [clojure.future])
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]

            [clj-honeycomb.util.keyword :refer (stringify-keyword)]))

(set! *warn-on-reflection* true)

(s/fdef stringify-keys
  :args (s/cat :m map?)
  :ret map?)

(defn stringify-keys
  "Recursively walk a map and convert all keyword keys to strings. We can't
   use clojure.walk/stringify-keys because that just calls name on keywords
   and that removes the namespace from the keyword.

   m The map to transform."
  [m]
  (when-not (map? m)
    (throw (IllegalArgumentException. "The argument to stringify-keys must be a map.")))
  (->> m
       (map (fn [[k v]]
              [(cond (map? k) (stringify-keys k)
                     (keyword? k) (stringify-keyword k)
                     :else (str k))
               (if (map? v)
                 (stringify-keys v)
                 v)]))
       (into {})))

(s/fdef paths
  :args (s/alt :without-prefix (s/cat :m map?)
               :with-prefix (s/cat :prefix vector?
                                   :m map?))
  :ret (s/coll-of vector?))

(defn paths
  "If a path is a vector that can be handed to get-in, assoc-in, etc, then
   this function produces all the valid paths to leaf nodes in a map.

   m      The map to traverse
   prefix Used for recursion, do not use."
  ([m]
   (paths [] m))
  ([prefix m]
   (when-not (map? m)
     (throw (IllegalArgumentException. "The argument to paths must be a map.")))
   (mapcat (fn [[k v]]
             (if (map? v)
               (paths (conj prefix k) v)
               [(conj prefix k)]))
           m)))

(s/fdef flatten
  :args (s/cat :f (s/fspec :args (s/cat :path vector?)
                           :ret any?)
               :m map?)
  :ret map?)

(defn flatten
  "Flatten an arbitrarily deep map into a flat map. Takes a function to
   transform the key path of each entry into a key for the new map.

   f A function which takes a vector of keys describing the path to an entry in
     the map and returning the key where it should be stored in the resulting
     map.
   m The map to flatten"
  [f m]
  (when-not (fn? f)
    (throw (IllegalArgumentException. "The first argument to flatten must be a function.")))
  (when-not (map? m)
    (throw (IllegalArgumentException. "The second argument to flatten must be a map.")))
  (->> (paths m)
       (map (fn [path]
              [(f path) (get-in m path)]))
       (into {})))

(s/fdef flatten-and-stringify
  :args (s/cat :prefix string?
               :m map?)
  :ret map?)

(defn flatten-and-stringify
  "Flatten a map with flatten, and convert the path of keys to a dot-separated
   string representation of the path, prefixing each key with prefix.

   prefix A prefix for all keys in the output map.
   m      The map to flatten"
  [prefix m]
  (when-not (string? prefix)
    (throw (IllegalArgumentException. "The prefix must be a String")))
  (when-not (map? m)
    (throw (IllegalArgumentException. "The second argument to flatten-and-stringify must be a map.")))
  (flatten (fn [path]
             (->> path
                  (map #(if (keyword? %)
                          (stringify-keyword %)
                          (str %)))
                  (str/join ".")
                  (str prefix)))
           m))
