#!/usr/bin/env bash

set -euo pipefail

rm -rf gen/org
$HOME/Downloads/jextract-19/bin/jextract --source -t org.jextract point.h
mv org gen/.
