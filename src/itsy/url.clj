(ns itsy.url
  (:require [clojure.set :as set]
            [clojure.tools.logging :as log]
            [cemerick.url :as u])
  (:gen-class))

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
          fq-ufq (map #(str (u/url original-url %)) ufq)
          all (set (concat fq fq-ufq))]
      (log/info (format "###%s" all))
      all)))
