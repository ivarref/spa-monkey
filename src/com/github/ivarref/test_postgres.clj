(ns com.github.ivarref.test-postgres
  (:require [com.github.ivarref.spa-monkey :as spa])
  (:import (org.apache.tomcat.jdbc.pool PoolProperties DataSourceProxy)))

(defonce st (atom {:remote-host "127.0.0.1"
                   :remote-port 5432
                   :bind        "127.0.0.1"
                   :port        54320}))

(def jdbc-password (System/getenv "POSTGRES_PASSWORD"))

(def uri
  (str "jdbc:postgresql://127.0.0.1:5432/postgres?user=postgres"
       "&password=" jdbc-password))

(def uri-proxy
  (str "jdbc:postgresql://127.0.0.1:54320/postgres?user=postgres"
       "&password=" jdbc-password))

(defn create-datasource! [uri]
  (spa/start! st)
  (let [props (PoolProperties.)]
    (.setDriverClassName props "org.postgresql.Driver")
    (.setUrl props uri)
    (.setValidationQuery props "select 1")
    (.setInitialSize props 2)
    (.setTestOnBorrow props true)
    (.setValidationInterval props 5000)
    (let [ds (DataSourceProxy. props)]
      (with-open [conn (.getConnection ds)]
        (println "got connection"))
      ds)))

(comment
  (def ds (create-datasource! uri-proxy)))

(comment
  (with-open [conn (.getConnection ds)]
    (println "got connection:" conn)))


(defn test-ds []
  (with-open [ds (create-datasource! uri)]
    (with-open [conn (.getConnection ds)]
      (println "got connection! ^__^"))))
