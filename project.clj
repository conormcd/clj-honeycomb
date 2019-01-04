(def libhoney-version "1.0.2")

(defproject conormcd/clj-honeycomb (str libhoney-version
                                        (or (some->> "CIRCLE_BUILD_NUM" System/getenv (str "."))
                                            "-dev"))
  :description "A Clojure interface to Honeycomb.io, built on libhoney-java."
  :url "http://github.com/conormcd/clj-honeycomb"
  :license {:name "Apache License, Version 2.0"
            :url "https://github.com/conormcd/clj-honeycomb/blob/master/LICENSE"}
  :repositories [["releases" {:url "https://clojars.org/repo"
                              :username :env/clojars_username
                              :password :env/clojars_password
                              :sign-releases false}]
                 ["snapshots" {:url "https://clojars.org/repo"
                               :username :env/clojars_username
                               :password :env/clojars_password
                               :sign-releases false}]]
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [clojure-future-spec "1.9.0"]
                 [io.honeycomb.libhoney/libhoney-java ~libhoney-version]]
  :pedantic? :abort
  :plugins [[lein-cljfmt "0.6.3"]
            [lein-cloverage "1.0.13" :exclusions [org.clojure/clojure]]
            [lein-codox "0.10.5"]]
  :profiles {:dev {:dependencies [[cloverage "1.0.13" :exclusions [org.clojure/clojure]]
                                  [org.clojure/data.json "0.2.6"]
                                  [org.slf4j/slf4j-simple "1.7.25"]
                                  [se.haleby/stub-http "0.2.5"]]
                   :codox {:exclude-vars nil
                           :namespaces :all}}})
