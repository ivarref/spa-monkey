#!/usr/bin/env bash

trap "kill 0" SIGINT

(cd $HOME/code/blog && hugo server -D --disableFastRender &)

xdg-open "http://localhost:1313/posts/network-failures/"
printf network-failures.md | entr cp -fv ./network-failures.md ~/code/blog/content/posts/network-failures.md
