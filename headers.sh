#!/usr/bin/env bash

set -euo pipefail

mkdir gen/ || true
rm -rf gen/org || true
$HOME/Downloads/jextract-19/bin/jextract --source -t org.jextract point.h
mv org gen/.
