(ns com.github.ivarref.spa-monkey
  (:require [clojure.tools.logging :as log])
  (:import (clojure.lang Atom)
           (java.io BufferedInputStream BufferedOutputStream Closeable IOException InputStream OutputStream)
           (java.net InetSocketAddress ServerSocket Socket SocketTimeoutException))
  (:gen-class))

(defn- close [^Closeable s]
  (when (and s (instance? Closeable s))
    (try
      (.close s)
      (catch IOException _
        nil))))

(defn- sock->readable [sock]
  (str "127.0.0.1:" (.getLocalPort sock)
       "->"
       "127.0.0.1:" (.getPort sock)))

(defn sock->remote-str [^Socket s]
  (cond (instance? InetSocketAddress (.getRemoteSocketAddress s))
        (let [addr (.getRemoteSocketAddress s)]
          (str (.getHostName addr) ":" (.getPort addr)))

        :else
        (str "unhandled:" s " of type " (.getClass (.getRemoteSocketAddress s)))))

(defn- add-socket [sock typ state]
  (-> state
      (update-in [:socks typ] (fnil conj #{}) sock)))

(defn- del-socket [sock typ state]
  (-> state
      (update-in [:socks typ] (fnil disj #{}) sock)))

(defn- running? [state]
  (when state
    (:running? @state)))

(defmacro new-thread [id state typ sock f]
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

(defn- forward-byte! [state ^OutputStream out rd from]
  (let [w (try
            (.write out ^int rd)
            (.flush out)
            1
            (catch Exception e
              (when (running? state)
                (log/warn "Exception while writing" from "to socket:" (ex-message e)))
              -1))]
    (if (= 1 w)
      true
      nil)))

(defn- pump-byte! [^Atom state id typ ^InputStream inp out ^Socket src ^Socket dst]
  (let [rd (try
             (.read inp)
             (catch Throwable e
               (when (running? state)
                 (log/warn id typ (sock->readable src) "Exception while reading socket:" (ex-message e) "of type" (.getClass e)))
               -1))]
    (if (= -1 rd)
      nil
      (do
        (doseq [[handler-id f] (get @state :handlers)]
          (let [retval (f {:id id :op typ :src src :dst dst})]
            (when (= :pop retval)
              (log/info "dropping handler" handler-id)
              (swap! state update :handlers dissoc handler-id))))
        (forward-byte! state out rd typ)))))

(defn- pump! [id typ state ^Socket src ^Socket dst]
  (try
    (with-open [inp (BufferedInputStream. (.getInputStream src))
                out (BufferedOutputStream. (.getOutputStream dst))]
      (loop []
        (when (and (running? state) (not (.isClosed src)) (not (.isClosed dst)))
          (when (pump-byte! state id typ inp out src dst)
            (recur)))))
    (catch Exception e
      (if (running? state)
        (throw e)
        nil))))

(defn- handle-connection! [id state ^Socket incoming]
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
    (new-thread id state :send remote (fn [_] (pump! id :send state incoming remote)))
    (pump! id :recv state remote incoming)))

(defn- accept [state ^ServerSocket server]
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
    (doseq [sock (get-in @state [:socks :recv])]
      (close sock))
    (doseq [sock (get-in @state [:socks :send])]
      (close sock))
    (Thread/sleep 100)))

(defn start! [state]
  (stop! state)
  (swap! state assoc
         :unhandled-exceptions #{}
         :running? true
         :id 0
         :handler-id 0
         :handlers {})
  (let [{:keys [bind port]
         :or   {bind "127.0.0.1"
                port 20009}} @state
        exception? (promise)]
    (log/info "Starting spa-monkey on" (str bind ":" port))
    (new-thread
      0
      state
      :server
      (try
        (doto
          (ServerSocket.)
          (.setReuseAddress true)
          (.bind (InetSocketAddress. ^String bind ^int port)))
        (catch Exception e
          (deliver exception? e)
          (throw e)))
      (fn [^ServerSocket server]
        (deliver exception? nil)
        (while (running? state)
          (when-let [sock (accept state server)]
            (let [id (:id (swap! state update :id (fnil inc 0)))]
              (new-thread id state :recv sock (fn [sock] (handle-connection! id state sock))))))
        #_(info "Server exiting")))
    @exception?))

(defn add-handler! [state handler]
  (swap! state (fn [{:keys [handler-id handlers] :as old-state}]
                 (let [id (inc (or handler-id 0))]
                   (-> old-state
                       (assoc :handler-id id)
                       (assoc :handlers (assoc handlers id handler)))))))

(comment
  (def st (atom {:remote-host "localhost"
                 :remote-port 5432
                 :port        54321})))

(comment
  (start! st))
