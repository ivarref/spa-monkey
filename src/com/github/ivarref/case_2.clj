(ns com.github.ivarref.case-2
  (:require [datomic.api :as d]
            [com.github.ivarref.spa-monkey :as monkey]
            [clojure.tools.logging :as log]
            [com.github.ivarref.case-1 :as u])
  (:import (java.time Duration)))

(defonce st (atom {:remote-host "localhost"
                   :remote-port 5432
                   :port        21119}))

(defn get-conn []
  (let [start-time (System/currentTimeMillis)]
    (monkey/start! st)
    (let [uri (str "datomic:sql://ire-test-2?"
                   "jdbc:postgresql://"
                   "localhost:21119"
                   "/postgres?user=postgres&password="
                   (System/getenv "POSTGRES_PASSWORD"))
          conn (do
                 (d/create-database uri)
                 (d/connect uri))
          spent-time (- (System/currentTimeMillis) start-time)]
      (log/info "Got datomic connection in" spent-time "milliseconds")
      conn)))

(defonce read-status (atom nil))
(defonce conn-atom (atom nil))

(defn do-test-inner [_]
  (log/info "Starting case 2 ...")
  (u/start-nrepl-server! nil)
  (let [conn (get-conn)
        start-time (System/currentTimeMillis)
        done? (promise)]
    (reset! conn-atom conn)
    (try
      (reset! read-status :pending)
      (monkey/drop-remote! st)
      (future
        (loop []
          (when (= :wait (deref done? 60000 :wait))
            (log/info "Still waiting for read segment")
            (recur))))
      (log/info "Starting read-segment on single blocked connection")
      (u/read-segment conn "854f8149-7116-45dc-b3df-5b57a5cd1e4e")
      (reset! read-status :done)
      (log/info "Got segment after" (- (System/currentTimeMillis) start-time) "milliseconds")
      (catch Throwable t
        (log/error t "Error during read:" (ex-message t))
        (let [spent-time (- (System/currentTimeMillis) start-time)]
          (log/info "Error after"
                    (u/ms->duration spent-time)
                    "aka"
                    spent-time
                    "milliseconds")
          (reset! read-status [:error t])))
      (finally
        (deliver done? :true))))
  (Thread/sleep 1000))

(defn do-test! [{:keys [block?] :as args}]
  (try
    (u/init-logging! args)
    (do-test-inner args))
  (if block?
    @(promise)
    (do
      (Thread/sleep (.toMillis (Duration/ofMinutes 3)))
      (shutdown-agents))))

(comment
  (def conn (do
              (u/init-logging! {})
              (get-conn))))

(comment
  (do
    (monkey/drop-remote! st)
    (u/read-segment conn "854f8149-7116-45dc-b3df-5b57a5cd1e4e")))
