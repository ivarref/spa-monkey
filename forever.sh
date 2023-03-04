#!/usr/bin/env bash

set -euo pipefail

rm src/com/github/ivarref/GetSockOpt.class || true
rm src/com/github/ivarref/GetSockOpt*.class || true

javac \
--release 20 \
--enable-preview \
src/com/github/ivarref/GetSockOpt.java

clojure -X:forever

echo "Done"