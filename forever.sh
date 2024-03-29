#!/usr/bin/env bash

set -euo pipefail
set -x

rm -vf src/com/github/ivarref/*.class 2> /dev/null || true

javac src/com/github/ivarref/GetSockOpt.java

clojure -X:forever

echo "Done"
