(ns com.github.ivarref.case-1
  (:require [datomic.api :as d]
            [com.github.ivarref.spa-monkey :as monkey]
            [clojure.tools.logging :as log]
            [babashka.process :refer [$ check]]
            [nrepl.server :as nrepl]
            [datomic.cluster :as cluster]))

(defonce init
         (Thread/setDefaultUncaughtExceptionHandler
           (reify Thread$UncaughtExceptionHandler
             (uncaughtException [_ thread ex]
               (.print (System/err) "Uncaught exception on ")
               (.println (System/err) (.getName ^Thread thread))
               (.printStackTrace ^Throwable ex)
               nil))))

(defn accept! []
  (as-> ^{:out :string :err :string} ($ ./accept) v
    (check v)))

(defn drop! []
  (as-> ^{:out :string :err :string} ($ ./drop) v
        (check v)))

(defn start-nrepl-server! [{:keys [block?]}]
  (nrepl/start-server :bind "127.0.0.1" :port 7777)
  (log/info "nrepl server started")
  (when block?
    @(promise)))


(defonce st (atom {:remote-host "psql-stage-we.postgres.database.azure.com"
                   :remote-port 5432}))

(defn get-conn []
  (let [start-time (System/currentTimeMillis)]
    (monkey/start! st)
    (let [url (str "datomic:sql://ire-test-1?"
                   "jdbc:postgresql://"
                   "localhost:20009"
                   ;"psql-stage-we.postgres.database.azure.com:5432"
                   "/datomic?user=datomic@psql-stage-we&password="
                   (System/getenv "DATOMIC_STAGE_PASSWORD")
                   "&sslmode=require")
          conn (d/connect url)
          spent-time (- (System/currentTimeMillis) start-time)]
      (log/info "Got datomic connection in" spent-time "milliseconds")
      conn)))

(defn read-segment [conn val-key]
  (let [cluster (.get-cluster conn)]
    @(cluster/get-val cluster val-key)))

(defonce read-status (atom nil))
(defonce conn-atom (atom nil))

(defn do-test! [{:keys [block?]}]
  (log/info "Starting test ...")
  (accept!)
  (start-nrepl-server! nil)
  (let [conn (get-conn)
        start-time (System/currentTimeMillis)]
    (reset! conn-atom conn)
    (try
      (reset! read-status :pending)
      (drop!)
      (Thread/sleep 1000)
      (log/info "Starting read-segment on blocked connection")
      (read-segment conn "854f8149-7116-45dc-b3df-5b57a5cd1e4e")
      (reset! read-status :done)
      (log/info "Got segment after" (- (System/currentTimeMillis)
                                       start-time)
                "milliseconds")
      (catch Throwable t
        (log/error t "Error during read:" (ex-message t))
        (reset! read-status [:error t]))))
  (when block?
    @(promise)))



(comment
  (def conn (get-conn)))

#_(def conn (d/connect url))
