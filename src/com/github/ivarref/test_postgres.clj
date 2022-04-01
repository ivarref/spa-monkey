(ns com.github.ivarref.test-postgres
  (:require [com.github.ivarref.spa-monkey :as spa]
            [taoensso.tufte :as tufte])
  (:import (org.apache.tomcat.jdbc.pool PoolProperties DataSourceProxy)
           (java.sql Connection)))

(defonce st (atom {:remote-host "127.0.0.1" :remote-port 5432
                   :bind        "127.0.0.1" :port 54320}))

(def jdbc-password (System/getenv "POSTGRES_PASSWORD"))

(def uri (str "jdbc:postgresql://127.0.0.1:5432/postgres?user=postgres&password=" jdbc-password))

(defn create-datasource! [uri]
  (spa/start! st)
  (let [props (PoolProperties.)]
    (.setDriverClassName props "org.postgresql.Driver")
    (.setUrl props uri)
    (.setValidationQuery props "select 1")
    (.setInitialSize props 8)
    (.setTestOnBorrow props false)
    #_(.setValidationInterval props 250)
    (let [ds (DataSourceProxy. props)
          start-time (System/nanoTime)]
      (with-open [conn (.getConnection ds)]
        (let [spent-nanos (- (System/nanoTime) start-time)]
          #_(println "Bootstrapped datasource in" (format "%.1f" (double (/ spent-nanos 1e6))) "ms"))
        nil #_(println "got connection"))
      ds)))

(defn close! [ds]
  (swap! st assoc :running? false)
  (.close ^DataSourceProxy ds)
  (spa/stop! st))

(comment
  (def ds (create-datasource! uri)))

(comment
  (with-open [conn (.getConnection ds)]
    (println "got connection:" conn)))


(defn get-conn [^DataSourceProxy ds]
  (let [start-time (System/nanoTime)]
    (with-open [^Connection conn (.getConnection ds)]
      (let [valid? (tufte/p "isValid" (.isValid conn 250))
            spent-time (double (/ (- (System/nanoTime) start-time) 1e6))]
        (assert valid?)
        #_(println "Got connection in" (format "%.1f" spent-time) "ms")
        (int spent-time)))))

; proxy: => {0 830, 1 7, 24 1, 42 44, 43 88, 44 2, 45 1, 46 18, 47 4, 86 1, 90 3, 503 1}
; direct: => {0 1000}

(defn test-ds []
  (let [ds (create-datasource! uri)
        n 100]
    (try
      (let [[res stats] (tufte/profiled
                          {}
                          (into (sorted-map)
                                (frequencies (loop [res []]
                                               (if (= (count res) n)
                                                 res
                                                 (recur (conj res (get-conn ds))))))))]
        (println (tufte/format-pstats @stats))
        res)
      (finally
        (close! ds)))))
