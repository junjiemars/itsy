(ns itsy.core
  "Tool used to crawl web pages with an aritrary handler."
  (:require [clojure.tools.cli :refer [parse-opts]]
            [cemerick.url :refer [url]]
            [clojure.string :as s]
            [clojure.tools.logging :as log]
            [clojure.set :as set]
            [clj-http.client :as http]
            [itsy.robots :as robots]
            [slingshot.slingshot :refer [get-thrown-object try+]]
            [itsy.config :as c]
            [itsy.url :as u])
  (:import (java.net URL)
           (java.util.concurrent LinkedBlockingQueue TimeUnit))
  (:gen-class))

(def ^:dynamic *options* (atom {}))

(def terminated Thread$State/TERMINATED)

(defn url?
  "Returns a map about the url if the url is valid, else nil"
  [u]
  (try (url u)
       (catch Exception e
         (log/error e) nil)))

(defn- enqueue*
  "Internal function to enqueue a url as a map with :url and :count."
  [config url]
  (log/trace :enqueue-url url)
  (.put (-> config :state :url-queue)
        {:url url :count @(-> config :state :url-count)})
  (swap! (-> config :state :url-count) inc))


(defn enqueue-url
  "Enqueue the url assuming the url-count is below the limit and we haven't seen
  this url before."
  [config url]
  (if (get @(-> config :state :seen-urls) url)
    (swap! (-> config :state :seen-urls) update-in [url] inc)

    (when (or (neg? (:url-limit config))
              (< @(-> config :state :url-count) (:url-limit config)))
      (when-let [url-info (url? url)]
        (swap! (-> config :state :seen-urls) assoc url 1)
        (if-let [host-limiter (:host-limit config)]
          (when (re-find host-limiter (:host url-info))
            (enqueue* config url))
          (enqueue* config url))))))

(defn enqueue-urls
  "Enqueue a collection of urls for work"
  [config urls]
  (doseq [url urls]
    (enqueue-url config url)))

(defn- crawl-page
  "Internal crawling function that fetches a page, enqueues url found on that
  page and calls the handler with the page body."
  [config url-map]
  (try+
   (log/trace :retrieving-body-for url-map)
   (let [url (:url url-map)
         score (:count url-map)
         body (:body (http/get url (:http-opts config)))
         _ (log/trace :extracting-urls)
         urls ((:url-extractor config) url body)]
     (enqueue-urls config urls)
     (try
       ((:handler config) (assoc url-map :body body))
       (catch Exception e
         (log/error e "Exception executing handler"))))
   (catch java.net.SocketTimeoutException e
     (log/trace "connection timed out to" (:url url-map)))
   (catch org.apache.http.conn.ConnectTimeoutException e
     (log/trace "connection timed out to" (:url url-map)))
   (catch java.net.UnknownHostException e
     (log/trace "unknown host" (:url url-map) "skipping."))
   (catch org.apache.http.conn.HttpHostConnectException e
     (log/trace "unable to connect to" (:url url-map) "skipping"))
   (catch map? m
     (log/debug "unknown exception retrieving" (:url url-map) "skipping.")
     (log/debug (dissoc m :body) "caught"))
   (catch Object e
     (log/debug e "!!!"))))


(defn thread-status
  "Return a map of threadId to Thread.State for a config object."
  [config]
  (zipmap (map (memfn getId) @(-> config :state :running-workers))
          (map (memfn getState) @(-> config :state :running-workers))))


