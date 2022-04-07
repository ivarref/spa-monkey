(ns com.github.ivarref.spa-monkey
  (:require [clojure.tools.logging :as log])
  (:import (java.net ServerSocket InetSocketAddress Socket SocketTimeoutException)
           (java.io IOException BufferedInputStream Closeable BufferedOutputStream OutputStream InputStream))
  (:gen-class))

(defn close [^Closeable s]
  (when s
    (try
      (.close s)
      (catch IOException _
        nil))))

#_(defn add-uncaught-exception-handler! []
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
           (log/error "Unhandled exception:" (ex-message t#))
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
                                          old-state)))
          drop? (not= old new)]
      (when drop?
        (log/warn "Start dropping bytes. Id:" id))
      drop?)))



(defn drop-remote! [state]
  (swap! state update :drop-remote (fnil inc 0)))

(defn block-remote! [state ms]
  (swap! state update :block-remote (fnil conj []) ms))

(defn block-incoming! [state ms]
  (swap! state update :block-incoming (fnil conj []) ms))

(defn forward-byte! [state ^OutputStream out rd]
  (let [w (try
            (.write out ^int rd)
            (.flush out)
            1
            (catch Exception e
              (when (running? state)
                (log/warn "Exception while writing to socket:" (ex-message e)))
              -1))]
    (if (= 1 w)
      true
      nil)))

(defn block-incoming? [state-atom from]
  (when (= :incoming from)
    (->> (swap-vals! state-atom update :block-incoming (comp vec (fnil rest [])))
         (first)
         (:block-incoming)
         (first))))

(defn pump-byte! [state id from ^InputStream inp out]
  (let [rd (try
             (.read inp)
             (catch Exception e
               (when (running? state)
                 (log/warn "Exception while reading socket:" (ex-message e)))
               -1))]
    (if (= -1 rd)
      nil
      (cond
        (drop-forward-byte? state id from)
        (do
          (swap! state update :dropped-bytes (fnil inc 0))
          true)

        :else
        (do
          (when-let [ms (block-incoming? state from)]
            (log/warn "Blocking incoming for" ms "ms")
            (Thread/sleep ms))
          (forward-byte! state out rd))))))


(defn pump! [state from ^Socket src ^Socket dst]
  (let [id (random-uuid)]
    (try
      (with-open [inp (BufferedInputStream. (.getInputStream src))
                  out (BufferedOutputStream. (.getOutputStream dst))]
        (loop []
          (when (and (running? state) (not (.isClosed src)) (not (.isClosed dst)))
            (when (pump-byte! state id from inp out)
              (recur)))))
      (catch Exception e
        (if (running? state)
          (throw e)
          nil))
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
                   (log/error "Timeout connection to" (str remote-host ":" remote-port))
                   (throw ste)))]
    (new-thread state remote (fn [_] (pump! state :incoming incoming remote)))
    (pump! state :remote remote incoming)))

(defn accept [state ^ServerSocket server]
  (try
    (.accept server)
    (catch Exception e
      (when (running? state)
        (log/error "Error during .accept:" (ex-message e)))
      nil)))

(defn stop! [state]
  (swap! state assoc :running? false)
  (while (not-empty (:threads @state))
    (doseq [sock (:sockets @state)]
      (close sock))
    (Thread/sleep 100)))

(defn start! [state]
  (stop! state)
  (swap! state assoc
         :dropped-bytes 0
         :unhandled-exceptions #{}
         :running? true
         :block-incoming []
         :drop-remote 0)
  (let [{:keys [bind port]
         :or   {bind "127.0.0.1"
                port 20009}} @state]
    (log/info "Starting spa-monkey on" (str bind ":" port))
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
