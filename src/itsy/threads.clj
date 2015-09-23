(ns itsy.threads
  (:require [clojure.tools.logging :as log])
  (:gen-class))

(set! *warn-on-reflection* true)

(def NEW Thread$State/NEW)
(def RUNNABLE Thread$State/RUNNABLE)
(def BLOCKED Thread$State/BLOCKED)
(def WAITING Thread$State/WAITING)
(def TIMED_WAITING Thread$State/TIMED_WAITING)
(def TERMINATED Thread$State/TERMINATED)

(defn spawn
  ([^Runnable f] (Thread. f))
  ([^Runnable f ^String n] (Thread. f n)))

(declare current)

(defn id
  ([] (.getId (Thread/currentThread)))
  ([^Thread t] (.getId t)))

(defn named
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
  ([^Thread t] (.start t))
  ([^String n ^Runnable f] (.start (Thread. f n))))

(defn current
  [] (Thread/currentThread))

(defn sleep
  ([ms] (Thread/sleep ms))
  ([ms ns] (Thread/sleep ms ns)))

(defn interrupt
  [^Thread t]
  (try
    (.interrupt t)
    (catch SecurityException e
      (log/error e))))
