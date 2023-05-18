(ns com.github.ivarref.log-init
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [io.aviso.repl :as repl]
    [taoensso.timbre :as timbre])
  (:import (java.lang.management ManagementFactory)
           (java.time Duration)
           (java.util.logging Level Logger)
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

(defn console-format-line
  [data]
  (try
    (let [{:keys [uptime-ms level ?err msg_ ?ns-str]} data
          maybe-stacktrace (when ?err
                             (str "\n" (timbre/stacktrace ?err {:stacktrace-fonts nil})))]
      (str
        (ms->duration uptime-ms)
        " ["
        (str/upper-case (name level))
        "] "
        #_(when-not (str/starts-with? ?ns-str "com.github.ivarref")
            (str (.getName (Thread/currentThread)) " "))
        #_(when-not (str/starts-with? ?ns-str "com.github.ivarref")
            (str ?ns-str " "))
        (force msg_)
        #_maybe-stacktrace))
    (catch Throwable t
      (println "error in local-console-format-fn:" (ex-message t))
      nil)))

(defonce lock (atom nil))
(defonce line-count (atom 0))

(defn format-line-number [line-number]
  (format "%04d" line-number))

(defn atomic-log [log-file {:keys [uptime-ms level ?err msg_ ?ns-str] :as data}]
  (locking lock
    (let [line-number (swap! line-count inc)
          line (str (format-line-number line-number) " " (console-format-line data))]
      (when (some? log-file)
        (spit log-file (str line "\n") :append true)
        (spit (str log-file ".json")
              (str
                (json/generate-string (array-map
                                        :level (str/upper-case (name level))
                                        :logger ?ns-str
                                        :thread (.getName (Thread/currentThread))
                                        :uptime (ms->duration uptime-ms)
                                        :message (force msg_)))
                "\n")
              :append true))
      (println line))))

(defn init-logging! [{:keys [log-file min-level]
                      :or   {min-level [[#{"datomic.*"} :warn]
                                        [#{"com.github.ivarref.*"} :debug]
                                        [#{"*"} :info]]}}]
  (repl/install-pretty-exceptions)
  (let [log-file (when (some? log-file)
                   (str "logs/"
                        log-file
                        ".log"))]
    (when (some? log-file)
      (spit log-file "")
      (spit (str log-file ".json") ""))
    (SLF4JBridgeHandler/removeHandlersForRootLogger)
    (SLF4JBridgeHandler/install)
    (.setLevel (Logger/getLogger "") Level/FINEST)
    (timbre/merge-config!
      {:min-level min-level
       :output-fn (fn [data] data)
       :appenders {:println {:enabled?   true
                             :async?     false
                             :min-level  nil
                             :rate-limit nil
                             :output-fn  :inherit
                             :fn         (fn [data]
                                           (atomic-log log-file (assoc data :uptime-ms (jvm-uptime-ms))))}}})
    (when (some? log-file)
      (log/debug "logging to file" log-file))))
