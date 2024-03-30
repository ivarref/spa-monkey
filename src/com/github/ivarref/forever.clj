(ns com.github.ivarref.forever
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [com.github.ivarref.hookd :as hookd]
            [com.github.ivarref.log-init :as log-init]
            [com.github.ivarref.nft :as nft]
            [com.github.ivarref.spa-monkey :as monkey]
            [com.github.ivarref.utils :as u]
            [datomic.api :as d]
            [taoensso.timbre :as timbre])
  (:import (java.lang ProcessHandle)
           (java.net Socket)
           (java.sql Connection)
           (java.time Duration)))

(defonce proxy-state (atom {:remote-host "localhost"
                            :remote-port 5432
                            :port        54321}))

(defonce conn-pool (atom nil))


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


(def expand-stack-trace-element (resolve 'io.aviso.exception/expand-stack-trace-element))

(def ^:private current-dir-prefix
  "Convert the current directory (via property 'user.dir') into a prefix to be omitted from file names."
  (delay (str (System/getProperty "user.dir") "/")))

#_(comment
    (take 5 (map (partial expand-stack-trace-element @current-dir-prefix) (.getStackTrace @blocked-thread))))

(defn forever [_]
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
      (spit "forever.stacktrace.log" "")
      (try
        (let [blocked-thread (promise)
              result (future (try
                               (deliver blocked-thread (Thread/currentThread))
                               (d/q '[:find ?e ?doc
                                      :in $
                                      :where
                                      [?e :db/doc ?doc]]
                                    (d/db conn))
                               (catch Throwable t
                                 t)))]
          (while (= ::timeout (deref result 1000 ::timeout))
            (println (str/join "\n" (mapv (partial expand-stack-trace-element @current-dir-prefix) (.getStackTrace @blocked-thread))))
            (log/info "Waited for query result for"
                      (str (Duration/ofMillis (- (System/currentTimeMillis) start-time)))))
          (log/debug "Got query result" @result))
        (finally
          nil))
      (Thread/sleep 100)
      (reset! running? false)
      (let [stop-time (System/currentTimeMillis)]
        (log/info "Query on blocked connection ... Done in" (log-init/ms->duration (- stop-time start-time))
                  "aka" (int (- stop-time start-time)) "milliseconds")
        (log/info "Waiting 70 seconds for datomic.process-monitor")
        (Thread/sleep 70000)))                              ; Give datomic time to report StorageGetMsec
    (catch Throwable t
      (log/error t "Unexpected exception:" (ex-message t)))
    (finally
      (monkey/stop! proxy-state)
      (nft/accept! :throw? false)
      (shutdown-agents)
      (log/info "Exiting"))))
