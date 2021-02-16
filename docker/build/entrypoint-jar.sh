#!/bin/sh

export JAVA_OPTS="$JAVA_OPTS -XX:+IgnoreUnrecognizedVMOptions --illegal-access=warn"
java -Dhttp.port=8080 -Dhttps.port=8443 -jar otoroshi.jar
