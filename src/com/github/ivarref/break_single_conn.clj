(ns com.github.ivarref.break-single-conn
  (:require
    [babashka.process :refer [$ check]]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [com.github.ivarref.hookd :as hookd]
    [datomic.api :as d]
    [datomic.cluster :as cluster]
    [nrepl.server :as nrepl]
    [taoensso.timbre :as timbre])
  (:import (java.lang.management ManagementFactory)
           (java.net Socket)
           (java.sql Connection)
           (java.time Duration ZonedDateTime)
           (java.time.format DateTimeFormatter)
           (java.util.logging Level Logger)
           (javax.sql PooledConnection)
           (org.postgresql.core PGStream QueryExecutor QueryExecutorBase)
           (org.postgresql.jdbc PgConnection)
           (org.slf4j.bridge SLF4JBridgeHandler)))

(defonce init
         (Thread/setDefaultUncaughtExceptionHandler
           (reify Thread$UncaughtExceptionHandler
             (uncaughtException [_ thread ex]
               (.print (System/err) "Uncaught exception on ")
               (.println (System/err) (.getName ^Thread thread))
               (.printStackTrace ^Throwable ex)
               nil))))

(defn jvm-uptime-ms []
  (.getUptime (ManagementFactory/getRuntimeMXBean)))

(defn ms->duration [ms]
  (apply (partial format "%02d:%02d:%02d")
         (let [duration (Duration/ofMillis ms)]
           [(.toHours duration)
            (.toMinutesPart duration)
            (.toSecondsPart duration)])))

(defn local-console-format-fn
  [data]
  (try
    (let [{:keys [level ?err msg_ ?ns-str]} data]
      (let [maybe-stacktrace (when ?err
                               (str "\n" (timbre/stacktrace ?err {:stacktrace-fonts nil})))]
        (str
          (ms->duration (jvm-uptime-ms))
          " "
          (str/upper-case (name level))
          " "
          (last (str/split ?ns-str #"\."))
          " "
          (force msg_)
          maybe-stacktrace)))
    (catch Throwable t
      (println "error in local-console-format-fn:" (ex-message t))
      nil)))

(defonce lock (atom nil))

(defn atomic-println [log-file arg]
  (locking lock
    (when (some? log-file)
      (spit log-file (str arg "\n") :append true))
    (println arg)))

(defn init-logging! [{:keys [log-file]}]
  (let [log-file (when (some? log-file)
                   (str log-file
                        "_"
                        (.format
                          (DateTimeFormatter/ofPattern "yyyy-MM-dd_HH_mm_ss")
                          (ZonedDateTime/now))
                        ".log"))]
    (when (some? log-file)
      (spit log-file ""))
    (SLF4JBridgeHandler/removeHandlersForRootLogger)
    (SLF4JBridgeHandler/install)
    (.setLevel (Logger/getLogger "") Level/FINEST)
    (timbre/merge-config!
      {:min-level [[#{"datomic.*"} :debug]
                   ;[#{"com.github.ivarref.*"} :debug]
                   [#{"*"} :info]]
       :output-fn (fn [data] (local-console-format-fn data))
       :appenders {:println {:enabled?   true
                             :async?     false
                             :min-level  nil
                             :rate-limit nil
                             :output-fn  :inherit
                             :fn         (fn [data]
                                           (let [{:keys [output_]} data]
                                             (atomic-println log-file (force output_))))}}})
    (when (some? log-file)
      (log/info "logging to file" log-file))))

(defn get-conn []
  (let [start-time (System/currentTimeMillis)]
    (let [uri (str "datomic:sql://agent?"
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
      conn)))

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
    (log/info "Adding IP filter:" drop-txt)
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
  (init-logging! opts)
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
            (drop-sock! sock)
            (log/debug "Not dropping anything.")))))
    (let [start-time (System/currentTimeMillis)]
      (log/info "Starting read-segment on blocked connection ...")
      (read-segment conn "854f8149-7116-45dc-b3df-5b57a5cd1e4e")
      (let [stop-time (System/currentTimeMillis)]
        (log/info "Starting read-segment on blocked connection ... Done in" (str (ms->duration (- stop-time start-time)) ".")))))
  (when block?
    @(promise)))
