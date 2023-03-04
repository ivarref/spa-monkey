(ns com.github.ivarref.log-init
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
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

(defn local-console-format-fn
  [data]
  (try
    (let [{:keys [level ?err msg_ ?ns-str]} data
          maybe-stacktrace (when ?err
                             (str "\n" (timbre/stacktrace ?err {:stacktrace-fonts nil})))]
      (str
        (ms->duration (jvm-uptime-ms))
        " ["
        (str/upper-case (name level))
        "] "
        (when-not (str/starts-with? ?ns-str "com.github.ivarref")
          (str (.getName (Thread/currentThread)) " "))
        (when-not (str/starts-with? ?ns-str "com.github.ivarref")
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

(defn atomic-println [log-file line {:keys [level ?err msg_ ?ns-str]}]
  (locking lock
    (let [line-number (swap! line-count inc)
          line (str (format-line-number line-number) " " line)]
      (when (some? log-file)
        (spit log-file (str line "\n") :append true)
        (spit (str log-file ".json")
              (str
                (json/generate-string (array-map
                                        :level (str/upper-case (name level))
                                        :logger ?ns-str
                                        :thread (.getName (Thread/currentThread))
                                        :message (force msg_)))
                "\n")
              :append true))
      (println line))))

(defn init-logging! [{:keys [log-file min-level]
                      :or   {min-level [[#{"datomic.*"} :warn]
                                        [#{"com.github.ivarref.*"} :debug]
                                        [#{"*"} :info]]}}]
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
       :output-fn (fn [data] (local-console-format-fn data))
       :appenders {:println {:enabled?   true
                             :async?     false
                             :min-level  nil
                             :rate-limit nil
                             :output-fn  :inherit
                             :fn         (fn [data]
                                           (let [{:keys [output_]} data]
                                             (atomic-println log-file (force output_) data)))}}})
    (when (some? log-file)
      (log/debug "logging to file" log-file))))
