#!/bin/bash

set -ex

#printf "datomic/init\ndocker-compose.yml\n" | entr -czrd \
docker rm -f postgres || true
docker rm -f datomic || true
docker-compose up --force-recreate --no-color --build --remove-orphans --abort-on-container-exit
