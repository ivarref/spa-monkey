(ns com.github.ivarref.tcp-retry
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [com.github.ivarref.hookd :as hookd]
    [com.github.ivarref.log-init :as log-init]
    [com.github.ivarref.nft :as nft]
    [com.github.ivarref.utils :as u]
    [com.github.ivarref.spa-monkey :as monkey]
    [datomic.api :as d]
    [nrepl.server :as nrepl]
    [taoensso.timbre :as timbre])
  (:import
    (java.lang ProcessHandle)
    (java.net Socket)
    (java.sql Connection)
    (java.time Duration)
    (java.util.concurrent CountDownLatch)))

; sudo bash -c 'echo 6 > /proc/sys/net/ipv4/tcp_retries2'

; my machine's (Linux 5.15.91-1-MANJARO #1 SMP PREEMPT Wed Feb 1 12:03:19 UTC 2023 x86_64 GNU/Linux)
; default is 15:
; $ cat /proc/sys/net/ipv4/tcp_retries2
; 15

; sudo bash -c 'echo 15 > /proc/sys/net/ipv4/tcp_retries2'
; sdk default java 20.ea.34-open

(defonce conn-pool (atom nil))

(defonce datomic-conn (atom nil))

(defn do-test! [{:keys [block?] :as opts}]
  (try
    (log-init/init-logging! (merge opts
                                   {:min-level [[#{"datomic.*"} :warn]
                                                [#{"com.github.ivarref.*"} :info]
                                                [#{"*"} :info]]}))
    (log/debug "user.name is" (System/getProperty "user.name"))
    (let [tcp-retry-file "/proc/sys/net/ipv4/tcp_retries2"]
      (log/info tcp-retry-file "is" (str/trim (slurp tcp-retry-file))))
    (nft/accept!)
    (hookd/install-return-consumer!
      "org.apache.tomcat.jdbc.pool.ConnectionPool"
      "::Constructor"
      (partial reset! conn-pool))
    (when block?
      (log/info "Starting nREPL server ....")
      (nrepl/start-server :bind "127.0.0.1" :port 7777))
    (let [conn (u/get-conn)
          drop-count (atom 0)
          running? (atom true)
          _ (hookd/install-return-consumer!
              "org.apache.tomcat.jdbc.pool.ConnectionPool"
              "getConnection"
              (fn [^Connection conn]
                (try
                  (log/info "connection is" conn)
                  (let [^Socket sock (u/conn->socket conn)]
                    (if (= 1 (swap! drop-count inc))
                      (do
                        (nft/drop-sock! sock)
                        (u/watch-socket! "client" running? sock))
                      (log/info "Not dropping anything for" (nft/sock->readable sock))))
                  (catch Throwable t
                    (log/error t "Unexpected error in getConnection:" (ex-message t))
                    (System/exit 1)
                    (throw t)))))
          start-time (System/currentTimeMillis)]
      (timbre/merge-config! {:min-level [[#{"datomic.*"} :debug]
                                         [#{"com.github.ivarref.*"} :info]
                                         [#{"*"} :info]]})
      (log/info "Starting query on blocked connection ...")
      (let [result (d/q '[:find ?e ?doc
                          :in $
                          :where
                          [?e :db/doc ?doc]]
                        (d/db conn))]
        (log/debug "Got query result" result))
      (Thread/sleep 100)
      (reset! running? false)
      (let [stop-time (System/currentTimeMillis)]
        (log/info "Query on blocked connection ... Done in" (log-init/ms->duration (- stop-time start-time))
                  "aka" (int (- stop-time start-time)) "milliseconds")
        (log/info "Waiting 70 seconds for datomic.process-monitor")
        (Thread/sleep 70000)))                              ; Give datomic time to report StorageGetMsec
    (when block?
      @(promise))
    (catch Throwable t
      (log/error t "Unexpected exception:" (ex-message t)))
    (finally
      (nft/accept! :throw? false)
      (log/info "Exiting"))))

(defonce proxy-state (atom {:remote-host "localhost"
                            :remote-port 5432
                            :port        54321}))

; cat logs/forever.log.json | jq -r -c '.thread' | sort | uniq
;CLI-agent-send-off-pool-7
;client-socket-watcher
;Datomic Metrics Reporter
;main
;proxy-socket-watcher
;ThreadGroup-1-recv
;ThreadGroup-1-send
;ThreadGroup-2-recv
;ThreadGroup-3-recv
;ThreadGroup-4-recv
; cat logs/forever.log.json | jq -r -c 'select( .thread == "CLI-agent-send-off-pool-7")'
; cat logs/forever.log.json | jq -r -c 'select( .thread == "proxy-socket-watcher") | .uptime + " " + .message'
; cat logs/forever.log.json | jq -r -c 'select( .thread == "client-socket-watcher") | .uptime + " " + .message'

(defn forever [{:keys [block?]}]
  (try
    (log-init/init-logging! {:log-file  "forever"
                             :min-level [[#{"datomic.*"} :warn]
                                         [#{"com.github.ivarref.*"} :info]
                                         [#{"*"} :info]]})
    (let [tcp-retry-file "/proc/sys/net/ipv4/tcp_retries2"]
      (log/info tcp-retry-file "is" (str/trim (slurp tcp-retry-file))))
    (log/info "PID is:" (.pid (ProcessHandle/current)))
    (nft/accept!)
    (hookd/install-return-consumer!
      "org.apache.tomcat.jdbc.pool.ConnectionPool"
      "::Constructor"
      (partial reset! conn-pool))
    (when block?
      (log/info "Starting nREPL server ....")
      (nrepl/start-server :bind "127.0.0.1" :port 7777))
    (when-let [e (monkey/start! proxy-state)]
      (log/error e "Could not start proxy:" (ex-message e))
      (System/exit 1))
    (let [conn (u/get-conn :port 54321)
          running? (atom true)
          get-conn-count (atom 0)
          start-time (System/currentTimeMillis)]
      (hookd/install-return-consumer!
        "org.apache.tomcat.jdbc.pool.ConnectionPool"
        "getConnection"
        (fn [^Connection conn]
          (let [^Socket sock (u/conn->socket conn)]
            (when (= 1 (swap! get-conn-count inc))
              (log/info "ConnectionPool/getConnection returning socket" (nft/sock->readable sock))
              (u/watch-socket! "client" running? sock)))))
      (monkey/add-handler!
        proxy-state
        (fn [{:keys [op dst]}]
          (when (= op :recv)
            (Thread/sleep 500)                              ; make sure ACKs have arrived at the other side
            (nft/drop-sock! dst)
            (u/watch-socket! "proxy" running? dst)
            :pop)))
      (timbre/merge-config! {:min-level [[#{"datomic.*"} :debug]
                                         [#{"com.github.ivarref.*"} :info]
                                         [#{"*"} :info]]})
      (log/info "Starting query on blocked connection ...")
      (let [result (d/q '[:find ?e ?doc
                          :in $
                          :where
                          [?e :db/doc ?doc]]
                        (d/db conn))]
        (log/debug "Got query result" result))
      (Thread/sleep 100)
      (reset! running? false)
      (let [stop-time (System/currentTimeMillis)]
        (log/info "Query on blocked connection ... Done in" (log-init/ms->duration (- stop-time start-time))
                  "aka" (int (- stop-time start-time)) "milliseconds")
        (log/info "Waiting 70 seconds for datomic.process-monitor")
        (Thread/sleep 70000)))                              ; Give datomic time to report StorageGetMsec
    (when block?
      @(promise))
    (catch Throwable t
      (log/error t "Unexpected exception:" (ex-message t)))
    (finally
      (monkey/stop! proxy-state)
      (nft/accept! :throw? false)
      (log/info "Exiting"))))

(defonce blocked-thread (atom nil))

(defn brick-init! [{:keys []}]
  (try
    (log-init/init-logging! {:log-file  "brick"
                             :min-level [[#{"datomic.*" "com.github.ivarref.spa-monkey"} :warn]
                                         [#{"com.github.ivarref.*"} :info]
                                         [#{"*"} :info]]})
    (let [^datomic.Connection conn (u/get-conn :db-name "brick" :port 5432 :delete? true)]
      (log/info "got connection" conn)
      @(d/transact conn [#:db{:ident :e/id, :cardinality :db.cardinality/one, :valueType :db.type/string, :unique :db.unique/identity}
                         #:db{:ident :e/info, :cardinality :db.cardinality/one, :valueType :db.type/string}])
      @(d/transact conn [{:e/id "demo1" :e/info "info1"}])
      @(d/transact conn [{:e/id "demo2" :e/info "info2"}])
      (.close conn))
    (catch Throwable t
      (log/error t "Unexpected error:" (ex-message t))
      (System/exit 1))
    (finally
      (log/info "brick-init exiting"))))

(defn brick [{:keys [block? brick? tick-rate-ms]}]
  (try
    (log-init/init-logging! {:log-file  "brick"
                             :min-level [[#{"datomic.*" "com.github.ivarref.spa-monkey"} :warn]
                                         [#{"com.github.ivarref.*"} :info]
                                         [#{"*"} :info]]})
    (let [tcp-retry-file "/proc/sys/net/ipv4/tcp_retries2"]
      (log/info tcp-retry-file "is" (str/trim (slurp tcp-retry-file))))
    (log/info "PID is:" (.pid (ProcessHandle/current)))
    (nft/accept!)
    (hookd/install-return-consumer!
      "org.apache.tomcat.jdbc.pool.ConnectionPool"
      "::Constructor"
      (partial reset! conn-pool))
    (log/info "Starting nREPL server ....")
    (nrepl/start-server :bind "127.0.0.1" :port 7777)
    (when-let [e (monkey/start! proxy-state)]
      (log/error e "Could not start proxy:" (ex-message e))
      (System/exit 1))
    (let [conn (u/get-conn :db-name "brick" :port 54321)]
      (reset! datomic-conn conn)
      (hookd/install-return-consumer!
        "org.apache.tomcat.jdbc.pool.ConnectionPool"
        "getConnection"
        (fn [^Connection conn]
          (let [^Socket sock (u/conn->socket conn)]
            #_(when (<= 10 (swap! get-conn-count inc)))
            (log/info "ConnectionPool/getConnection returning socket" (nft/sock->readable sock))
            #_(u/watch-socket! "client" running? sock))))
      (let [dropped-sockets (atom #{})]
        (monkey/add-handler!
          proxy-state
          (fn [{:keys [op dst]}]
            (try
              (locking dropped-sockets
                (when (and (= op :recv)
                           (= 0 (count @dropped-sockets)))
                  (Thread/sleep 500)                        ; make sure ACKs have arrived at the other side
                  (let [new-blocked (swap! dropped-sockets conj dst)]
                    (if brick?
                      (nft/drop-sockets! new-blocked)
                      (log/info "not dropping socket"))
                    #_(log/info "Blocked socket count:" (count new-blocked)))
                  #_(u/watch-socket! "proxy" running? dst)))
              (catch Throwable t
                (log/error "unexpected error:" (ex-message t))))))
        (timbre/merge-config! {:min-level [[#{"datomic.kv-cluster"} :debug]
                                           [#{"datomic.process-monitor"
                                              "com.github.ivarref.spa-monkey"} :warn]
                                           [#{"datomic.*"} :info]
                                           [#{"com.github.ivarref.nft"} :warn]
                                           [#{"com.github.ivarref.*"} :info]
                                           [#{"*"} :info]]})
        (when brick?
          (future
            (try
              (log/info "Starting query on dropped connection ...")
              ; blocks/drops on kv-cluster/val 6436706a-911c-4547-b305-a7c82d49620e
              (let [result (d/pull (d/db conn) [:*] [:e/id "demo1"])]
                (log/info "Got query result" result))
              (Thread/sleep 1000)
              (catch Throwable t
                (log/error "an exception at last:" (ex-message t)))
              (finally
                nil)))
          (loop []
            (Thread/sleep 1000)
            (when (= 0 (count @dropped-sockets))
              (log/info "Waiting for drop")
              (recur))))
        (log/info (count @dropped-sockets) "dropped socket")
        (let [start-time (System/currentTimeMillis)
              fut (future
                    (reset! blocked-thread (Thread/currentThread))
                    (try
                      #_(d/q '[:find (pull ?e [:*])
                               :in $
                               :where
                               [?e :db/doc ?doc]]
                             (d/db conn))
                      (d/pull (d/db conn) [:*] [:e/id "demo2"])
                      (catch Throwable t
                        (log/error "Error:" (ex-message t)))))]
          (loop []
            (Thread/sleep ^long (or tick-rate-ms (if brick? 15000 1000)))
            (when (not (realized? fut))
              (log/info "Waited for non-dropped query for" (str (Duration/ofMillis (- (System/currentTimeMillis)
                                                                                      start-time))))
              (recur)))
          (log/info "future was" @fut)))
      (when block?
        @(promise)))
    (catch Throwable t
      (log/error t "Unexpected exception:" (ex-message t)))
    (finally
      (monkey/stop! proxy-state)
      (nft/accept! :throw? false)
      (log/info "Exiting"))))

(defmacro maybe-timeout [& body]
  `(let [thread# (atom nil)
         fut# (future (try
                        (reset! thread# (Thread/currentThread))
                        ~@body
                        (catch Throwable t#
                          (log/error t# "Unexpected error:" (ex-message t#)))))]
     (let [v# (deref fut# 5000 ::timeout)]
       (if (= v# ::timeout)
         [fut# @thread#]
         [v# nil]))))

(comment
  (let [v (first (maybe-timeout
                   (d/pull (d/db @datomic-conn) [:*] :db/txInstant)))]
    v))

(comment
  (doseq [id (sort (d/q '[:find [?id ...]
                          :in $
                          :where
                          [?e :db/ident ?id]]
                        (d/db @datomic-conn)))]
    (println id)
    (println (d/pull (d/db @datomic-conn) [:*] id))))

(comment
  (maybe-timeout (d/q '[:find ?tx
                        :in $
                        :where
                        [?e :db/txInstant ?tx]]
                      (d/db @datomic-conn))))

(def ^:private current-dir-prefix
  "Convert the current directory (via property 'user.dir') into a prefix to be omitted from file names."
  (delay (str (System/getProperty "user.dir") "/")))

(def expand-stack-trace-element (resolve 'io.aviso.exception/expand-stack-trace-element))

(comment
  (+ 1 2))
(comment
  (take 5 (map (partial expand-stack-trace-element @current-dir-prefix) (.getStackTrace @blocked-thread))))
