#!/usr/bin/env bash

trap 'trap - SIGTERM && kill -- -$$;' SIGINT SIGTERM EXIT

# https://jvns.ca/blog/2020/06/28/entr/
while true
do
{ git ls-files; git ls-files . --exclude-standard --others; } | entr -dz clojure -X:break-after-ack
done
