#!/usr/bin/env bash

trap "kill 0" SIGINT

(cd $HOME/code/quickstart; hugo server -D --disableFastRender &)

printf network-failures.md | entr cp -fv ./network-failures.md ~/code/quickstart/content/posts/network-failures.md
