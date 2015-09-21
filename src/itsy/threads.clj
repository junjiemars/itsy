(ns itsy.threads
  (:gen-class))

(set! *warn-on-reflection* true)

(defn spawn
  ([^Runnable f] (Thread. f))
  ([^Runnable f ^String n] (Thread. f n)))

(declare current)

(defn id
  ([] (.getId (Thread/currentThread)))
  ([^Thread t] (.getId t)))

(defn name
  ([] (.getName (Thread/currentThread)))
  ([^Thread t] (.getName t))
  ([^Thread t ^String n] (.setName t n) n))

(defn state
  [^Thread t]
  (.getState t))

(defn join
  ([^Thread t] (.join t))
  ([^Thread t ms] (.join t ms))
  ([^Thread t ms ns] (.join t ms ns)))

(defn start
  [^Thread t]
  (.start t))

(defn current
  [] (Thread/currentThread))

(defn sleep
  ([ms] (Thread/sleep ms))
  ([ms ns] (Thread/sleep ms ns)))
