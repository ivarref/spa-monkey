#!/usr/bin/env bash

set -euo pipefail

rm src/com/github/ivarref/GetSockOpt.class || true
rm src/com/github/ivarref/GetSockOpt*.class || true

$HOME/.sdkman/candidates/java/20.ea.34-open/bin/javac \
--release 20 \
--enable-preview \
src/com/github/ivarref/GetSockOpt.java

$HOME/.sdkman/candidates/java/20.ea.34-open/bin/java \
--enable-preview \
--enable-native-access=ALL-UNNAMED \
--add-opens java.base/java.net=ALL-UNNAMED \
--add-opens java.base/java.io=ALL-UNNAMED \
-cp src com.github.ivarref.GetSockOpt

echo "Done"
