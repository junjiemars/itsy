(ns itsy.core
  "Tool used to crawl web pages with an aritrary handler."
  (:require [clojure.tools.cli :refer [parse-opts]]
            [clojure.string :as s]
            [clojure.tools.logging :as log]
            [clojure.set :as set]
            ;[cemerick.url :refer [url]]
            [clj-http.client :as http]
            [itsy.robots :as robots]
            [slingshot.slingshot
             :refer [get-thrown-object try+]]
            [itsy.config :as c]
            [itsy.url :as u]
            [itsy.threads :as t])
  (:import (java.util.concurrent TimeUnit))
  (:gen-class))

(def ^:dynamic *options* (atom {}))

(def terminated Thread$State/TERMINATED)

(defn- enqueue*
  "Internal function to enqueue a url as a map with :url 
  and :count."
  [config url]
  (log/debug :enqueue-url url)
  (.put 
   (-> config :state :url-queue)
   {:url url :count @(-> config :state :url-count)})
  (swap! (-> config :state :url-count) inc))

(defn enqueue-url
  "Enqueue the url assuming the url-count is below the limit 
  and we haven't seen this url before."
  [config {:keys [:to :from] :as url}]
  (if (get @(-> config :state :seen-urls) to)
    (do
      (swap! (-> config :state :seen-urls)
             update-in [to :count] inc)
      (swap! (:seen-urls (:state config))
             update-in [to :from] #(conj % from)))

    (when (or (neg? (:url-limit config))
              (< @(-> config :state :url-count)
                 (:url-limit config)))
      (when-let [url-info (u/url? to)]
        (if-let [host-limiter (:host-limit config)]
          (when (and (re-find host-limiter (:host url-info))
                     (not (contains?
                           @(:404-urls (:state config))
                           (:host url-info))))
            (do
              (swap! (-> config :state :seen-urls)
                     assoc to {:count 1 :from #{from}})
              (enqueue* config to)))
          (do
            (swap! (-> config :state :seen-urls)
                   assoc to {:count 1 :from #{from}})
            (enqueue* config to)))))))

(defn enqueue-urls
  "Enqueue a collection of urls for work"
  [config {:keys [:to :from] :as urls}]
  (doseq [url to]
    (enqueue-url config {:to url :from from})))

(declare add-worker)
(declare remove-worker)

(defn dequeue
  [config url worker]
  (log/debug "dequeue " url)
  (swap! (:seen-urls (:state config))
         dissoc url)
  (swap! (:404-urls (:state config))
         conj url)
  ;(add-worker config)
  ;(.start (Thread. #(.notify worker)))
  )

(defn- crawl-page
  "Internal crawling function that fetches a page, 
  enqueues url found on that page and calls the handler 
  with the page body."
  [config url-map]
  (let [url (:url url-map)]
    (try+
     (log/trace :retrieving-body-for url-map)
     (log/debug "###:tid=" (t/id))
     (let [score (:count url-map)
           body (:body (http/get url (:http-opts config)))
           _ (log/debug :extracting-urls)
           urls ((:url-extractor config) url body)]
       (enqueue-urls config urls)
       (try
         (when-let [f (:handler config)]
           (f (assoc url-map :body body)))
         (catch Exception e
           (log/error (.getMessage e) e))))
     (catch java.net.SocketTimeoutException e
       (log/debug "###:tid=" (t/id))
       (log/debug "connection timed out to" (:url url-map))
       (dequeue config url (t/current)))
     (catch org.apache.http.conn.ConnectTimeoutException e
       (log/debug "connection timed out to" (:url url-map))
       (dequeue config url (t/current)))
     (catch java.net.UnknownHostException e
       (log/debug "unknown host" (:url url-map) "skipping.")
       (dequeue config url (t/current)))
     (catch org.apache.http.conn.HttpHostConnectException e
       (log/debug "unable to connect to"
                  (:url url-map) "skipping")
       (dequeue config url (t/current)))
     (catch map? m
       (log/debug "###:tid=" (t/id))
       (log/debug "unknown exception retrieving"
                  (:url url-map) "skipping.")
       (log/debug (dissoc m :body) "caught")
       (dequeue config url (t/current)))
     (catch Object e
       (log/debug e "!!!")))))


(defn thread-status
  "Return a map of threadId to Thread.State for a config 
  object."
  [config]
  (let [ws (:running-workers (:state config))]
    (zipmap (map t/id @ws) (map t/state @ws))))


(defn- worker-fn
  "Generate a worker function for a config object."
  [config]
  (fn worker-fn* []
    (loop []
      (log/trace "grabbing url from a queue of"
             (.size (-> config :state :url-queue)) "items")
      (when-let [url-map (.poll 
                          (-> config :state :url-queue)
                          3 TimeUnit/SECONDS)]
        (log/trace :got url-map)
        (cond

         (not (-> config :polite?))
         (crawl-page config url-map)

         ;;(robots/crawlable? (:url url-map))
         true
         (crawl-page config url-map)

         :else
         (log/trace :politely-not-crawling (:url url-map))))
      (let [tid (t/id)]
        (log/trace :running?
                   (get @(-> config :state :worker-canaries)
                        tid))
        (let [state (:state config)
              limit-reached
              (and (pos? (:url-limit config))
                   (>= @(:url-count state) (:url-limit config))
                   zero? (.size (:url-queue state)))]
          (when-not (get @(:worker-canaries state) tid)
            (log/info "my canary has died,terminating myself"))
          (when limit-reached
            (log/debug (str "url limit reached: ("
                            @(:url-count state)
                        "/" (:url-limit config)
                        "), terminating myself")))
          (when (and (get @(:worker-canaries state) tid)
                     (not limit-reached))
            (recur)))))))


(defn start-worker
  "Start a worker thread for a config object, updating the 
  config's state with the new Thread object."
  [config]
  (let [w-thread (t/spawn (worker-fn config))
        _ (t/name w-thread (str "itsy-worker-"
                                (t/name w-thread)))
        w-tid (t/id w-thread)]
    (dosync
     (alter (-> config :state :worker-canaries)
            assoc w-tid true)
     (alter (-> config :state :running-workers)
            conj w-thread))
    (log/trace "Starting thread:" w-thread w-tid)
    (t/start w-thread))
  (log/trace "New worker count:"
             (count @(-> config :state :running-workers))))

(defn stop-workers
  "Given a config object, stop all the workers for that config"
  [config]
  (when (pos? (count @(-> config :state :running-workers)))
    (log/info "Strangling canaries...")
    (dosync
     (ref-set (-> config :state :worker-canaries) {})
     (log/info "Waiting for workers to finish...")
     (map #(t/join % 30000)
          @(-> config :state :running-workers))
     (t/sleep 10000)
     (if (= #{terminated} (set (vals (thread-status config))))
       (do
         (log/info "All workers stopped.")
         (ref-set (-> config :state :running-workers) []))
       (do
         (log/warn "Unable to stop all workers.")
         (ref-set (-> config :state :running-workers)
                  (remove #(= terminated (t/state %))
                          @(-> config
                               :state :running-workers)))))))
  @(-> config :state :running-workers))


(defn add-worker
  "Given a config object, add a worker to the pool, 
  returns the new worker count."
  [config]
  (log/info "Adding one additional worker to the pool")
  (start-worker config)
  (count @(-> config :state :running-workers)))

(defn remove-worker
  "Given a config object, remove a worker from the pool, 
  returns the new worker count."
  [config worker]
  (log/trace "Removing one worker from the pool")
  (dosync
   (when-let [ws @(:running-workers (:state config))]
     (let [new-workers (remove #(= worker %) ws)
           tid (t/id worker)]
       (log/trace "Strangling canary for Thread:" tid)
       (alter (-> config :state :worker-canaries)
              assoc tid false)
       (ref-set (-> config :state :running-workers)
                new-workers))))
  (log/trace "New worker count:"
             (count @(-> config :state :running-workers)))
  (count @(-> config :state :running-workers)))

(defn crawl
  "Crawl a url with the given config."
  [options]
  (log/debug :options options)
  (let [hl (:host-limit options)
        config options]
    (log/trace :config config)
    (log/info "Starting" (:workers config) "workers...")
    (http/with-connection-pool {:timeout 5
                                :threads (:workers config)
                                :insecure? true}
      (dotimes [_ (:workers config)]
        (start-worker config))
      (log/info "Starting crawl of" (:url config))
      (enqueue-url config {:to (:url config) :from "/"}))
    config))

(def cli-options
  [["-f" "--file FILE" "Configuration File"
    :default "config"
    ;:parse-fn #(Integer/parseInt %)
    ;:validate [#(< 0 % 0x10000)
    ;"Must be a number between 0 and 65536"]
    ]
   ["-o" "--out FILE" "Output File"
    :default "cawled"
    ]
   ["-v" nil "Verbosity level"
    :id :verbosity
    :default 0
    :assoc-fn (fn [m k _] (update-in m [k] inc))]
   ["-h" "--help"]])

(defn -main
  "Read configuration file and run"
  [& args]

  (let [opts (parse-opts args cli-options)
        conf (c/read-from-file (:file (:options opts)))
        out (:out (:options opts))
        ]
    (.addShutdownHook
     (Runtime/getRuntime)
     (t/spawn
      #(do
         (c/pretty-save out @c/*config*)
         (c/pretty-save
          (str out "-urls")
          (keys @(:seen-urls (:state @c/*config*)))))))
    (log/info "ready to crawl " (:url conf))
    (crawl conf)))
