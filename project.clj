(defproject itsy "0.1.2-SNAPSHOT"
  :description "A threaded web-spider written in Clojure "
  :url "https://github.com/dakrone/itsy"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [clj-http "0.9.2"]
                 [clj-robots "0.6.0"]
                 [com.cemerick/url "0.1.1"]
                 [org.clojure/tools.logging "0.3.1"]
                 [log4j/log4j "1.2.17" :exclusions [javax.mail/mail
                                                    javax.jms/jms
                                                    com.sun.jmdk/jmxtools
                                                    com.sun.jmx/jmxri]]
                 [org.slf4j/slf4j-log4j12 "1.7.5"]
                 [org.apache.tika/tika-core "1.5"]
                 [org.apache.tika/tika-parsers "1.5"]]
  :main itsy.core
  ;:resource-paths ["etc"]
  :profiles {:dev {:jvm-opts ["-Dlogfile.path=itsy.log"]}
             :uberjar {:aot :all}}
  )
