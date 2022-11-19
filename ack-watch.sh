#!/usr/bin/env bash

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
