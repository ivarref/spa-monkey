#!/usr/bin/env bash

if ! [ $(id -u) = 0 ]; then
   echo "The script need to be run as root." >&2
   exit 1
fi

BREAK="false"
trap 'trap - SIGTERM && export BREAK="true"; kill -- -$$;' SIGINT SIGTERM EXIT

# https://jvns.ca/blog/2020/06/28/entr/
while [[ "${BREAK}" == "false" ]]
do
  { git ls-files; git ls-files . --exclude-standard --others; } | grep -v "logs/\|^\." | entr -dz clojure -X:break-after-ack
  sleep 3
  if [[ "${BREAK}" == "false" ]]
  then
    echo "*** *** *** Restarting *** *** ***"
  fi
done
echo "Exiting"
