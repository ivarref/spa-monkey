(ns com.github.ivarref.spa-monkey
  (:import (java.time ZonedDateTime)
           (java.time.format DateTimeFormatter)
           (java.net ServerSocket InetSocketAddress Socket SocketTimeoutException)
           (java.io IOException BufferedInputStream Closeable BufferedOutputStream))
  (:gen-class))

(def ^DateTimeFormatter pattern (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss z"))
(defonce lock (Object.))

(defn close [^Closeable s]
  (when s
    (try
      (.close s)
      (catch IOException _
        nil))))

(defn debug [& args]
  (locking lock
    (apply println
           (into [(.format pattern (ZonedDateTime/now))
                  (str "[" (.getName (Thread/currentThread)) "]")
                  "DEBUG"]
                 args))))


(defn info [& args]
  (locking lock
    (apply println
           (into [(.format pattern (ZonedDateTime/now))
                  (str "[" (.getName (Thread/currentThread)) "]")
                  "INFO"]
                 args))))

(defn warn [& args]
  (locking lock
    (apply println
           (into [(.format pattern (ZonedDateTime/now))
                  (str "[" (.getName (Thread/currentThread)) "]")
                  "WARN"]
                 args))))

(defn error [& args]
  (locking lock
    (binding [*out* *err*]
      (apply println
             (into [(.format pattern (ZonedDateTime/now))
                    (str "[" (.getName (Thread/currentThread)) "]")
                    "ERROR"]
                   args)))))

(defn add-uncaught-exception-handler! []
  (Thread/setDefaultUncaughtExceptionHandler
    (reify Thread$UncaughtExceptionHandler
      (uncaughtException [_ thread ex]
        (.print (System/err) "Uncaught exception on ")
        (.println (System/err) (.getName ^Thread thread))
        (.printStackTrace ^Throwable ex)
        (error "Uncaught exception on" (.getName ^Thread thread))
        nil))))

(defn add-socket [sock state]
  (update state :sockets (fnil conj #{}) sock))

(defn del-socket [sock state]
  (update state :sockets (fnil disj #{}) sock))

(defn running? [state]
  (when state
    (:running? @state)))

(defmacro new-thread [state sock f]
  `(let [state# ~state]
     (future
       (try
         (swap! state# (fn [old-state#] (update old-state# :threads (fnil conj #{}) (Thread/currentThread))))
         (let [sock# ~sock
               f# ~f]
           (try
             (swap! state# (partial add-socket sock#))
             (f# sock#)
             (finally
               (close sock#)
               (swap! state# (partial del-socket sock#)))))
         (catch Throwable t#
           (error "Unhandled exception:" (ex-message t#))
           (swap! state# (fn [old-state#] (update old-state# :unhandled-exceptions (fnil conj #{}) t#))))
         (finally
           (swap! state# (fn [old-state#] (update old-state# :threads (fnil disj #{}) (Thread/currentThread)))))))))


(defn drop-forward-byte? [state id from]
  (or
    (contains? (:drop @state #{}) id)
    (let [[old new] (swap-vals! state (fn [old-state]
                                        (if (and (= from :remote)
                                                 (pos-int? (get old-state :drop-remote 0)))
                                          (-> old-state
                                              (update :drop-remote dec)
                                              (update :drop (fnil conj #{}) id))
                                          old-state)))]
      (not= old new))))


(defn drop-remote! [state]
  (swap! state update :drop-remote (fnil inc 0)))


(defn pump-byte! [state id from inp out]
  (let [rd (try
             (.read inp)
             (catch Exception e
               (when (running? state)
                 (warn "Exception while reading socket:" (ex-message e)))
               -1))]
    (if (= -1 rd)
      nil
      (if (drop-forward-byte? state id from)
        (do
          (swap! state update :dropped-bytes (fnil inc 0))
          true)
        (let [w (try
                  (.write out ^int rd)
                  (.flush out)
                  1
                  (catch Exception e
                    (when (running? state)
                      (warn "Exception while writing to socket:" (ex-message e)))
                    -1))]
          (if (= 1 w)
            true
            nil))))))


(defn pump! [state from ^Socket src ^Socket dst]
  (let [id (random-uuid)]
    (try
      (with-open [inp (BufferedInputStream. (.getInputStream src))
                  out (BufferedOutputStream. (.getOutputStream dst))]
        (loop []
          (when (and (running? state) (not (.isClosed src)) (not (.isClosed dst)))
            (when (pump-byte! state id from inp out)
              (recur)))))
      (finally
        (swap! state update :drop (fnil disj #{}) id)))))


(defn handle-connection! [state incoming]
  (let [{:keys [remote-host remote-port connection-timeout]
         :or   {remote-host        "127.0.0.1"
                remote-port        3117
                connection-timeout 3000}} @state
        remote (try
                 (doto (Socket.)
                   (.connect (InetSocketAddress. ^String remote-host ^int remote-port) connection-timeout))
                 (catch SocketTimeoutException ste
                   (error "Timeout connection to" (str remote-host ":" remote-port))
                   (throw ste)))]
    (new-thread state remote (fn [_] (pump! state :incoming incoming remote)))
    (pump! state :remote remote incoming)))

(defn accept [state server]
  (try
    (.accept server)
    (catch Exception e
      (when (running? state)
        (error "Error during .accept:" (ex-message e)))
      nil)))

(defn stop! [state]
  (when (running? state)
    (swap! state (fn [old-state] (assoc old-state :running? false)))
    (while (not-empty (:threads @state))
      (doseq [sock (:sockets @state)]
        (close sock))
      (Thread/sleep 100))))

(defn start! [state]
  (stop! state)
  (swap! state (fn [old-state] (assoc old-state :dropped-bytes 0 :unhandled-exceptions #{} :running? true)))
  (let [{:keys [bind port]
         :or   {bind "127.0.0.1"
                port 20009}} @state]
    (new-thread
      state
      (doto
        (ServerSocket.)
        (.setReuseAddress true)
        (.bind (InetSocketAddress. ^String bind ^int port)))
      (fn [^ServerSocket server]
        ;(info "Started server")
        (while (running? state)
          (when-let [sock (accept state server)]
            (new-thread state sock (fn [sock] (handle-connection! state sock)))))
        #_(info "Server exiting")))))
