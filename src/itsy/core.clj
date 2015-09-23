(ns itsy.core
  "Tool used to crawl web pages with an aritrary handler."
  (:require [clojure.tools.cli :refer [parse-opts]]
            [clojure.string :as s]
            [clojure.set :as set]
            [clojure.tools.logging :as log]
            [clojure.set :as set]
            ;[cemerick.url :refer [url]]
            [clj-http.client :as http]
            [itsy.robots :as robots]
            [itsy.config :as c]
            [itsy.url :as u]
            [itsy.threads :as t])
  (:import [java.util.concurrent
            TimeUnit Executors ExecutorService])
  (:gen-class))

(def pool (Executors/newFixedThreadPool
           (.availableProcessors (Runtime/getRuntime))))

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
  "Enqueue a collection of urls which had not been seen or
  not bad"
  [config {:keys [:to :from] :as urls}]
  (let [seen-urls (set (keys @(:seen-urls (:state config))))
        bad-urls @(:404-urls (:state config))]
    (doseq [url (set/difference to seen-urls bad-urls)]
      (enqueue-url config {:to url :from from}))))

(declare add-worker)
(declare spawn-workers)

(defn dequeue
  [config url worker]
  (log/debug "#dequeue:" url)
  (swap! (:seen-urls (:state config))
         dissoc url)
  (swap! (:404-urls (:state config))
         conj url)
  (let [p (promise)
        c config
        w worker]
    (future (spawn-workers c)
            (deliver p true))
    (when @p
      (log/debug "#dequeued")
      (t/interrupt w))))

(defn- crawl-pageb
  "Internal crawling function that fetches a page, 
  enqueues url found on that page and calls the handler 
  with the page body."
  [config url-map]
  (let [url (:url url-map)]
    (try
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
     (catch Exception e
       (log/debug e)
       (dequeue config url (t/current))))))


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
        (let [state (:state config)
              limit-reached
              (and (pos? (:url-limit config))
                   (>= @(:url-count state) (:url-limit config))
                   zero? (.size (:url-queue state)))]
          (when limit-reached
            (log/debug (str "url limit reached: ("
                            @(:url-count state)
                        "/" (:url-limit config)
                        "), terminating myself")))
          (when (not limit-reached)
            (recur)))))))


(defn start-worker
  "Start a worker thread for a config object, updating the 
  config's state with the new Thread object."
  [config]
  (let [w-thread (t/spawn (worker-fn config))
        _ (t/named w-thread (str "itsy-worker-"
                                (t/named w-thread)))
        w-tid (t/id w-thread)]
    (dosync
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
     (log/info "Waiting for workers to finish...")
     (map #(t/join % 30000)
          @(-> config :state :running-workers))
     (t/sleep 10000)
     (if (every? #(= Thread$State/TERMINATED %)
                 (vals (thread-status config)))
       (do
         (log/info "All workers stopped.")
         (ref-set (-> config :state :running-workers) []))
       (do
         (log/warn "Unable to stop all workers.")
         (ref-set (:running-workers (:state config))
                  (remove
                   #(= t/TERMINATED (t/state %))
                   @(:running-workers (:state config))))))))
  @(-> config :state :running-workers))


(defn add-worker
  "Given a config object, add a worker to the pool, 
  returns the new worker count."
  [config]
  (log/info "Adding one additional worker to the pool")
  (start-worker config)
  (count @(-> config :state :running-workers)))

(defn spawn-workers
  "Given a config object, remove a worker from the pool, 
  returns the new worker count."
  [config worker]
  (log/debug "removing one worker from the pool")
  (dosync
   (when-let [ws (:running-workers (:state config))]
     (let [remains (remove #(or (= t/TERMINATED (t/state %))
                                (= t/WAITING (t/state %))) @ws)
           subs (- (:workers config) (count remains))]
       (when (> subs 0)
         (dotimes [i subs]
           (add-worker config)))
       (ref-set (-> config :state :running-workers)
                (remove #(= t/TERMINATED (t/state %)) @ws)))))
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

(defn- enqueue1
  [config urls]
  (dosync (let [q (:queue (:state config))
                bad (:bad-urls (:state config))
                seen (:seen-urls (:state config))
                new (set/difference urls @bad @seen)]
            (alter q set/union new))))

(defn- dequeue1
  [config]
  (dosync (let [q (:queue (:state config))
                n0 (first @q)]
            (alter q set/difference #{n0})
            n0)))

(defn- trash
  [config url]
  (dosync (let [bad (:bad-urls (:state config))]
            (alter bad conj url))))

(defn- seen-url
  [config url]
  (dosync (let [seen (:seen-urls (:state config))]
            (alter seen set/union #{url}))))

(defn- crawling1
  [config pool]
  (let [url (dequeue1 config)
        page (try
               (http/get url (:http-opts config))
               (catch Exception e
                 (log/error e)))]
    (if-let [body (:body page)]
      (let [urls (u/extract-all url body)]
        (seen-url config url)
        (enqueue1 config urls))
      (trash config url))
    (dosync (let [q (:queue (:state config))]
              (when (< (.getActiveCount pool)
                       (count @q))
                (new-crawl-task pool
                                #(crawling1 config pool)))))))

(defn- new-crawl-task
  [^ExecutorService pool
   ^Runnable task]
  (.submit pool task))

(defn- crawl1
  [config pool]
  (when-let [url (:url config)]
    (enqueue1 config #{url})
    (new-crawl-task pool #(crawling1 config pool))
    url))

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
    ;(crawl conf)
    (crawl1 conf pool)
    ))
