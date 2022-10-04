(ns com.github.ivarref.utils
  (:require [clojure.tools.logging :as log]
            [datomic.api :as d]))

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
