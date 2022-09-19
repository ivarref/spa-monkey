(ns com.github.ivarref.log-init
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [taoensso.timbre :as timbre])
  (:import (java.lang.management ManagementFactory)
           (java.time Duration ZonedDateTime)
           (java.time.format DateTimeFormatter)
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
        " "
        (str/upper-case (name level))
        " "
        (last (str/split ?ns-str #"\."))
        " "
        (force msg_)
        maybe-stacktrace))
    (catch Throwable t
      (println "error in local-console-format-fn:" (ex-message t))
      nil)))

(defonce lock (atom nil))

(defn atomic-println [log-file arg]
  (locking lock
    (when (some? log-file)
      (spit log-file (str arg "\n") :append true))
    (println arg)))

(defn init-logging! [{:keys [log-file levels]
                      :or   {levels [[#{"datomic.*"} :warn]
                                     [#{"com.github.ivarref.*"} :debug]
                                     [#{"*"} :info]]}}]
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
      {:min-level levels
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
