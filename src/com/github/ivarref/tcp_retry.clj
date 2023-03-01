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
    (java.net Socket)
    (java.sql Connection)))

; sudo bash -c 'echo 6 > /proc/sys/net/ipv4/tcp_retries2'

; my machine's (Linux 5.15.91-1-MANJARO #1 SMP PREEMPT Wed Feb 1 12:03:19 UTC 2023 x86_64 GNU/Linux)
; default is 15:
; $ cat /proc/sys/net/ipv4/tcp_retries2
; 15

; sudo bash -c 'echo 15 > /proc/sys/net/ipv4/tcp_retries2'
; sdk default java 17.0.4.1-tem

(defonce conn-pool (atom nil))

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
                        (u/watch-socket! running? sock))
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
        (log/info "Waiting 90 seconds for datomic.process-monitor")
        (Thread/sleep 90000)))                              ; Give datomic time to report StorageGetMsec
    (when block?
      @(promise))
    (catch Throwable t
      (log/error t "Unexpected exception:" (ex-message t)))
    (finally
      (try
        (nft/accept!)
        (catch Throwable t
          (log/error t "Error during nft/accept:" (ex-message t))))
      (log/info "Exiting"))))

(defn forever [{:keys [block?]}]
  (try
    (log-init/init-logging! {:log-file  "forever"
                             :min-level [[#{"datomic.*"} :warn]
                                         [#{"com.github.ivarref.*"} :info]
                                         [#{"*"} :info]]})
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
        (log/info "Waiting 90 seconds for datomic.process-monitor")
        (Thread/sleep 90000)))                              ; Give datomic time to report StorageGetMsec
    (when block?
      @(promise))
    (catch Throwable t
      (log/error t "Unexpected exception:" (ex-message t)))
    (finally
      (try
        (nft/accept!)
        (catch Throwable t
          (log/error t "Error during nft/accept:" (ex-message t))))
      (log/info "Exiting"))))
