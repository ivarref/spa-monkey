(ns com.github.ivarref.break-single-conn-once
  (:require
    [babashka.process :refer [$ check]]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [com.github.ivarref.hookd :as hookd]
    [com.github.ivarref.log-init :as log-init]
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

(defn read-segment [conn val-key]
  (let [cluster (.get-cluster conn)]
    @(cluster/get-val cluster val-key)))

(defn accept! []
  (log/debug "Start accepting all packets")
  (as-> ^{:out :string :err :string} ($ ./accept) v
        (check v)))

(defn sock->drop [^Socket s]
  (str "tcp dport " (.getPort s) " "
       "tcp sport " (.getLocalPort s) " "
       "ip saddr 127.0.0.1 "
       "ip daddr 127.0.0.1 drop;"))

(defn drop-str! [s]
  (spit "drop.txt" s)
  (as-> ^{:out :string :err :string} ($ "/usr/sbin/nft" -f ./drop.txt) v
        (check v)))

(defn drop-sock! [sock]
  (let [drop-txt (sock->drop sock)]
    (log/info "Dropping TCP packets for"
              (str "127.0.0.1:" (.getLocalPort sock))
              "->"
              (str "127.0.0.1:" (.getPort sock)))
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

(defn do-test! [{:keys [block?] :as opts}]
  (try
    (log-init/init-logging! (merge opts
                                   {:levels [[#{"datomic.*"} :debug]
                                             [#{"com.github.ivarref.*"} :info]
                                             [#{"*"} :info]]}))
    (accept!)
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
              (drop-sock! sock)
              (log/info "Not dropping anything for" sock)))))
      (let [start-time (System/currentTimeMillis)
            done-read? (promise)]
        (log/info "Starting read-segment on blocked connection ...")
        (future
          (loop [uptime (int (/ (log-init/jvm-uptime-ms) 60000))
                 v 0]
            (when-not (realized? done-read?)
              (let [timeout? (true? (deref done-read? 1000 true))
                    new-uptime (int (/ (log-init/jvm-uptime-ms) 60000))]
                (when timeout?
                  (if (not= uptime new-uptime)
                    (do
                      (log/info (if (even? v) "tick" "tack"))
                      (recur new-uptime (inc v)))
                    (recur uptime v)))))))
        (read-segment conn "854f8149-7116-45dc-b3df-5b57a5cd1e4e")
        (deliver done-read? :done)
        (let [stop-time (System/currentTimeMillis)]
          (log/info "Reading on blocked connection ... Done in" (log-init/ms->duration (- stop-time start-time))))))
    (when block?
      @(promise))
    (finally
      (accept!))))
