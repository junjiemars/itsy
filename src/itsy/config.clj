(ns itsy.config
  "Read, parse and save config file"
  (:require [clojure.java.io :refer [writer]]
            [clojure.pprint :refer [pprint]]
            [itsy.url :as u])
  (:import (java.net URL)
           (java.util.concurrent LinkedBlockingQueue TimeUnit))
  (:gen-class))

(def ^:dynamic *config* (atom
                         {:workers 1
                          :url-limit 100
                          :url-extractor u/extract-all
                          :state {:url-queue (LinkedBlockingQueue.)
                                  :url-count (atom 0)
                                  :running-workers (ref [])
                                  :worker-canaries (ref {})
                                  :seen-urls (atom {})}
                          :http-opts {:socket-timeout 10000
                                      :conn-timeout 10000
                                      :insecure? true
                                      :throw-entire-message? false}
                          :polite? true}))


(defn read-from-file
  "Read config from file and store it in *config* "
  [f]
  (let [c (read-string (slurp f))]
    (swap! *config* merge c)))

(defn save-to-file
  "Save *config* to file"
  ([f] (spit f @*config*))
  ([f c] (spit f c)))

(defn pretty-save
  ([f] (pprint @*config* (writer f)))
  ([f c] (pprint c (writer f))))
