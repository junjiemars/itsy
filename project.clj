(defproject itsy "0.1.2-SNAPSHOT"
  :description "A threaded web-spider written in Clojure "
  :url "https://github.com/dakrone/itsy"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/tools.cli "0.3.3"]
                 [clj-http "0.9.2"]
                 [clj-robots "0.6.0"]
                 [com.cemerick/url "0.1.1"]
                 [org.clojure/tools.logging "0.3.1"]
                 [ch.qos.logback/logback-classic "1.1.1"]
                 [org.apache.tika/tika-core "1.5"]
                 [org.apache.tika/tika-parsers "1.5"]]
  :main itsy.core
  ;:resource-paths ["etc"]
  :profiles {:dev
             {:jvm-opts ["-Droot-level=DEBUG"]
              :dependencies [[org.clojure/tools.trace "0.7.8"]]}
             :uberjar {:aot :all
                   :jvm-opts ["-Droot-level=INFO"]}})
