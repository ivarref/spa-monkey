#!/usr/bin/env bash

if ! [ $(id -u) = 0 ]; then
   echo "The script need to be run as root." >&2
   exit 1
fi

bash -c 'echo 6 > /proc/sys/net/ipv4/tcp_retries2'

clojure -X:break-after-ack
