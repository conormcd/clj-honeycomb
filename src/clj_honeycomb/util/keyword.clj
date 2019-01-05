(ns ^:no-doc clj-honeycomb.util.keyword
  "Utility functions for manipulating keywords")

(defn stringify-keyword
  "Convert a keyword to a string without losing the namespace information.
   Behaves like identity for non-keyword inputs.

   k A keyword to turn into a string."
  [k]
  (if (keyword? k)
    (if-let [n (namespace k)]
      (str n "/" (name k))
      (name k))
    k))
