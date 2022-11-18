#!/usr/bin/env bash

set -ex

#openssl req -new -text -passout pass:abcd -subj /CN=localhost -out server.req -keyout privkey.pem
#openssl rsa -in privkey.pem -passin pass:abcd -out server.key
#openssl req -x509 -in server.req -text -key server.key -out server.crt

docker rm -f postgres || true
docker run -u $(id -u):$(id -g) -it --name postgres --env POSTGRES_PASSWORD="password" --network=host --rm \
    -v $PWD/postgres:/var/lib/postgresql/data \
    -v "$(pwd)/server.crt:/var/lib/postgresql/server.crt:ro" \
    -v "$(pwd)/server.key:/var/lib/postgresql/server.key:ro" \
    -e POSTGRES_HOST_AUTH_METHOD=trust \
    postgres:13.2 \
    -c ssl=on -c ssl_cert_file=/var/lib/postgresql/server.crt -c ssl_key_file=/var/lib/postgresql/server.key
