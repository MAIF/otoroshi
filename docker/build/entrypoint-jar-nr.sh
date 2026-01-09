#!/bin/sh
set -eu

export JAVA_OPTS="${JAVA_OPTS:-} -XX:+IgnoreUnrecognizedVMOptions --illegal-access=warn"
echo "JAVA_OPTS: ${JAVA_OPTS}"

BASE_JAVA_OPTS="$JAVA_OPTS \
 --add-opens java.base/javax.net.ssl=ALL-UNNAMED \
 --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
 --add-opens=java.base/sun.net.www.protocol.file=ALL-UNNAMED \
 --add-exports=java.base/sun.security.x509=ALL-UNNAMED \
 --add-opens=java.base/sun.security.ssl=ALL-UNNAMED \
 -Dlog4j2.formatMsgNoLookups=true \
 -Dhttp.port=8080 -Dhttps.port=8443"

if [ -z "${OTOROSHI_PLUGINS_DIR_PATH:-}" ]; then
  echo "Bootstrapping otoroshi without additional plugins"
  exec java ${BASE_JAVA_OPTS} -jar otoroshi.jar
else
  echo "Bootstrapping otoroshi with additional plugins from ${OTOROSHI_PLUGINS_DIR_PATH}"
  exec java ${BASE_JAVA_OPTS} -cp "./otoroshi.jar:${OTOROSHI_PLUGINS_DIR_PATH}/*" play.core.server.ProdServerStart
fi