(ns com.github.ivarref.case-1
  (:require [datomic.api :as d]
            [com.github.ivarref.spa-monkey :as monkey]
            [clojure.tools.logging :as log]
            [babashka.process :refer [$ check]]
            [nrepl.server :as nrepl]
            [datomic.cluster :as cluster]
            [taoensso.timbre :as timbre]
            [clojure.string :as str])
  (:import (java.time Duration ZonedDateTime)
           (org.slf4j.bridge SLF4JBridgeHandler)
           (java.util.logging Logger Level)
           (java.lang.management ManagementFactory)
           (java.time.format DateTimeFormatter)))

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
                               (str "\n" (timbre/stacktrace ?err)))]
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
      {:min-level [[#{"datomic.kv-cluster"} :debug]
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

(defn accept! []
  (log/info "Start accepting all packets")
  (as-> ^{:out :string :err :string} ($ ./accept) v
        (check v)))

(defn drop! []
  (log/info "Start dropping packets")
  (as-> ^{:out :string :err :string} ($ ./drop) v
        (check v)))

(defn start-nrepl-server! [{:keys [block?]}]
  (nrepl/start-server :bind "127.0.0.1" :port 7777)
  (log/info "nrepl server started")
  (when block?
    @(promise)))


(defonce st (atom {:remote-host "localhost"
                   :remote-port 5432}))

(defn get-conn []
  (let [start-time (System/currentTimeMillis)]
    (monkey/start! st)
    (let [uri (str "datomic:sql://ire-test-1?"
                   "jdbc:postgresql://"
                   "localhost:20009"
                   "/postgres?user=postgres&password="
                   (System/getenv "POSTGRES_PASSWORD"))
          conn (do
                 (d/create-database uri)
                 (d/connect uri))
          spent-time (- (System/currentTimeMillis) start-time)]
      (log/info "Got datomic connection in" spent-time "milliseconds")
      conn)))


(defn read-segment [conn val-key]
  (let [cluster (.get-cluster conn)]
    @(cluster/get-val cluster val-key)))

(defonce read-status (atom nil))
(defonce conn-atom (atom nil))

(defn do-test-inner [_]
  (log/info "Starting test ...")
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
        (let [spent-time (- (System/currentTimeMillis) start-time)]
          (log/info "Error after"
                    (ms->duration spent-time)
                    "aka"
                    spent-time
                    "milliseconds")
          (reset! read-status [:error t])))))
  (Thread/sleep 1000))

(defn do-test! [{:keys [block?] :as args}]
  (try
    (accept!)
    (init-logging! args)
    (do-test-inner args)
    (finally
      (accept!)))
  (if block?
    @(promise)
    (do
      (Thread/sleep (.toMillis (Duration/ofMinutes 3)))
      (shutdown-agents))))

(comment
  (def conn (get-conn)))

#_(def conn (d/connect url))
