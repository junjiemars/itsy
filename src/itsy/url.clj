(ns itsy.url
  (:require [clojure.set :as set]
            [clojure.tools.logging :as log]
            [cemerick.url :refer [url]])
  (:gen-class))

(defn url?
  "Returns a map about the url if the url is valid, else nil"
  [u]
  (try (url u)
       (catch Exception e
         (log/error e) nil)))

(defn extract-all
  "Dumb URL extraction based on regular expressions. Extracts relative URLs."
  [original-url body]
  (when body
    (let [candidates1 (->> (re-seq #"href=\"([^\"]+)\"" body)
                           (map second)
                           (remove #(or (= % "/")
                                        (.startsWith % "#")))
                           set)
          candidates2 (->> (re-seq #"href='([^']+)'" body)
                           (map second)
                           (remove #(or (= % "/")
                                        (.startsWith % "#")))
                           set)
          url-regex #"https?://[-A-Za-z0-9+&@#/%?=~_|!:,.;]*[-A-Za-z0-9+&@#/%=~_|]"
          candidates3 (re-seq url-regex body)
          all-candidates (set (concat candidates1 candidates2 candidates3))
          fq (set (filter #(.startsWith % "http") all-candidates))
          ufq (set/difference all-candidates fq)
          fq-ufq (map #(str (url original-url %)) ufq)
          all (set (concat fq fq-ufq))]
      (log/info all)
      {:to all :from original-url})))

(defn url-counter [{:keys [url body]}]
  (log/info url " had been crawled " (count body) " times"))
