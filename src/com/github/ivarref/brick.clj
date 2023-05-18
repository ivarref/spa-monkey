(ns com.github.ivarref.brick
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [com.github.ivarref.hookd :as hookd]
            [com.github.ivarref.log-init :as log-init]
            [com.github.ivarref.nft :as nft]
            [com.github.ivarref.spa-monkey :as monkey]
            [com.github.ivarref.utils :as u]
            [datomic.api :as d]
            [nrepl.server :as nrepl]
            [taoensso.timbre :as timbre]
            [wiretap.wiretap :as w])
  (:import (datomic Connection)
           (java.lang ProcessHandle)
           (java.net Socket)
           (java.time Duration)))

(def n 1000000)
(def ids (mapv str (range 1 (inc n))))

(defn brick-init! [{:keys []}]
  (try
    (log-init/init-logging! {:log-file  "brick"
                             :min-level [[#{"datomic.*"
                                            "com.github.ivarref.spa-monkey"
                                            "com.github.ivarref.nft"} :warn]
                                         [#{"com.github.ivarref.*"} :info]
                                         [#{"*"} :info]]})
    (let [^Connection conn (u/get-conn :db-name "brick" :port 5432 :delete? true)
          tx (mapv (fn [id]
                     {:e/id   id
                      :e/info (str "info" id)})
                   ids)]
      @(d/transact conn [#:db{:ident :e/id, :cardinality :db.cardinality/one, :valueType :db.type/string, :unique :db.unique/identity}
                         #:db{:ident :e/info, :cardinality :db.cardinality/one, :valueType :db.type/string}])
      (doseq [tx-chunk (partition-all 10000 tx)]
        @(d/transact conn (vec tx-chunk))
        (log/info "Inserted 10000 items"))
      (.release conn))
    (catch Throwable t
      (log/error t "Unexpected error:" (ex-message t))
      (System/exit 1))
    (finally
      (log/info "brick-init exiting"))))

(defonce conn-pool (atom nil))
(defonce datomic-conn (atom nil))
(defonce proxy-state (atom {:remote-host "localhost"
                            :remote-port 5432
                            :port        54321}))
(defonce blocked-thread (atom nil))

; is the lock block per segment, or global somehow?

(defn brick [{:keys [block? brick? tick-rate-ms]}]
  (try
    (log-init/init-logging! {:log-file  "brick"
                             :min-level [[#{"datomic.kv-cluster"} :debug]
                                         [#{"datomic.*" "com.github.ivarref.spa-monkey"} :warn]
                                         [#{"com.github.ivarref.*"} :info]
                                         [#{"*"} :info]]})
    (let [tcp-retry-file "/proc/sys/net/ipv4/tcp_retries2"]
      (log/info tcp-retry-file "is" (str/trim (slurp tcp-retry-file))))
    (log/info "PID is" (.pid (ProcessHandle/current)))
    (nft/accept!)
    ;(log/info "Starting nREPL server ....")
    ;(nrepl/start-server :bind "127.0.0.1" :port 7777)
    #_(when-let [e (monkey/start! proxy-state)]
        (log/error e "Could not start proxy:" (ex-message e))
        (System/exit 1))
    (let [conn (u/get-conn :db-name "brick" :port 5432)]
      (log/info "Got connection")

      ;0094 00:00:06 [INFO] Starting pull 1
      ;0095 00:00:06 [DEBUG] {:event :kv-cluster/get-val, :val-key "64666731-3dff-4f65-b577-2be9b91471df", :phase :begin, :pid 410195, :tid 31}
      ;0097 00:00:06 [DEBUG] {:event :kv-cluster/get-val, :val-key "6466671b-521b-4b46-8d24-d9608c1f6e34", :phase :begin, :pid 410195, :tid 31}
      ;0099 00:00:06 [INFO] Starting pull 1000000
      ;0100 00:00:06 [DEBUG] {:event :kv-cluster/get-val, :val-key "64666730-74d1-44a9-a00d-a30cbd6ac1c4", :phase :begin, :pid 410195, :tid 31}

      ;0094 00:00:06 [INFO] Starting pull 1000000
      ;0095 00:00:06 [DEBUG] {:event :kv-cluster/get-val, :val-key "64666731-3dff-4f65-b577-2be9b91471df", :phase :begin, :pid 410667, :tid 29}
      ;0097 00:00:06 [DEBUG] {:event :kv-cluster/get-val, :val-key "6466671b-521b-4b46-8d24-d9608c1f6e34", :phase :begin, :pid 410667, :tid 29}
      ;0099 00:00:06 [DEBUG] {:event :kv-cluster/get-val, :val-key "64666730-74d1-44a9-a00d-a30cbd6ac1c4", :phase :begin, :pid 410667, :tid 29}
      ;0101 00:00:06 [INFO] Starting pull 1
      ;0102 00:00:06 [INFO] Done

      (log/info "Starting pull" (last ids))
      (d/pull (d/db conn) [:*] [:e/id (last ids)])

      (log/info "Starting pull 1")
      (d/pull (d/db conn) [:*] [:e/id "1"])

      (log/info "Starting pull 250000")
      (d/pull (d/db conn) [:*] [:e/id "250000"])
      ;0103 00:00:06 [DEBUG] {:event :kv-cluster/get-val, :val-key "64666720-94c1-4427-bbee-6d4d257ecbf6", :phase :begin, :pid 410978, :tid 31}
      ;0105 00:00:06 [DEBUG] {:event :kv-cluster/get-val, :val-key "64666720-9fee-4713-b532-1ada76a62edb", :phase :begin, :pid 410978, :tid 31}

      (log/info "Starting pull 500000")
      (d/pull (d/db conn) [:*] [:e/id "500000"])
      ;0103 00:00:06 [DEBUG] {:event :kv-cluster/get-val, :val-key "64666725-a0e8-4f44-baf9-0ce3257dfec0", :phase :begin, :pid 410844, :tid 31}
      ;0105 00:00:06 [DEBUG] {:event :kv-cluster/get-val, :val-key "64666725-4a18-4f75-ad59-651677eb9438", :phase :begin, :pid 410844, :tid 31}

      (log/info "Done")
      #_(doseq [id ids]
          (d/pull (d/db conn) [:*] [:e/id id])
          #_(log/info "Pulled" id))
      #_(reset! datomic-conn conn)
      #_(hookd/install-return-consumer!
          "org.apache.tomcat.jdbc.pool.ConnectionPool"
          "getConnection"
          (fn [^Connection conn]
            (let [^Socket sock (u/conn->socket conn)]
              #_(when (<= 10 (swap! get-conn-count inc)))
              (log/info "ConnectionPool/getConnection returning socket" (nft/sock->readable sock))
              #_(u/watch-socket! "client" running? sock))))
      #_(let [dropped-sockets (atom #{})]
          (monkey/add-handler!
            proxy-state
            (fn [{:keys [op dst]}]
              (try
                (locking dropped-sockets
                  (when (and (= op :recv)
                             (= 0 (count @dropped-sockets)))
                    (Thread/sleep 500)                      ; make sure ACKs have arrived at the other side
                    (let [new-blocked (swap! dropped-sockets conj dst)]
                      (if brick?
                        (nft/drop-sockets! new-blocked)
                        (log/info "not dropping socket"))
                      #_(log/info "Blocked socket count:" (count new-blocked)))
                    #_(u/watch-socket! "proxy" running? dst)))
                (catch Throwable t
                  (log/error "unexpected error:" (ex-message t))))))
          (timbre/merge-config! {:min-level [[#{"datomic.kv-cluster"} :debug]
                                             [#{"datomic.process-monitor"
                                                "com.github.ivarref.spa-monkey"} :warn]
                                             [#{"datomic.*"} :info]
                                             [#{"com.github.ivarref.nft"} :warn]
                                             [#{"com.github.ivarref.*"} :info]
                                             [#{"*"} :info]]})
          (when brick?
            (u/named-future "pull-demo-1"
                            (try
                              (log/info "Starting pull" [:e/id "demo1"] "on dropped connection ...")
                              ; blocks/drops on kv-cluster/val 6436706a-911c-4547-b305-a7c82d49620e
                              (let [result (d/pull (d/db conn) [:*] [:e/id "demo1"])]
                                (log/info "Got pull result" result))
                              (Thread/sleep 1000)
                              (catch Throwable t
                                (log/error "an exception at last:" (ex-message t)))
                              (finally
                                nil)))
            (loop []
              (Thread/sleep 1000)
              (when (= 0 (count @dropped-sockets))
                (log/info "Waiting for drop")
                (recur))))
          (log/info (count @dropped-sockets) "dropped socket")
          (let [start-time (System/currentTimeMillis)
                fut (u/named-future "pull-demo-2"
                                    (reset! blocked-thread (Thread/currentThread))
                                    (try
                                      (d/q '[:find (pull ?e [:*])
                                             :in $
                                             :where
                                             [?e :db/doc ?doc]]
                                           (d/db conn))
                                      (log/info "OK :db/doc query")
                                      (log/info "Start pull" [:e/id "demo2"])
                                      (let [v (d/pull (d/db conn) [:*] [:e/id "demo2"])]
                                        (log/info "Done pull" [:e/id "demo2"])
                                        v)
                                      (catch Throwable t
                                        (log/error "Error:" (ex-message t)))))]
            (loop []
              (Thread/sleep ^long (or tick-rate-ms (if brick? 15000 1000)))
              (when (not (realized? fut))
                (log/info "Waited for pull" [:e/id "demo2"] "for" (str (Duration/ofMillis (- (System/currentTimeMillis)
                                                                                             start-time))))
                (recur)))
            (log/info "future was" @fut)))
      (when block?
        @(promise)))
    (catch Throwable t
      (log/error t "Unexpected exception:" (ex-message t)))
    (finally
      #_(monkey/stop! proxy-state)
      (nft/accept! :throw? false)
      (log/info "Exiting"))))
