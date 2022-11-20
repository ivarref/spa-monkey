(ns com.github.ivarref.utils
  (:require [clojure.tools.logging :as log]
            [com.github.ivarref.log-init :as log-init]
            [datomic.api :as d]))

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
    (log/info "Got datomic connection in" spent-time "milliseconds")
    conn))

(defn start-tick-thread [done?]
  (future
    (loop [uptime (int (/ (log-init/jvm-uptime-ms) 60000))
           v 0]
      (when-not (realized? done?)
        (let [timeout? (true? (deref done? 1000 true))
              new-uptime (int (/ (log-init/jvm-uptime-ms) 60000))]
          (when timeout?
            (if (not= uptime new-uptime)
              (do
                (log/info (if (even? v) "tick" "tack"))
                (recur new-uptime (inc v)))
              (recur uptime v))))))))
