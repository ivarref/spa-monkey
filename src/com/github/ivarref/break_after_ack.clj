(ns com.github.ivarref.break-after-ack
  (:require
    [babashka.process :refer [$ check]]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [com.github.ivarref.hookd :as hookd]
    [com.github.ivarref.log-init :as log-init]
    [com.github.ivarref.spa-monkey :as spa-monkey]
    [com.github.ivarref.utils :as u]
    [datomic.cluster :as cluster]
    [nrepl.server :as nrepl])
  (:import
    (java.net Socket)
    (java.sql Connection)
    (javax.sql PooledConnection)
    (org.postgresql.core PGStream QueryExecutor QueryExecutorBase)
    (org.postgresql.jdbc PgConnection)))

(defn conn->socket [^PooledConnection conn]
  (when (instance? PooledConnection conn)
    (let [^PgConnection conn (.getConnection conn)
          ^QueryExecutor qe (.getQueryExecutor conn)
          field (.getDeclaredField QueryExecutorBase "pgStream")
          _ (.setAccessible field true)
          ^PGStream stream (.get field qe)]
      (.getSocket stream))))

(defn accept! []
  (log/info "Clear all packet filters")
  (as-> ^{:out :string :err :string} ($ ./accept) v
        (check v)))

(defn read-segment [conn val-key]
  (let [cluster (.get-cluster conn)]
    @(cluster/get-val cluster val-key)))

(defn drop-str! [s]
  (spit "drop.txt" s)
  (as-> ^{:out :string :err :string} ($ "/usr/sbin/nft" -f ./drop.txt) v
        (check v)))

(defn sock->readable [sock]
  (str "127.0.0.1:" (.getLocalPort sock)
       "->"
       "127.0.0.1:" (.getPort sock)))

(defn sock->drop [^Socket s]
  (str "tcp dport " (.getPort s) " "
       "tcp sport " (.getLocalPort s) " "
       "ip saddr 127.0.0.1 "
       "ip daddr 127.0.0.1 drop;"))

(defn drop-sock! [sock]
  (let [drop-txt (sock->drop sock)]
    (log/info "Dropping TCP packets for" (spa-monkey/ports-str sock))
    (drop-str! (str/join "\n"
                         ["flush ruleset"
                          "table ip filter {"
                          "chain output {"
                          "type filter hook output priority filter;"
                          "policy accept;"
                          drop-txt
                          "}"
                          "}"]))))

(defonce conn-pool (atom nil))

(defonce monkey (atom {:remote-host "localhost"
                       :remote-port 5432
                       :port        54321}))

(defonce pool-socket-promise (promise))
(defonce drop-sock (atom nil))

(defn do-test-inner [_]
  (hookd/install-return-consumer!
    "org.apache.tomcat.jdbc.pool.ConnectionPool"
    "::Constructor"
    (partial reset! conn-pool))
  (when-let [e (spa-monkey/start! monkey)]
    (throw e))
  (let [conn (u/get-conn :port 54321)
        get-conn-count (atom 0)]
    (hookd/install-return-consumer!
      "org.apache.tomcat.jdbc.pool.ConnectionPool"
      "getConnection"
      (fn [^Connection conn]
        (let [^Socket sock (conn->socket conn)]
          (when (= 1 (swap! get-conn-count inc))
            (deliver pool-socket-promise sock)
            (log/info "ConnectionPool/getConnection returning socket" (spa-monkey/ports-str sock))))))
    (spa-monkey/add-handler!
      monkey
      (fn [{:keys [op dst]}]
        (when (and (= op :recv)
                   (realized? pool-socket-promise)
                   (= (spa-monkey/ports dst :reverse? true)
                      (spa-monkey/ports @pool-socket-promise)))
          (drop-sock! dst)
          (reset! drop-sock dst)
          :pop)))
    (let [start-time (System/currentTimeMillis)
          done? (promise)]
      (log/info "Starting read-segment on (will be) blocked connection ...")
      (u/start-tick-thread pool-socket-promise done?)
      (read-segment conn "854f8149-7116-45dc-b3df-5b57a5cd1e4e")
      (deliver done? :done)
      (let [stop-time (System/currentTimeMillis)]
        (log/info "Reading on blocked connection ... Done in" (log-init/ms->duration (- stop-time start-time)))))))

(defn do-test! [{:keys [block?] :as opts}]
  (try
    (log-init/init-logging! {:log-file "break-after-ack"
                             :levels   [[#{"datomic.*"} :warn]
                                        [#{"com.github.ivarref.*"} :info]
                                        [#{"*"} :info]]})
    (accept!)
    (when block?
      (log/info "Starting nREPL server ....")
      (nrepl/start-server :bind "127.0.0.1" :port 7777))
    (do-test-inner opts)
    (when block?
      @(promise))
    (finally
      (accept!)
      (spa-monkey/stop! monkey))))
