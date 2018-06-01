#!/bin/sh
cd /otoroshi
./graalvm/bin/java "$JAVA_OPTS" -Dhttp.port=8080 -jar otoroshi.jar "$@"