#!/bin/sh
cd /otoroshi
./graalvm/bin/java -Dhttp.port=8080 -jar otoroshi.jar