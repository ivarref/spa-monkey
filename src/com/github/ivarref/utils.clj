(ns com.github.ivarref.utils
  (:require [clojure.set :as set]
            [clojure.tools.logging :as log]
            [com.github.ivarref.log-init :as log-init]
            [com.github.ivarref.nft :as nft]
            [datomic.api :as d])
  (:import (com.github.ivarref GetSockOpt)
           (java.net Socket)
           (java.time Duration)
           (javax.sql PooledConnection)
           (org.postgresql.core PGStream QueryExecutor QueryExecutorBase)
           (org.postgresql.jdbc PgConnection)))

(defn get-conn [& {:keys [port]
                   :or   {port 5432}}]
  (let [start-time (System/currentTimeMillis)
        uri (str "datomic:sql://agent?"
                 "jdbc:postgresql://"
                 "localhost:" port
                 "/postgres?user=postgres&password="
                 (System/getenv "POSTGRES_PASSWORD")
                 (System/getenv "CONN_EXTRA"))
        conn (do
               (d/create-database uri)
               (d/connect uri))
        spent-time (- (System/currentTimeMillis) start-time)]
    (log/debug "Got datomic connection in" spent-time "milliseconds")
    conn))

(defn start-tick-thread [sock-promise done?]
  (future
    (let [curr-name (.getName (Thread/currentThread))]
      (try
        (.setName (Thread/currentThread) "Ticker")
        (if (= :error (deref sock-promise 3000 :error))
          (throw (ex-info "Timeout waiting for socket" {}))
          (log/info "Tick thread started"))
        (loop [uptime (int (/ (log-init/jvm-uptime-ms) 60000))
               v 0]
          (when (and (not (.isClosed @sock-promise))
                     (not (realized? done?)))
            (let [timeout? (true? (deref done? 1000 true))
                  new-uptime (int (/ (log-init/jvm-uptime-ms) 60000))]
              (when timeout?
                (if (not= uptime new-uptime)
                  (do
                    (log/info (if (even? v) "tick" "tack"))
                    (recur new-uptime (inc v)))
                  (recur uptime v))))))
        (catch Throwable t
          (log/error t "Error:" (ex-message t)))
        (finally
          (log/info "Tick thread exiting")
          (.setName (Thread/currentThread) curr-name))))))

(defn conn->socket [^PooledConnection conn]
  (if (instance? PooledConnection conn)
    (let [^PgConnection conn (.getConnection conn)
          ^QueryExecutor qe (.getQueryExecutor conn)
          field (.getDeclaredField QueryExecutorBase "pgStream")
          _ (.setAccessible field true)
          ^PGStream stream (.get field qe)]
      (.getSocket stream))
    (do
      (log/error "Unhandled conn->socket for" conn)
      (throw (ex-info "Unhandled conn->socket" {:conn conn})))))

(defn get-state [sock]
  (-> (into (sorted-map) (GetSockOpt/getTcpInfo sock))
      (dissoc "tcpi_last_ack_recv"
              "tcpi_last_ack_sent"
              "tcpi_last_data_recv"
              "tcpi_last_data_sent"
              "tcpi_probes"
              "tcpi_state")
      (set/rename-keys {"tcpi_state_str" "tcpi_state"})
      (assoc "open?" (not (.isClosed sock)))))

(defn with-clock [state now-ms]
  (reduce-kv (fn [o k v]
               (assoc o k [v now-ms]))
             {}
             state))

(defn max-clock [state]
  (reduce-kv (fn [o _k [_v now-ms]]
               (max o now-ms))
             0
             state))

(defn no-clock [state]
  (reduce-kv (fn [o k [v _now-ms]]
               (assoc o k v))
             {}
             state))

(def tick-granularity-ms 10000)

(defn watch-socket! [nam running? ^Socket sock]
  (future
    (let [org-name (.getName (Thread/currentThread))]
      (.setName (Thread/currentThread) (str nam "-socket-watcher"))
      (try
        (let [initial-state (get-state sock)
              fd (GetSockOpt/getFd sock)]
          (log/info nam "fd" fd (nft/sock->readable sock) "initial state is" initial-state)
          (loop [prev-log (System/currentTimeMillis)
                 prev-state (with-clock initial-state (System/currentTimeMillis))]
            (Thread/sleep 1)
            (let [now-ms (System/currentTimeMillis)
                  {:strs [open?] :as new-state} (get-state sock)]
              (cond
                (not= new-state (no-clock prev-state))
                (do
                  (doseq [[new-k new-v] new-state]
                    (when (not= new-v (first (get prev-state new-k)))
                      (let [ms-diff (- now-ms (second (get prev-state new-k)))]
                        (log/info nam "fd" fd (nft/sock->readable sock) new-k (first (get prev-state new-k)) "=>" new-v (str "(In " ms-diff " ms)")))))
                  (when (and @running? open?)
                    (recur now-ms
                           (reduce-kv (fn [o k [old-v _old-ms :as old-val]]
                                        (if (not= old-v (get new-state k))
                                          (assoc o k [(get new-state k) now-ms])
                                          (assoc o k old-val)))
                                      {}
                                      prev-state))))
                (or (not @running?) (not open?))
                nil

                (> (- now-ms prev-log) tick-granularity-ms)
                (do
                  (log/info nam "fd" fd (nft/sock->readable sock) "no changes last" (str (Duration/ofMillis (- now-ms (max-clock prev-state)))))
                  (recur now-ms prev-state))

                :else
                (recur prev-log prev-state)))))
        (catch Throwable t
          (if (.isClosed sock)
            (log/warn nam "fd" (GetSockOpt/getFd sock) (nft/sock->readable sock)  "error in socket watcher. Message:"  (ex-message t))
            (log/error nam "fd" (GetSockOpt/getFd sock) (nft/sock->readable sock)  "error in socket watcher. Message:"  (ex-message t))))
        (finally
          (log/info nam "fd" (GetSockOpt/getFd sock) (nft/sock->readable sock)  "watcher exiting")
          (.setName (Thread/currentThread) org-name))))))
