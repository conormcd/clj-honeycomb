(ns ^:no-doc clj-honeycomb.propagation
  (:use [clojure.future])
  (:require [clojure.data.codec.base64 :as base64]
            [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.set :refer (rename-keys)]
            [clojure.string :as str])
  (:import (java.nio.charset Charset)))

(def ^:private ^Charset utf-8 (Charset/forName "UTF-8"))

(s/def ::context (s/map-of string?
                           (s/or :s string?
                                 :f (s/and float?
                                           #(not (Double/isInfinite %))
                                           #(not (Double/isNaN %)))
                                 :i integer?
                                 :b boolean?)))
(s/def ::parent-span-id (s/and string? seq #(not (re-find #"," %))))
(s/def ::trace-id (s/and string? seq #(not (re-find #"," %))))
(s/def ::version #{1})
(s/def ::propagation-data (s/keys :req-un [::version
                                           ::trace-id]
                                  :opt-un [::context
                                           ::parent-span-id]))

(s/fdef str->base64
  :args (s/cat :s string?)
  :ret string?)

(defn- str->base64
  "Convert a string into its base64 encoded form.

   s  The string to be encoded."
  [^String s]
  (String. ^bytes (base64/encode (.getBytes s utf-8)) utf-8))

(s/fdef base64->str
  :args (s/cat :s string?)
  :ret string?)

(defn- base64->str
  "Decode a base64-encoded string.

   s  The string to be decoded."
  [^String s]
  (String. ^bytes (base64/decode (.getBytes s utf-8)) utf-8))

(s/fdef pack
  :args (s/cat :propagation-data ::propagation-data)
  :ret string?)

(defn pack
  "Encode the items needed for trace propagation into the format used by all
   the beelines.

   :context        A map of data to add to the event from the parent span.
   :parent-span-id The ID of the span which should be the parent of the span
                   we'll create with a new event. Must not include a comma.
   :trace-id       The trace ID to propagate. Must not include commas.
   :version        This must exist and must be the number 1."
  [{:keys [context parent-span-id trace-id version]}]
  (when trace-id
    (str version
         ";trace_id=" trace-id
         (when parent-span-id
           (str ",parent_id=" parent-span-id))
         (when context
           (str ",context=" (-> context json/write-str str->base64))))))

(s/fdef unpack
  :args (s/cat :propagation-header string?)
  :ret ::propagation-data)

(defn unpack
  "Parse an X-Honeycomb-Trace header and extract the data needed for trace
   propagation.

   propagation-header The value of the X-Honeycomb-Trace header in the same
                      format as all the beelines."
  [propagation-header]
  (when (= "1;" (subs propagation-header 0 2))
    (-> (->> (str/split (subs propagation-header 2) #",")
             (map #(str/split % #"=" 2))
             (into {}))
        (rename-keys {"trace_id" :trace-id
                      "parent_id" :parent-span-id
                      "context" :context})
        (select-keys [:context :parent-span-id :trace-id])
        (assoc :version 1)
        ((fn [prop-map]
           (if (:context prop-map)
             (update prop-map
                     :context
                     (comp json/read-str base64->str))
             prop-map))))))
