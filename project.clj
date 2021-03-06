(def libhoney-version "1.0.6")

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
                 [org.clojure/data.codec "0.1.1"]
                 [org.clojure/data.json "0.2.6"]
                 [clojure-future-spec "1.9.0"]
                 [io.honeycomb.libhoney/libhoney-java ~libhoney-version]]
  :java-source-paths ["src-java"]
  :pedantic? :abort
  :global-vars {*warn-on-reflection* true}
  :plugins [[lein-cljfmt "0.6.3"]
            [lein-cloverage "1.0.13" :exclusions [org.clojure/clojure]]
            [lein-codox "0.10.5"]
            [lein-kibit "0.1.6"]
            [lein-nvd "0.6.0" :exclusions [org.apache.maven.wagon/wagon-http
                                           org.codehaus.plexus/plexus-utils
                                           org.slf4j/slf4j-api
                                           org.slf4j/jcl-over-slf4j]]]
  :nvd {:data-directory "/tmp/nvd/data"}
  :profiles {:dev {:dependencies [[cloverage "1.0.13" :exclusions [org.clojure/clojure]]
                                  [org.clojure/data.json "0.2.6"]
                                  [org.clojure/test.check "0.10.0-alpha3"]
                                  [ch.qos.logback/logback-classic "1.2.3"]
                                  [ring/ring-mock "0.3.2"]
                                  [se.haleby/stub-http "0.2.7"]]
                   :codox {:exclude-vars nil
                           :namespaces :all}}})
