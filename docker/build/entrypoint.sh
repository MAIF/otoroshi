#!/bin/sh
cd ./otoroshi
./bin/otoroshi -Dhttp.port=8080 "$@" -XX:+IgnoreUnrecognizedVMOptions --add-modules=java.xml.bind --illegal-access=warn
