(ns com.github.ivarref.nft
  (:require [babashka.process :refer [$ check]]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import (com.github.ivarref GetSockOpt)
           (java.net Socket)))

(defn nft-sudo [filename]
  (log/info "Executing sudo nft -f" filename "...")
  (let [fut (future (try
                      (as-> ^{:out :string :err :string} ($ sudo nft -f ~filename) v
                            (check v))
                      :ok
                      (catch Throwable t
                        t)))
        res (deref fut (* 10 60000) ::timeout)]
    (cond
      (= ::timeout res)
      (do
        (log/info "Executing sudo nft -f" filename "... Timeout!")
        (throw (ex-info "sudo nft timeout" {})))

      (= :ok res)
      (do
        (log/info "Executing sudo nft -f" filename "... OK!")
        true)

      (instance? Throwable res)
      (do
        (log/error res "Executing sudo nft -f" filename "... Error:" (ex-message res))
        (throw res))

      :else
      (do
        (log/error "Unhandled state. Got res:" res)
        (throw (ex-info "Unexpected state" {:result res}))))))

(defn accept! [& {:keys [throw?]
                  :or   {throw? true}}]
  (log/info "Clear all packet filters ...")
  (try
    (nft-sudo "accept.txt")
    (catch Throwable t
      (log/error t "Error during nft/accept:" (ex-message t))
      (if throw?
        (throw t)
        (log/warn "Not re-throwing exception")))))

(defn sock->drop [^Socket s]
  (str "tcp dport " (.getPort s) " "
       "tcp sport " (.getLocalPort s) " "
       "ip saddr 127.0.0.1 "
       "ip daddr 127.0.0.1 drop;"))

(defn drop-str! [s]
  (if (try
        (spit "drop.txt" s)
        true
        (catch Throwable t
          (log/error t "Writing drop.txt failed:" (ex-message t))
          false))
    (nft-sudo "drop.txt")
    (do
      (log/error "Not invoking nft!")
      false)))

(defn sock->readable [^Socket sock]
  (str "" (.getLocalPort sock)
       ":"
       #_"localhost:" (.getPort sock)))

(defn drop-sock! [sock]
  (let [drop-txt (sock->drop sock)
        drop-file (str/join "\n"
                            ["flush ruleset"
                             "table ip filter {"
                             "chain output {"
                             "type filter hook output priority filter;"
                             "policy accept;"
                             drop-txt
                             "}"
                             "}"])]
    (log/info "Dropping TCP packets for" (sock->readable sock) "fd" (GetSockOpt/getFd sock))
    (drop-str! drop-file)))

(defn drop-sockets! [sockets]
  (let [sockets (into [] sockets)
        drop-txt (str/join " " (mapv sock->drop sockets))
        drop-file (str/join "\n"
                            ["flush ruleset"
                             "table ip filter {"
                             "chain output {"
                             "type filter hook output priority filter;"
                             "policy accept;"
                             drop-txt
                             "}"
                             "}"])]
    (doseq [sock sockets]
      (log/info "Dropping TCP packets for" (sock->readable sock) "fd" (GetSockOpt/getFd sock)))
    (drop-str! drop-file)))
