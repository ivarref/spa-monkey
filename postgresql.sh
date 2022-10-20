#!/usr/bin/env bash

set -ex

docker rm -f postgres || true
docker run -it --name postgres --env POSTGRES_PASSWORD="password" --network=host --rm postgres:13.2
