#!/bin/sh

export JAVA_OPTS="$JAVA_OPTS -XX:+IgnoreUnrecognizedVMOptions --illegal-access=warn"
if [ -z ${OTOROSHI_PLUGINS_DIR_PATH+x} ]; 
then 
  echo "Bootstrapping otoroshi without additional plugins"
  java $JAVA_OPTS --add-opens java.base/javax.net.ssl=ALL-UNNAMED --add-opens=java.base/sun.net.www.protocol.file=ALL-UNNAMED --add-exports=java.base/sun.security.x509=ALL-UNNAMED --add-opens=java.base/sun.security.ssl=ALL-UNNAMED -Dlog4j2.formatMsgNoLookups=True -Dhttp.port=8080 -Dhttps.port=8443 -jar otoroshi.jar
else 
  echo "Bootstrapping otoroshi with additional plugins from ${OTOROSHI_PLUGINS_DIR_PATH}"
  java $JAVA_OPTS --add-opens java.base/javax.net.ssl=ALL-UNNAMED --add-opens=java.base/sun.net.www.protocol.file=ALL-UNNAMED --add-exports=java.base/sun.security.x509=ALL-UNNAMED --add-opens=java.base/sun.security.ssl=ALL-UNNAMED -Dlog4j2.formatMsgNoLookups=True -Dhttp.port=8080 -Dhttps.port=8443 -cp "./otoroshi.jar:${OTOROSHI_PLUGINS_DIR_PATH}/*" play.core.server.ProdServerStart
fi

