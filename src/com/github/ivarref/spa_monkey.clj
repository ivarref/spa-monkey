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

(defn sock->remote-str [^Socket s]
  (cond (instance? InetSocketAddress (.getRemoteSocketAddress s))
        (let [addr (.getRemoteSocketAddress s)]
          (str (.getHostName addr) ":" (.getPort addr)))

        :else
        (str "unhandled:" s " of type " (.getClass (.getRemoteSocketAddress s)))))

#_(defn add-uncaught-exception-handler! []
    (Thread/setDefaultUncaughtExceptionHandler
      (reify Thread$UncaughtExceptionHandler
        (uncaughtException [_ thread ex]
          (.print (System/err) "Uncaught exception on ")
          (.println (System/err) (.getName ^Thread thread))
          (.printStackTrace ^Throwable ex)
          (error "Uncaught exception on" (.getName ^Thread thread))
          nil))))

(defn add-socket [sock typ state]
  (-> state
      #_(update :sockets (fnil conj #{}) sock)
      (update-in [:socks typ] (fnil conj #{}) sock)))

(defn del-socket [sock typ state]
  (-> state
      #_(update :sockets (fnil disj #{}) sock)
      (update-in [:socks typ] (fnil disj #{}) sock)))

(defn running? [state]
  (when state
    (:running? @state)))

(defmacro new-thread [state typ sock f]
  `(let [state# ~state]
     (future
       (try
         (swap! state# (fn [old-state#] (update old-state# :threads (fnil conj #{}) (Thread/currentThread))))
         (let [sock# ~sock
               f# ~f]
           (try
             (swap! state# (partial add-socket sock# ~typ))
             (f# sock#)
             (finally
               (close sock#)
               (swap! state# (partial del-socket sock# ~typ)))))
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

(defn pump-byte! [state id from ^InputStream inp out ^Socket src ^Socket dst]
  (let [rd (try
             (.read inp)
             (catch Throwable e
               (when (running? state)
                 (log/warn "Exception while reading socket:" (ex-message e) "of type" (.getClass e)))
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
            (let [dest-ms (if (= ms 0)
                            Long/MAX_VALUE
                            (+ (System/currentTimeMillis) ms))]
              (log/warn "Blocking incoming"
                        (sock->remote-str src)
                        "for" (if (= ms 0)
                                Long/MAX_VALUE
                                ms)
                        "ms")
              (try
                (swap! state update :blocked-incoming-count (fnil inc 0))
                (loop []
                  (Thread/sleep 10)
                  (when (and (running? state)
                             (not (.isClosed src))
                             (not (.isClosed dst))
                             (> dest-ms (System/currentTimeMillis)))
                    (recur)))
                (finally
                  (swap! state update :blocked-incoming-count (fnil dec 0))))
              (cond
                (not (running? state))
                (log/info "Aborted sleeping due to shutdown requested")

                (and (running? state) (.isClosed src))
                (log/info "Aborted sleeping due to source closed connection")

                (and (running? state) (.isClosed dst))
                (log/info "Aborted sleeping due to destination closed connection")

                :else
                (log/info "Done sleeping, other state"))))
          (forward-byte! state out rd))))))

(defn pump! [state from ^Socket src ^Socket dst]
  (let [id (random-uuid)]
    (try
      (with-open [inp (BufferedInputStream. (.getInputStream src))
                  out (BufferedOutputStream. (.getOutputStream dst))]
        (loop []
          (when (and (running? state) (not (.isClosed src)) (not (.isClosed dst)))
            (when (pump-byte! state id from inp out src dst)
              (recur)))))
      (catch Exception e
        (if (running? state)
          (throw e)
          nil))
      (finally
        (swap! state update :drop (fnil disj #{}) id)))))

(defn handle-connection! [state ^Socket incoming]
  (log/info "Handle new incoming connection from" (sock->remote-str incoming))
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
    (new-thread state :remote remote (fn [_] (pump! state :incoming incoming remote)))
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
    (doseq [sock (get-in @state [:socks :server])]
      (close sock))
    (doseq [sock (get-in @state [:socks :remote])]
      (close sock))
    (doseq [sock (get-in @state [:socks :incoming])]
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
      :server
      (doto
        (ServerSocket.)
        (.setReuseAddress true)
        (.bind (InetSocketAddress. ^String bind ^int port)))
      (fn [^ServerSocket server]
        ;(info "Started server")
        (while (running? state)
          (when-let [sock (accept state server)]
            (new-thread state :incoming sock (fn [sock] (handle-connection! state sock)))))
        #_(info "Server exiting")))))

(defn block-all-incoming-plus-one [state]
  (assoc state :block-incoming (vec (repeat (inc (count (get-in state [:socks :incoming]))) 0))))

(defn block-all-incoming-plus-one! [state-atom]
  (swap! state-atom block-all-incoming-plus-one))

(comment
  (def st (atom {:remote-host "localhost"
                 :remote-port 5432
                 :port        54321})))

(comment
  (start! st))
