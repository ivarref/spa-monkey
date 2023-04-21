#!/usr/bin/env bash

set -euo pipefail

rm src/com/github/ivarref/GetSockOpt.class 2> /dev/null || true
rm src/com/github/ivarref/GetSockOpt*.class 2> /dev/null || true

$HOME/.sdkman/candidates/java/20.ea.34-open/bin/javac \
--release 20 \
--enable-preview \
src/com/github/ivarref/GetSockOpt.java

clojure -X:tcp-retry

echo "Done"
