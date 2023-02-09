(ns com.github.ivarref.break-single-conn-once
  (:require
    [babashka.process :refer [$ check]]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [com.github.ivarref.hookd :as hookd]
    [com.github.ivarref.log-init :as log-init]
    [com.github.ivarref.utils :as u]
    [datomic.api :as d]
    [nrepl.server :as nrepl]
    [taoensso.timbre :as timbre])
  (:import
    (java.net Socket)
    (java.sql Connection)
    (javax.sql PooledConnection)
    (org.postgresql.core PGStream QueryExecutor QueryExecutorBase)
    (org.postgresql.jdbc PgConnection)))

; sudo bash -c 'echo 6 > /proc/sys/net/ipv4/tcp_retries2'
; $ cat /proc/sys/net/ipv4/tcp_retries2
; 15
; sdk default java 17.0.4.1-tem

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
  (try
    (as-> ^{:out :string :err :string} ($ sudo ./accept) v
          (check v))
    (catch Throwable t
      (log/error "Could not clear packet filters:" (ex-message t))
      (throw t))))

(defn sock->drop [^Socket s]
  (str "tcp dport " (.getPort s) " "
       "tcp sport " (.getLocalPort s) " "
       "ip saddr 127.0.0.1 "
       "ip daddr 127.0.0.1 drop;"))

(defn drop-str! [s]
  (if (try
        (spit "drop.txt" s)
        true
        (catch Throwable t
          (log/error t "Writing drop.txt failed:" (ex-message t))
          false))
    (as-> ^{:out :string :err :string} ($ sudo "/usr/sbin/nft" -f ./drop.txt) v
          (check v))
    (log/error "Not invoking nft!")))

(defn sock->readable [sock]
  (str "127.0.0.1:" (.getLocalPort sock)
       "->"
       "127.0.0.1:" (.getPort sock)))

(defn drop-sock! [sock]
  (let [drop-txt (sock->drop sock)
        drop-file (str/join "\n"
                            ["flush ruleset"
                             "table ip filter {"
                             "chain output {"
                             "type filter hook output priority filter;"
                             "policy accept;"
                             drop-txt
                             "}"
                             "}"])]
    (log/info "Dropping TCP packets for" (sock->readable sock))
    (drop-str! drop-file)))

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
              (log/info (if (even? v) "tick" "tack"))
              (recur new-uptime (inc v)))
            (recur uptime v)))))))

(defn do-test! [{:keys [block?] :as opts}]
  (try
    (log-init/init-logging! (merge opts
                                   {:levels [[#{"datomic.*"} :warn]
                                             [#{"com.github.ivarref.*"} :info]
                                             [#{"*"} :info]]}))
    (log/debug "user.name is" (System/getProperty "user.name"))
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
              (log/info "Not dropping anything for" (sock->readable sock))))))
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
          (log/debug "got result" (type result)))
        (deliver done-read? :done)
        (let [stop-time (System/currentTimeMillis)]
          (log/info "Query on blocked connection ... Done in" (log-init/ms->duration (- stop-time start-time))))))
    (when block?
      @(promise))
    (catch Throwable t
      (log/error t "unexpected exception:" (ex-message t)))
    (finally
      (accept!))))