(defn- worker-fn
  "Generate a worker function for a config object."
  [config]
  (fn worker-fn* []
    (loop []
      (log/trace "grabbing url from a queue of"
             (.size (-> config :state :url-queue)) "items")
      (when-let [url-map (.poll (-> config :state :url-queue)
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
      (let [tid (.getId (Thread/currentThread))]
        (log/trace :running? (get @(-> config :state :worker-canaries) tid))
        (let [state (:state config)
              limit-reached (and (pos? (:url-limit config))
                                 (>= @(:url-count state) (:url-limit config))
                                 (zero? (.size (:url-queue state))))]
          (when-not (get @(:worker-canaries state) tid)
            (log/debug "my canary has died, terminating myself"))
          (when limit-reached
            (log/debug (str "url limit reached: (" @(:url-count state)
                        "/" (:url-limit config) "), terminating myself")))
          (when (and (get @(:worker-canaries state) tid)
                     (not limit-reached))
            (recur)))))))


(defn start-worker
  "Start a worker thread for a config object, updating the config's state with
  the new Thread object."
  [config]
  (let [w-thread (Thread. (worker-fn config))
        _ (.setName w-thread (str "itsy-worker-" (.getName w-thread)))
        w-tid (.getId w-thread)]
    (dosync
     (alter (-> config :state :worker-canaries) assoc w-tid true)
     (alter (-> config :state :running-workers) conj w-thread))
    (log/info "Starting thread:" w-thread w-tid)
    (.start w-thread))
  (log/info "New worker count:" (count @(-> config :state :running-workers))))


(defn stop-workers
  "Given a config object, stop all the workers for that config."
  [config]
  (when (pos? (count @(-> config :state :running-workers)))
    (log/info "Strangling canaries...")
    (dosync
     (ref-set (-> config :state :worker-canaries) {})
     (log/info "Waiting for workers to finish...")
     (map #(.join % 30000) @(-> config :state :running-workers))
     (Thread/sleep 10000)
     (if (= #{terminated} (set (vals (thread-status config))))
       (do
         (log/info "All workers stopped.")
         (ref-set (-> config :state :running-workers) []))
       (do
         (log/warn "Unable to stop all workers.")
         (ref-set (-> config :state :running-workers)
                  (remove #(= terminated (.getState %))
                          @(-> config :state :running-workers)))))))
  @(-> config :state :running-workers))


(defn add-worker
  "Given a config object, add a worker to the pool, returns the new
  worker count."
  [config]
  (log/info "Adding one additional worker to the pool")
  (start-worker config)
  (count @(-> config :state :running-workers)))


(defn remove-worker
  "Given a config object, remove a worker from the pool, returns the new
  worker count."
  [config]
  (log/info "Removing one worker from the pool")
  (dosync
   (when-let [worker (first @(-> config :state :running-workers))]
     (let [new-workers (drop 1 @(-> config :state :running-workers))
           tid (.getId worker)]
       (log/debug "Strangling canary for Thread:" tid)
       (alter (-> config :state :worker-canaries) assoc tid false)
       (ref-set (-> config :state :running-workers) new-workers))))
  (log/info "New worker count:" (count @(-> config :state :running-workers)))
  (count @(-> config :state :running-workers)))

(defn crawl
  "Crawl a url with the given config."
  [options]
  (log/trace :options options)
  (let [hl (:host-limit options)
        config options
        ]
    (log/trace :config config)
    (log/info "Starting" (:workers config) "workers...")
    (http/with-connection-pool {:timeout 5
                                :threads (:workers config)
                                :insecure? true}
      (dotimes [_ (:workers config)]
        (start-worker config))
      (log/info "Starting crawl of" (:url config))
      (enqueue-url config (:url config)))
    config))

(defn palm
  "It's a HAND-ler.. get it? That was a terrible pun."
  [{:keys [url body]}]
  (println :url url :size (count body)))

(defn noop
  "handler that does nothing"
  [& _]
  nil)

(defn my-handler [{:keys [url body]}]
  (println url "has a count of" (count body)))

(def cli-options
  [["-f" "--file FILE" "Configuration File"
    :default "config"
    ;:parse-fn #(Integer/parseInt %)
    ;:validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]
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
        ]
    (log/info "ready to crawl " (:url conf))
    (crawl conf)
    ))
