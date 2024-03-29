(ns com.github.ivarref.tcp-retry
  (:require
    [clojure.java.io :as jio]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [com.github.ivarref.hookd :as hookd]
    [com.github.ivarref.log-init :as log-init]
    [com.github.ivarref.nft :as nft]
    [com.github.ivarref.utils :as u]
    [com.github.ivarref.spa-monkey :as monkey]
    [datomic.api :as d]
    [nrepl.server :as nrepl]
    [taoensso.timbre :as timbre])
  (:import
    (java.lang ProcessHandle)
    (java.net Socket)
    (java.sql Connection)
    (java.time Duration)
    (java.util.concurrent CountDownLatch)))

; sudo bash -c 'echo 6 > /proc/sys/net/ipv4/tcp_retries2'

; my machine's (Linux 5.15.91-1-MANJARO #1 SMP PREEMPT Wed Feb 1 12:03:19 UTC 2023 x86_64 GNU/Linux)
; default is 15:
; $ cat /proc/sys/net/ipv4/tcp_retries2
; 15

; sudo bash -c 'echo 15 > /proc/sys/net/ipv4/tcp_retries2'
; sdk default java 20.ea.34-open

(defonce conn-pool (atom nil))

(defonce datomic-conn (atom nil))

(defn do-test! [{:keys [block?] :as opts}]
  (try
    (log-init/init-logging! (merge opts
                                   {:min-level [[#{"datomic.*"} :warn]
                                                [#{"com.github.ivarref.*"} :info]
                                                [#{"*"} :info]]}))
    (log/debug "user.name is" (System/getProperty "user.name"))
    (let [tcp-retry-file "/proc/sys/net/ipv4/tcp_retries2"]
      (log/info tcp-retry-file "is" (str/trim (slurp tcp-retry-file))))
    (nft/accept!)
    (hookd/install-return-consumer!
      "org.apache.tomcat.jdbc.pool.ConnectionPool"
      "::Constructor"
      (partial reset! conn-pool))
    (when block?
      (log/info "Starting nREPL server ....")
      (nrepl/start-server :bind "127.0.0.1" :port 7777))
    (let [conn (u/get-conn)
          drop-count (atom 0)
          running? (atom true)
          _ (hookd/install-return-consumer!
              "org.apache.tomcat.jdbc.pool.ConnectionPool"
              "getConnection"
              (fn [^Connection conn]
                (try
                  #_(log/info "connection is" conn)
                  (let [^Socket sock (u/conn->socket conn)]
                    (if (= 1 (swap! drop-count inc))
                      (do
                        (nft/drop-sock! sock)
                        (u/watch-socket! "client" running? sock))
                      (log/info "Not dropping anything for" (nft/sock->readable sock))))
                  (catch Throwable t
                    (log/error t "Unexpected error in getConnection:" (ex-message t))
                    (System/exit 1)
                    (throw t)))))
          start-time (System/currentTimeMillis)]
      (timbre/merge-config! {:min-level [[#{"datomic.*"} :debug]
                                         [#{"com.github.ivarref.*"} :info]
                                         [#{"*"} :info]]})
      (log/info "Starting query on blocked connection ...")
      (let [result (d/q '[:find ?e ?doc
                          :in $
                          :where
                          [?e :db/doc ?doc]]
                        (d/db conn))]
        (log/debug "Got query result" result))
      (Thread/sleep 100)
      (reset! running? false)
      (let [stop-time (System/currentTimeMillis)]
        (log/info "Query on blocked connection ... Done in" (log-init/ms->duration (- stop-time start-time))
                  "aka" (int (- stop-time start-time)) "milliseconds")
        (log/info "Waiting 70 seconds for datomic.process-monitor")
        (Thread/sleep 70000)))                              ; Give datomic time to report StorageGetMsec
    (when block?
      @(promise))
    (catch Throwable t
      (log/error t "Unexpected exception:" (ex-message t)))
    (finally
      (nft/accept! :throw? false)
      (log/info "Exiting"))))

(defonce proxy-state (atom {:remote-host "localhost"
                            :remote-port 5432
                            :port        54321}))

; cat logs/forever.log.json | jq -r -c '.thread' | sort | uniq
;CLI-agent-send-off-pool-7
;client-socket-watcher
;Datomic Metrics Reporter
;main
;proxy-socket-watcher
;ThreadGroup-1-recv
;ThreadGroup-1-send
;ThreadGroup-2-recv
;ThreadGroup-3-recv
;ThreadGroup-4-recv
; cat logs/forever.log.json | jq -r -c 'select( .thread == "CLI-agent-send-off-pool-7")'
; cat logs/forever.log.json | jq -r -c 'select( .thread == "proxy-socket-watcher") | .uptime + " " + .message'
; cat logs/forever.log.json | jq -r -c 'select( .thread == "client-socket-watcher") | .uptime + " " + .message'

(defn forever [{:keys [block?]}]
  (try
    (log-init/init-logging! {:log-file  "forever"
                             :min-level [[#{"datomic.*"} :warn]
                                         [#{"com.github.ivarref.*"} :info]
                                         [#{"*"} :info]]})
    (let [tcp-retry-file "/proc/sys/net/ipv4/tcp_retries2"]
      (log/info tcp-retry-file "is" (str/trim (slurp tcp-retry-file))))
    (log/info "PID is:" (.pid (ProcessHandle/current)))
    (log/info "Java version is:" (System/getProperty "java.version"))
    (nft/accept!)
    (hookd/install-return-consumer!
      "org.apache.tomcat.jdbc.pool.ConnectionPool"
      "::Constructor"
      (partial reset! conn-pool))
    (when block?
      (log/info "Starting nREPL server ....")
      (nrepl/start-server :bind "127.0.0.1" :port 7777))
    (when-let [e (monkey/start! proxy-state)]
      (log/error e "Could not start proxy:" (ex-message e))
      (System/exit 1))
    (let [conn (u/get-conn :port 54321)
          running? (atom true)
          get-conn-count (atom 0)
          start-time (System/currentTimeMillis)]
      (hookd/install-return-consumer!
        "org.apache.tomcat.jdbc.pool.ConnectionPool"
        "getConnection"
        (fn [^Connection conn]
          (let [^Socket sock (u/conn->socket conn)]
            (when (= 1 (swap! get-conn-count inc))
              (log/info "ConnectionPool/getConnection returning socket" (nft/sock->readable sock))
              (u/watch-socket! "client" running? sock)))))
      (monkey/add-handler!
        proxy-state
        (fn [{:keys [op dst]}]
          (when (= op :recv)
            (Thread/sleep 500)                              ; make sure ACKs have arrived at the other side
            (nft/drop-sock! dst)
            (u/watch-socket! "proxy" running? dst)
            :pop)))
      (timbre/merge-config! {:min-level [[#{"datomic.*"} :debug]
                                         [#{"com.github.ivarref.*"} :info]
                                         [#{"*"} :info]]})
      (log/info "Starting query on blocked connection ...")
      (spit "forever.txt" "start-query\n")
      (try
        (let [result (future (try
                               (d/q '[:find ?e ?doc
                                      :in $
                                      :where
                                      [?e :db/doc ?doc]]
                                    (d/db conn))
                               (catch Throwable t
                                 t)))]
          (while (= ::timeout (deref result 10000 ::timeout))
            (log/info "Waited for query result for"
                      (str (Duration/ofMillis (- (System/currentTimeMillis) start-time))))
            (spit "forever.txt" (str "waited "
                                     (str (Duration/ofMillis (- (System/currentTimeMillis) start-time)))
                                     "\n")
                  :append true))
          (spit "forever.txt" "got result\n" :append true)
          (log/debug "Got query result" @result))
        (finally
          (spit "forever.txt" "done\n" :append true)))
      (Thread/sleep 100)
      (reset! running? false)
      (let [stop-time (System/currentTimeMillis)]
        (log/info "Query on blocked connection ... Done in" (log-init/ms->duration (- stop-time start-time))
                  "aka" (int (- stop-time start-time)) "milliseconds")
        (log/info "Waiting 70 seconds for datomic.process-monitor")
        (Thread/sleep 70000)))                              ; Give datomic time to report StorageGetMsec
    (when block?
      (log/info "blocking ...")
      @(promise))
    (catch Throwable t
      (log/error t "Unexpected exception:" (ex-message t)))
    (finally
      (monkey/stop! proxy-state)
      (nft/accept! :throw? false)
      (log/info "Exiting"))))

(defmacro maybe-timeout [& body]
  `(let [thread# (atom nil)
         fut# (future (try
                        (reset! thread# (Thread/currentThread))
                        ~@body
                        (catch Throwable t#
                          (log/error t# "Unexpected error:" (ex-message t#)))))]
     (let [v# (deref fut# 5000 ::timeout)]
       (if (= v# ::timeout)
         [fut# @thread#]
         [v# nil]))))

(comment
  (let [v (first (maybe-timeout
                   (d/pull (d/db @datomic-conn) [:*] :db/txInstant)))]
    v))

(comment
  (doseq [id (sort (d/q '[:find [?id ...]
                          :in $
                          :where
                          [?e :db/ident ?id]]
                        (d/db @datomic-conn)))]
    (println id)
    (println (d/pull (d/db @datomic-conn) [:*] id))))

(comment
  (maybe-timeout (d/q '[:find ?tx
                        :in $
                        :where
                        [?e :db/txInstant ?tx]]
                      (d/db @datomic-conn))))

(def ^:private current-dir-prefix
  "Convert the current directory (via property 'user.dir') into a prefix to be omitted from file names."
  (delay (str (System/getProperty "user.dir") "/")))

(def expand-stack-trace-element (resolve 'io.aviso.exception/expand-stack-trace-element))

(comment
  (+ 1 2))
(comment
  (take 5 (map (partial expand-stack-trace-element @current-dir-prefix) (.getStackTrace @blocked-thread))))
