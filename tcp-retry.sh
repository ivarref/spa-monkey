#!/usr/bin/env bash

set -euo pipefail

if [ "$(id -u)" -eq 0 ]; then echo "Please don't run as root." >&2; exit 1; fi

rm -fv src/com/github/ivarref/*.class 2> /dev/null || true

javac src/com/github/ivarref/GetSockOpt.java

clojure -X:tcp-retry

echo "Done"
