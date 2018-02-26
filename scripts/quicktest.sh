#!/bin/sh

LOCATION=`pwd`
SCALA_VERSION="2.11"

kill_otoroshi () {
  echo "Kill otoroshi"
  ps aux | grep java | grep otoroshi.jar | awk '{print $2}' | xargs kill  >> /dev/null
  rm -f $LOCATION/otoroshi/RUNNING_PID
}

check_scala_version () {
  if [ -d "$LOCATION/otoroshi/target/scala-2.12" ]; then
    SCALA_VERSION="2.12"
  else
    SCALA_VERSION="2.11"
  fi
  echo "Scala version is: $SCALA_VERSION"
}

kill_otoroshi
check_scala_version
echo "Removing old version of Otoroshi"
cd $LOCATION/otoroshi
rm -f $LOCATION/otoroshi/target/scala-$SCALA_VERSION/otoroshi.jar

echo "Building Otoroshi"
sbt ";clean;compile;assembly"
check_scala_version
java -jar $LOCATION/otoroshi/target/scala-$SCALA_VERSION/otoroshi.jar &

sleep 30

echo "Configuring Otoroshi"
$LOCATION/clients/cli/target/debug/otoroshicli services create \
  --group default \
  --id oto-test \
  --name oto-test \
  --env prod \
  --domain foo.bar \
  --subdomain test \
  --target http://127.0.0.1:8081 \
  --target http://127.0.0.1:8082 \
  --target http://127.0.0.1:8083 \
  --public-pattern '/.*' \
  --no-force-https >> /dev/null
 
$LOCATION/clients/cli/target/debug/otoroshicli config update \
  --max-concurrent-requests 9999999 \
  --per-ip-throttling-quota 9999999 \
  --throttling-quota 9999999  >> /dev/null

echo "Warm up for 70 sec ..."
wrk -t2 -c200 -d70s -H "Host: test.foo.bar" http://127.0.0.1:8080/ >> /dev/null
echo "Actual test ..."
wrk -t2 -c200 -d60s -H "Host: test.foo.bar" --latency http://127.0.0.1:8080/

kill_otoroshi
