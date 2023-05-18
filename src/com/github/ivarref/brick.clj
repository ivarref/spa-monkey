(ns com.github.ivarref.brick
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [com.github.ivarref.hookd :as hookd]
            [com.github.ivarref.log-init :as log-init]
            [com.github.ivarref.nft :as nft]
            [com.github.ivarref.spa-monkey :as monkey]
            [com.github.ivarref.utils :as u]
            [datomic.api :as d]
            [nrepl.server :as nrepl]
            [taoensso.timbre :as timbre]
            [wiretap.wiretap :as w])
  (:import (datomic Connection)
           (java.lang ProcessHandle)
           (java.net Socket)
           (java.time Duration)))

(defn brick-init! [{:keys []}]
  (try
    (log-init/init-logging! {:log-file  "brick"
                             :min-level [[#{"datomic.*"
                                            "com.github.ivarref.spa-monkey"
                                            "com.github.ivarref.nft"} :warn]
                                         [#{"com.github.ivarref.*"} :info]
                                         [#{"*"} :info]]})
    (let [^Connection conn (u/get-conn :db-name "brick" :port 5432 :delete? true)]
      ;(log/info "got connection" conn)
      @(d/transact conn [#:db{:ident :e/id, :cardinality :db.cardinality/one, :valueType :db.type/string, :unique :db.unique/identity}
                         #:db{:ident :e/info, :cardinality :db.cardinality/one, :valueType :db.type/string}])
      @(d/transact conn [{:e/id "demo1" :e/info "info1"}])
      @(d/transact conn [{:e/id "demo2" :e/info "info2"}])
      (.release conn))
    (catch Throwable t
      (log/error t "Unexpected error:" (ex-message t))
      (System/exit 1))
    (finally
      (log/info "brick-init exiting"))))

(defonce conn-pool (atom nil))
(defonce datomic-conn (atom nil))
(defonce proxy-state (atom {:remote-host "localhost"
                            :remote-port 5432
                            :port        54321}))
(defonce blocked-thread (atom nil))

; is the lock block per segment, or global somehow?

(defn brick [{:keys [block? brick? tick-rate-ms]}]
  (try
    (log-init/init-logging! {:log-file  "brick"
                             :min-level [[#{"datomic.*" "com.github.ivarref.spa-monkey"} :warn]
                                         [#{"com.github.ivarref.*"} :info]
                                         [#{"*"} :info]]})
    (let [tcp-retry-file "/proc/sys/net/ipv4/tcp_retries2"]
      (log/info tcp-retry-file "is" (str/trim (slurp tcp-retry-file))))
    (log/info "PID is" (.pid (ProcessHandle/current)))
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
          (u/named-future "pull-demo-1"
                          (try
                            (log/info "Starting pull" [:e/id "demo1"] "on dropped connection ...")
                            ; blocks/drops on kv-cluster/val 6436706a-911c-4547-b305-a7c82d49620e
                            (let [result (d/pull (d/db conn) [:*] [:e/id "demo1"])]
                              (log/info "Got pull result" result))
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
              fut (u/named-future "pull-demo-2"
                                  (reset! blocked-thread (Thread/currentThread))
                                  (try
                                    (d/q '[:find (pull ?e [:*])
                                           :in $
                                           :where
                                           [?e :db/doc ?doc]]
                                         (d/db conn))
                                    (log/info "OK :db/doc query")
                                    (log/info "Start pull" [:e/id "demo2"])
                                    (let [v (d/pull (d/db conn) [:*] [:e/id "demo2"])]
                                      (log/info "Done pull" [:e/id "demo2"])
                                      v)
                                    (catch Throwable t
                                      (log/error "Error:" (ex-message t)))))]
          (loop []
            (Thread/sleep ^long (or tick-rate-ms (if brick? 15000 1000)))
            (when (not (realized? fut))
              (log/info "Waited for pull" [:e/id "demo2"] "for" (str (Duration/ofMillis (- (System/currentTimeMillis)
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
