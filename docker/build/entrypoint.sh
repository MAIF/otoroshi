#!/bin/sh
cd ./otoroshi
./bin/otoroshi -Dhttp.port=8080 "$@"
