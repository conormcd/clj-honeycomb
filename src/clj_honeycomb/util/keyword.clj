(ns ^:no-doc clj-honeycomb.util.keyword
  "Utility functions for manipulating keywords"
  (:require [clojure.spec.alpha :as s]))

(s/fdef stringify-keyword
  :args (s/cat :k keyword?)
  :ret string?)

(defn stringify-keyword
  "Convert a keyword to a string without losing the namespace information.

   k A keyword to turn into a string."
  [k]
  (when-not (keyword? k)
    (throw (IllegalArgumentException. "Input to stringify-keyword must be a keyword")))
  (if-let [n (namespace k)]
    (str n "/" (name k))
    (name k)))
