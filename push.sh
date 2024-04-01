#!/usr/bin/env bash

printf network-failures.md | entr cp -fv ./network-failures.md ~/code/quickstart/content/posts/network-failures.md
