#!/bin/sh

export JAVA_OPTS="$JAVA_OPTS -XX:+IgnoreUnrecognizedVMOptions --illegal-access=warn"
if [ -z ${OTOROSHI_PLUGINS_DIR_PATH+x} ]; 
then 
  echo "Bootstrapping otoroshi without additional plugins"
  java -Dhttp.port=8080 -Dhttps.port=8443 -jar otoroshi.jar
else 
  echo "Bootstrapping otoroshi with additional plugins from ${OTOROSHI_PLUGINS_DIR_PATH}"
  java -cp "./otoroshi.jar:${OTOROSHI_PLUGINS_DIR_PATH}/*" play.core.server.ProdServerStart
fi

