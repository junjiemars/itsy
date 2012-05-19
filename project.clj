(defproject itsy "0.1.0-SNAPSHOT"
  :description "A threaded web-spider written in Clojure "
  :url "https://github.com/dakrone/itsy"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [clj-http "0.4.1"]
                 [com.cemerick/url "0.0.6"]
                 [org.clojure/tools.logging "0.2.3"]
                 [log4j "1.2.16"]]
  :resource-paths ["etc"])
