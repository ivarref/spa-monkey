#!/usr/bin/env bash

set -euo pipefail

mkdir gen/ || true
rm -rf gen/org || true
$HOME/Downloads/jextract-22/bin/jextract -l distance -t org.jextract point.h
mv org gen/.
