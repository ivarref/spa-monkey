(ns com.github.ivarref.getsockopt)

#_(defn handle-client [running? ^Socket sock]
    (try
      (with-open [in (-> sock
                         .getInputStream
                         (InputStreamReader. StandardCharsets/UTF_8)
                         BufferedReader.)
                  out (PrintWriter. (.getOutputStream sock) true StandardCharsets/UTF_8)]
        (loop []
          (when-let [line (.readLine in)]
            (log/info "Server received:" line)
            (Thread/sleep 1000)
            (.println out line)
            (Thread/sleep 1000)
            (when @running? (recur)))))
      (finally
        (.close sock))))

#_(defn accept-loop [running? server-socket]
    (try
      (when @running?
        (log/info "Waiting for connections ..."))
      (while @running?
        (let [new-client (.accept server-socket)]
          (watch-socket! "server" new-client)
          (future (handle-client running? new-client))))
      (log/info "Server exiting")
      (catch Throwable t
        (log/error "Unexpected error:" (ex-message t)))))

#_(defn getsockopt-demo [_]
    (try
      (log-init/init-logging! {:levels [[#{"datomic.*"} :warn]
                                        [#{"com.github.ivarref.*"} :info]
                                        [#{"*"} :info]]})
      (let [tcp-retry-file "/proc/sys/net/ipv4/tcp_retries2"]
        (log/info tcp-retry-file "is" (str/trim (slurp tcp-retry-file))))
      (let [ss (doto (ServerSocket.)
                 (.setReuseAddress true)
                 (.bind (InetSocketAddress. "localhost" 8080)))
            port (.getLocalPort ss)
            running? (atom true)]
        (log/info "Listening at port" port)
        (future (accept-loop running? ss))
        (Thread/sleep 100)
        (with-open [sock (Socket. "localhost" ^int port)
                    out (PrintWriter. (.getOutputStream sock) true StandardCharsets/UTF_8)
                    in (-> sock
                           .getInputStream
                           (InputStreamReader. StandardCharsets/UTF_8)
                           BufferedReader.)]
          (watch-socket! "client" sock)
          (.println out "Hello world!")
          (log/info "client sent Hello world!")
          (when-let [line (.readLine in)]
            (log/info "client received:" line)))
        #_(let [client ()]))
      (catch Throwable t
        (log/error t "Unexpected exception:" (ex-message t)))))
