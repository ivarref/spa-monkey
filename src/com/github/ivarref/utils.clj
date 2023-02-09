(ns com.github.ivarref.utils
  (:require [clojure.tools.logging :as log]
            [com.github.ivarref.log-init :as log-init]
            [datomic.api :as d])
  (:import (java.net Socket)))

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
