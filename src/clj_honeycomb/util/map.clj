(ns ^:no-doc clj-honeycomb.util.map
  "Some semi-generic map operations. We can't use clojure.walk for these
   operations since it consumes seqs like doall. These map operations work on
   maps *only*."
  (:refer-clojure :exclude [flatten])
  (:require [clojure.string :as str]

            [clj-honeycomb.util.keyword :refer (stringify-keyword)]))

(defn stringify-keys
  "Recursively walk a map and convert all keyword keys to strings. We can't
   use clojure.walk/stringify-keys because that just calls name on keywords
   and that removes the namespace from the keyword.

   m The map to transform."
  [m]
  (if (map? m)
    (->> m
         (map (fn [[k v]]
                [(stringify-keys (stringify-keyword k))
                 (stringify-keys v)]))
         (into {}))
    m))

(defn paths
  "If a path is a vector that can be handed to get-in, assoc-in, etc, then
   this function produces all the valid paths to leaf nodes in a map.

   m      The map to traverse
   prefix Used for recursion, do not use."
  ([m]
   (paths [] m))
  ([prefix m]
   (if (map? m)
     (mapcat (fn [[k v]]
               (if (map? v)
                 (paths (conj prefix k) v)
                 [(conj prefix k)]))
             m)
     [])))

(defn flatten
  "Flatten an arbitrarily deep map into a flat map. Takes a function to
   transform the key path of each entry into a key for the new map.

   f A function which takes a vector of keys describing the path to an entry in
     the map and returning the key where it should be stored in the resulting
     map.
   m The map to flatten"
  [f m]
  (if (map? m)
    (->> (paths m)
         (map (fn [path]
                [(f path) (get-in m path)]))
         (into {}))
    m))

(defn flatten-and-stringify
  "Flatten a map with flatten, and convert the path of keys to a dot-separated
   string representation of the path, prefixing each key with prefix.

   prefix A prefix for all keys in the output map.
   m      The map to flatten"
  [prefix m]
  (flatten (fn [path]
             (->> path
                  (map (comp str stringify-keyword))
                  (str/join ".")
                  (str prefix)))
           m))
