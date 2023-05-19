(ns com.github.ivarref.brick
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [com.github.ivarref.log-init :as log-init]
            [com.github.ivarref.nft :as nft]
            [com.github.ivarref.spa-monkey :as monkey]
            [com.github.ivarref.utils :as u]
            [datomic.api :as d]
            [taoensso.timbre :as timbre])
  (:import (datomic Connection)
           (java.lang ProcessHandle)
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
      (doseq [[idx tx-chunk] (map-indexed (fn [idx e] [(inc idx) e]) (partition-all 10000 tx))]
        @(d/transact conn (vec tx-chunk))
        (log/info "Inserted" (* idx 10000) "items,"
                  (str
                    (int (* 100
                            (/ (* idx 10000)
                               n)))
                    "% done")))
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

; is the lock block per segment, or global somehow?
; appears to be per segment (or similar)

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
    (when-let [e (monkey/start! proxy-state)]
      (log/error e "Could not start proxy:" (ex-message e))
      (System/exit 1))
    (timbre/merge-config! {:min-level [[#{"datomic.kv-cluster"} :warn]]})
    (let [conn (u/get-conn :db-name "brick" :port 54321)]
      (timbre/merge-config! {:min-level [[#{"datomic.kv-cluster"} :warn]]})
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
      (log/info "Pull 1 done")

      ;(log/info "Starting pull 250000")
      ;(d/pull (d/db conn) [:*] [:e/id "250000"])
      ;0103 00:00:06 [DEBUG] {:event :kv-cluster/get-val, :val-key "64666720-94c1-4427-bbee-6d4d257ecbf6", :phase :begin, :pid 410978, :tid 31}
      ;0105 00:00:06 [DEBUG] {:event :kv-cluster/get-val, :val-key "64666720-9fee-4713-b532-1ada76a62edb", :phase :begin, :pid 410978, :tid 31}

      ;(log/info "Starting pull 500000")
      ;(d/pull (d/db conn) [:*] [:e/id "500000"])
      ;0103 00:00:06 [DEBUG] {:event :kv-cluster/get-val, :val-key "64666725-a0e8-4f44-baf9-0ce3257dfec0", :phase :begin, :pid 410844, :tid 31}
      ;0105 00:00:06 [DEBUG] {:event :kv-cluster/get-val, :val-key "64666725-4a18-4f75-ad59-651677eb9438", :phase :begin, :pid 410844, :tid 31}

      (let [dropping? (promise)]
        (monkey/add-handler!
          proxy-state
          (fn [{:keys [op dst]}]
            (try
              (locking dropping?
                (when (and (= op :recv)
                           (false? (realized? dropping?)))
                  (Thread/sleep 500)                        ; make sure ACKs have arrived at the other side
                  (nft/drop-sock! dst)
                  (Thread/sleep 500)
                  (deliver dropping? true)))
              (catch Throwable t
                (log/error "unexpected error:" (ex-message t))))))
        (timbre/merge-config! {:min-level [[#{"datomic.kv-cluster"} :debug]
                                           [#{"datomic.process-monitor"
                                              "com.github.ivarref.spa-monkey"} :warn]
                                           [#{"datomic.*"} :info]
                                           [#{"com.github.ivarref.nft"} :info]
                                           [#{"com.github.ivarref.*"} :info]
                                           [#{"*"} :info]]})
        (u/named-future "pull-250000-dropped"
                        (try
                          (log/info "Starting pull 250000 on dropped connection ...")
                          ; blocks/drops on kv-cluster/val 6436706a-911c-4547-b305-a7c82d49620e
                          (let [result (d/pull (d/db conn) [:*] [:e/id "250000"])]
                            (log/info "Got pull result" result))
                          (Thread/sleep 1000)
                          (catch Throwable t
                            (log/error "An exception at last:" (ex-message t)))
                          (finally
                            nil)))
        (log/info "Waiting for dropping to start ...")
        @dropping?
        (log/info "Dropping started, starting new query")
        (let [fut (u/named-future "pull-250000-monitor-entry"
                                  (try
                                    (d/q '[:find (pull ?e [:*])
                                           :in $
                                           :where
                                           [?e :db/doc ?doc]]
                                         (d/db conn))
                                    (log/info "OK :db/doc query")
                                    (log/info "Start pull" [:e/id "500000"])
                                    (let [v (d/pull (d/db conn) [:*] [:e/id "500000"])]
                                      (log/info "Done pull" [:e/id "500000"] v)
                                      (log/info "Start pull" [:e/id "250000"])
                                      (d/pull (d/db conn) [:*] [:e/id "250000"])
                                      (log/info "Done pull" [:e/id "250000"]))
                                    (catch Throwable t
                                      (log/error "Unexpected error:" (ex-message t)))))
              start-time (System/currentTimeMillis)]
          (loop []
            (Thread/sleep ^long (or tick-rate-ms (if brick? 15000 1000)))
            (when (not (realized? fut))
              (log/info "Waited for pull" [:e/id "250000"] "for" (str (Duration/ofMillis (- (System/currentTimeMillis)
                                                                                            start-time))))
              (recur)))
          (log/info "future was" @fut))
        (when block?
          @(promise))))
    (catch Throwable t
      (log/error t "Unexpected exception:" (ex-message t)))
    (finally
      (monkey/stop! proxy-state)
      (nft/accept! :throw? false)
      (log/info "Exiting"))))
