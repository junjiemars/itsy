(ns itsy.robots
  "robots.txt fetch, parse and save"
  (:require [cemerick.url    :as u]
            [clj-http.client :as c]
            [clj-robots.core :as r]
            [clojure.tools.logging :as l]))

(def ^:dynamic *host-robots* (atom {}))

(defn fetch-robots-file [host]
  (try (r/parse (:body (c/get (str (u/url host "/robots.txt")))))
       (catch Exception e
         (l/error e)
         nil)))

(defn fetch-and-save-robots
  [a-site]
  (let [directives (fetch-robots a-site)]
    (swap! *host-robots* merge {(str
                                 (-> a-site
                                     url/url
                                     :host))
                                directives})))

(defn crawlable?
  [link]
  (let [the-host (-> link
                     url/url
                     :host)
        the-path (-> link
                     url/url
                     :path)]
    (cond
     (and (@*host-robots* the-host)
          (not (= (@*host-robots* the-host)
                  :not-found)))
     (robots/crawlable? (@*host-robots* the-host) the-path)

     (and (@*host-robots* the-host)
          (= (@*host-robots* the-host)
             :not-found))
     true

     :else
     (do
       (fetch-and-save-robots link)
       (recur link)))))
