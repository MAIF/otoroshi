#!/bin/sh
#cd ./otoroshi
#export JAVA_OPTS="$JAVA_OPTS -XX:+IgnoreUnrecognizedVMOptions --illegal-access=warn"
#./bin/otoroshi -Dhttp.port=8080 "$@" 

java -Dapp.adminPassword=password -jar /usr/app/otoroshi.jar
