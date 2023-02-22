(ns com.github.ivarref.tcp-retry
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [com.github.ivarref.hookd :as hookd]
    [com.github.ivarref.log-init :as log-init]
    [com.github.ivarref.nft :as nft]
    [com.github.ivarref.utils :as u]
    [datomic.api :as d]
    [nrepl.server :as nrepl]
    [taoensso.timbre :as timbre])
  (:import
    (com.github.ivarref GetSockOpt)
    (java.net Socket)
    (java.sql Connection)
    (javax.sql PooledConnection)
    (org.postgresql.core PGStream QueryExecutor QueryExecutorBase)
    (org.postgresql.jdbc PgConnection)))

; sudo bash -c 'echo 6 > /proc/sys/net/ipv4/tcp_retries2'

; my machine's (Linux 5.15.91-1-MANJARO #1 SMP PREEMPT Wed Feb 1 12:03:19 UTC 2023 x86_64 GNU/Linux)
; default is 15:
; $ cat /proc/sys/net/ipv4/tcp_retries2
; 15

; sudo bash -c 'echo 15 > /proc/sys/net/ipv4/tcp_retries2'
; sdk default java 17.0.4.1-tem

(defn conn->socket [^PooledConnection conn]
  (when (instance? PooledConnection conn)
    (let [^PgConnection conn (.getConnection conn)
          ^QueryExecutor qe (.getQueryExecutor conn)
          field (.getDeclaredField QueryExecutorBase "pgStream")
          _ (.setAccessible field true)
          ^PGStream stream (.get field qe)]
      (.getSocket stream))))

(defonce conn-pool (atom nil))

(defn tick-tack-loop [done-read?]
  (loop [uptime (int (/ (log-init/jvm-uptime-ms) 60000))
         v 0]
    (when-not (realized? done-read?)
      (let [timeout? (true? (deref done-read? 1000 true))
            new-uptime (int (/ (log-init/jvm-uptime-ms) 60000))]
        (when timeout?
          (if (not= uptime new-uptime)
            (do
              (log/info (if (even? v) "Tick" "Tack"))
              (recur new-uptime (inc v)))
            (recur uptime v)))))))

(defn get-state [sock]
  (-> (into (sorted-map) (GetSockOpt/getTcpInfo sock))
      (assoc "open?" (not (.isClosed sock)))))

(defn with-clock [state now-ms]
  (reduce-kv (fn [o k v]
               (assoc o k [v now-ms]))
             {}
             state))

(defn no-clock [state]
  (reduce-kv (fn [o k [v _now-ms]]
               (assoc o k v))
             {}
             state))

(defn watch-socket! [^Socket sock]
  (future
    (let [org-name (.getName (Thread/currentThread))]
      (.setName (Thread/currentThread) "socket-watcher")
      (try
        (let [initial-state (get-state sock)
              fd (GetSockOpt/getFd sock)]
          (log/info "Initial state for fd" fd initial-state)
          (loop [prev-state (with-clock initial-state (System/currentTimeMillis))]
            (Thread/sleep 5)
            (let [now-ms (System/currentTimeMillis)
                  {:strs [open?] :as new-state} (get-state sock)]
              (if (not= new-state (no-clock prev-state))
                (do
                  (doseq [[new-k new-v] new-state]
                    (when (and (not= new-v (first (get prev-state new-k)))
                               (not (contains? #{"tcpi_last_ack_recv"
                                                 "tcpi_last_ack_sent"
                                                 "tcpi_last_data_recv"
                                                 "tcpi_last_data_sent"
                                                 "tcpi_probes"}
                                               new-k)))
                      (let [ms-diff (- now-ms (second (get prev-state new-k)))]
                        (log/info "fd" fd new-k (first (get prev-state new-k)) "=>" new-v (str "(In " ms-diff " ms)")))))
                  (when open?
                    (recur (reduce-kv (fn [o k [old-v _old-ms :as old-val]]
                                        (if (not= old-v (get new-state k))
                                          (assoc o k [(get new-state k) now-ms])
                                          (assoc o k old-val)))
                                      {}
                                      prev-state))))
                (when open?
                  (recur prev-state))))))
        (catch Throwable t
          (if (.isClosed sock)
            (log/warn "Error in socket watcher for fd" (GetSockOpt/getFd sock) ", message:" (ex-message t))
            (log/error "Error in socket watcher for fd" (GetSockOpt/getFd sock) ", message:" (ex-message t))))
        (finally
          (.setName (Thread/currentThread) org-name))))))

(defn do-test! [{:keys [block?] :as opts}]
  (try
    (log-init/init-logging! (merge opts
                                   {:levels [[#{"datomic.*"} :warn]
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
          drop-count (atom 0)]
      (hookd/install-return-consumer!
        "org.apache.tomcat.jdbc.pool.ConnectionPool"
        "getConnection"
        (fn [^Connection conn]
          (let [^Socket sock (conn->socket conn)]
            (if (= 1 (swap! drop-count inc))
              (do
                (nft/drop-sock! sock)
                (watch-socket! sock)
                #_(log/info "Blocked socket:" (tcp-info @blocked-socket)))
              (log/info "Not dropping anything for" (nft/sock->readable sock))))))
      (let [start-time (System/currentTimeMillis)
            done-read? (promise)]
        (timbre/merge-config! {:min-level [[#{"datomic.*"} :debug]
                                           [#{"com.github.ivarref.*"} :info]
                                           [#{"*"} :info]]})
        (log/info "Starting query on blocked connection ...")
        (future (tick-tack-loop done-read?))
        (let [result (d/q '[:find ?e ?doc
                            :in $
                            :where
                            [?e :db/doc ?doc]]
                          (d/db conn))]
          (log/debug "Got query result" result))
        (deliver done-read? :done)
        (let [stop-time (System/currentTimeMillis)]
          (log/info "Query on blocked connection ... Done in" (log-init/ms->duration (- stop-time start-time))
                    "aka" (int (- stop-time start-time)) "milliseconds")
          (log/info "Waiting 90 seconds for datomic.process-monitor")
          (Thread/sleep 90000))))                           ; Give datomic time to report StorageGetMsec
    (when block?
      @(promise))
    (catch Throwable t
      (log/error t "Unexpected exception:" (ex-message t)))
    (finally
      (nft/accept!))))
