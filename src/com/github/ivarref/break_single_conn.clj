(ns com.github.ivarref.break-single-conn
  (:require
    [babashka.process :refer [$ check]]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [com.github.ivarref.hookd :as hookd]
    [com.github.ivarref.log-init :as log-init]
    [datomic.api :as d]
    [datomic.cluster :as cluster]
    [nrepl.server :as nrepl])
  (:import
    (java.net Socket)
    (java.sql Connection)
    (javax.sql PooledConnection)
    (org.postgresql.core PGStream QueryExecutor QueryExecutorBase)
    (org.postgresql.jdbc PgConnection)))

(defn get-conn []
  (let [start-time (System/currentTimeMillis)
        uri (str "datomic:sql://agent?"
                 "jdbc:postgresql://"
                 "localhost:5432"
                 "/postgres?user=postgres&password="
                 (System/getenv "POSTGRES_PASSWORD")
                 (System/getenv "CONN_EXTRA"))
        conn (do
               (d/create-database uri)
               (d/connect uri))
        spent-time (- (System/currentTimeMillis) start-time)]
    (log/info "Got datomic connection in" spent-time "milliseconds")
    conn))

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
    (log/info "Adding TCP filter:" drop-txt)
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
                                   {:levels [[#{"datomic.*"} :warn]
                                             [#{"com.github.ivarref.*"} :debug]
                                             [#{"*"} :info]]}))
    (accept!)
    (hookd/install-return-consumer!
      "org.apache.tomcat.jdbc.pool.ConnectionPool"
      "::Constructor"
      (partial reset! conn-pool))
    (when block?
      (log/info "Starting nREPL server ....")
      (nrepl/start-server :bind "127.0.0.1" :port 7777))
    (let [conn (get-conn)
          drop-count (atom 0)]
      (hookd/install-return-consumer!
        "org.apache.tomcat.jdbc.pool.ConnectionPool"
        "getConnection"
        (fn [^Connection conn]
          (let [^Socket sock (conn->socket conn)]
            (if (= 1 (swap! drop-count inc))
              (do
                (log/info "Dropping connection" conn)
                (drop-sock! sock))
              (log/info "Not dropping anything for" sock)))))
      (hookd/install-pre-hook!
        "org.apache.tomcat.jdbc.pool.ConnectionPool"
        "returnConnection"
        (fn [_ args]
          (log/info "Enter returnConnection" (into [] args))))
      (let [start-time (System/currentTimeMillis)]
        (log/info "Starting read-segment on blocked connection ...")
        (read-segment conn "854f8149-7116-45dc-b3df-5b57a5cd1e4e")
        (let [stop-time (System/currentTimeMillis)]
          (log/info "Reading on blocked connection ... Done in" (log-init/ms->duration (- stop-time start-time))))))
    (when block?
      @(promise))
    (finally
      (accept!))))
