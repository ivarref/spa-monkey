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

(defn nft-sudo [filename]
  (log/info "Executing sudo nft -f" filename "...")
  (let [fut (future (try
                      (as-> ^{:out :string :err :string} ($ sudo nft -f ~filename) v
                            (check v))
                      :ok
                      (catch Throwable t
                        t)))
        res (deref fut (* 10 60000) ::timeout)]
    (cond
      (= ::timeout res)
      (do
        (log/info "Executing sudo nft -f" filename "... Timeout!")
        (throw (ex-info "sudo nft timeout" {})))

      (= :ok res)
      (do
        (log/info "Executing sudo nft -f" filename "... OK!")
        true)

      (instance? Throwable res)
      (do
        (log/error res "Executing sudo nft -f" filename "... Error:" (ex-message res))
        (throw res))

      :else
      (do
        (log/error "Unhandled state. Got res:" res)
        (throw (ex-info "Unexpected state" {:result res}))))))

(defn accept! []
  (log/info "Clear all packet filters ...")
  (nft-sudo "accept.txt"))

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
    (nft-sudo "drop.txt")
    (do
      (log/error "Not invoking nft!")
      false)))

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

#_(defn tcp-info [sock]
    (-> (into (sorted-map) (GetSockOpt/getTcpInfo sock))
        (select-keys ["tcpi_state_str" "tcpi_unacked"])))

(defn tick-tack-loop [done-read? blocked-socket]
  (loop [uptime (int (/ (log-init/jvm-uptime-ms) 60000))
         v 0]
    (when-not (realized? done-read?)
      (let [timeout? (true? (deref done-read? 1000 true))
            new-uptime (int (/ (log-init/jvm-uptime-ms) 60000))]
        (when timeout?
          (if (not= uptime new-uptime)
            (do
              (log/info (if (even? v) "Tick" "Tack")
                        (when (realized? blocked-socket)
                          (into (sorted-map) (GetSockOpt/getTcpInfo @blocked-socket))))
              (recur new-uptime (inc v)))
            (recur uptime v)))))))

(defn get-state [sock]
  (-> (into (sorted-map) (GetSockOpt/getTcpInfo sock))
      (assoc "open?" (not (.isClosed sock)))))

(defn watch-socket! [^Socket sock]
  (future
    (try
      (let [initial-state (get-state sock)
            fd (GetSockOpt/getFd sock)
            now (System/currentTimeMillis)]
        (log/info "Initial state for fd" fd initial-state)
        (loop [prev-state initial-state]
          (Thread/sleep 1)
          (let [{:strs [open?] :as new-state} (get-state sock)]
             (when (not= new-state prev-state)
               (doseq [[new-k new-v] new-state]
                 (when (and (not= new-v (get prev-state new-k))
                            (not (contains? #{"tcpi_last_ack_recv"
                                              "tcpi_last_ack_sent"
                                              "tcpi_last_data_recv"
                                              "tcpi_last_data_sent"}
                                            new-k)))
                   (log/info "fd" fd new-k (get prev-state new-k) "=>" new-v))))
             (when open?
               (recur new-state)))))
      (catch Throwable t
        (if (.isClosed sock)
          (log/warn "Error in socket watcher:" (ex-message t))
          (log/error "Error in socket watcher:" (ex-message t)))))))

(defn do-test! [{:keys [block?] :as opts}]
  (try
    (log-init/init-logging! (merge opts
                                   {:levels [[#{"datomic.*"} :warn]
                                             [#{"com.github.ivarref.*"} :info]
                                             [#{"*"} :info]]}))
    (log/debug "user.name is" (System/getProperty "user.name"))
    (let [tcp-retry-file "/proc/sys/net/ipv4/tcp_retries2"]
      (log/info tcp-retry-file "is" (str/trim (slurp tcp-retry-file))))
    (accept!)
    (hookd/install-return-consumer!
      "org.apache.tomcat.jdbc.pool.ConnectionPool"
      "::Constructor"
      (partial reset! conn-pool))
    (when block?
      (log/info "Starting nREPL server ....")
      (nrepl/start-server :bind "127.0.0.1" :port 7777))
    (let [conn (u/get-conn)
          drop-count (atom 0)
          blocked-socket (promise)]
      (hookd/install-return-consumer!
        "org.apache.tomcat.jdbc.pool.ConnectionPool"
        "getConnection"
        (fn [^Connection conn]
          (let [^Socket sock (conn->socket conn)]
            (if (= 1 (swap! drop-count inc))
              (do
                (deliver blocked-socket sock)
                (drop-sock! sock)
                (watch-socket! sock)
                #_(log/info "Blocked socket:" (tcp-info @blocked-socket)))
              (log/info "Not dropping anything for" (sock->readable sock))))))
      (let [start-time (System/currentTimeMillis)
            done-read? (promise)]
        (timbre/merge-config! {:min-level [[#{"datomic.*"} :debug]
                                           [#{"com.github.ivarref.*"} :info]
                                           [#{"*"} :info]]})
        (log/info "Starting query on blocked connection ...")
        (future (tick-tack-loop done-read? blocked-socket))
        (let [result (d/q '[:find ?e ?doc
                            :in $
                            :where
                            [?e :db/doc ?doc]]
                          (d/db conn))]
          (log/debug "Got query result" result))
        (deliver done-read? :done)
        ;(log/info "Blocked socket:" (tcp-info @blocked-socket))
        ;(Thread/sleep 5000)
        ;(log/info "Blocked socket:" (tcp-info @blocked-socket))
        (let [stop-time (System/currentTimeMillis)]
          (log/info "Query on blocked connection ... Done in" (log-init/ms->duration (- stop-time start-time))
                    "aka" (int (- stop-time start-time)) "milliseconds")
          (log/info "Waiting 90 seconds for datomic.process-monitor")
          (Thread/sleep 90000)))) ; Give datomic time to report StorageGetMsec
    (when block?
      @(promise))
    (catch Throwable t
      (log/error t "Unexpected exception:" (ex-message t)))
    (finally
      (accept!))))
