#!/usr/bin/env bash

set -euo pipefail

rm -fv src/com/github/ivarref/*.class 2> /dev/null || true

javac src/com/github/ivarref/GetSockOpt.java

clojure -X:tcp-retry

echo "Done"
