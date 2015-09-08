(ns itsy.robots
  "robots.txt fetching and parsing then save it in *robots*"
  (:require [cemerick.url    :as u]
            [clj-http.client :as c]
            [clj-robots.core :as r]
            [clojure.tools.logging :as l]))

(def ^:dynamic *robots* (atom {}))

(defn fetch-robots-file [url]
  (let [host (:host (u/url url))
        robots (c/get (str (u/url url "/robots.txt")))
        body (r/parse (:body robots))]
    (swap! *robots* assoc (keyword host) body)))


(defn crawable? [url]
  (let [host (:host (u/url url))]
    (cond (contains? @*robots* (keyword host)) true
          ;(detect via path)
          :else true)))


