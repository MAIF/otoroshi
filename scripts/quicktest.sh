#!/bin/sh

LOCATION=`pwd`

cd $LOCATION/otoroshi
rm -f ./target/scala-2.11/otoroshi.jar
sbt "assembly"
java -jar ./target/scala-2.11/otoroshi.jar &

sleep 20

../clients/cli/target/debug/otoroshicli services create --group default --id oto-test --name oto-test --env prod \
  --domain foo.bar --subdomain test \
  --target http://127.0.0.1:8081 \
  --target http://127.0.0.1:8082 \
  --target http://127.0.0.1:8083 \
  --public-pattern '/.*' --no-force-https >> /dev/null
 
../clients/cli/target/debug/otoroshicli config update \
  --max-concurrent-requests 9999999 \
  --per-ip-throttling-quota 9999999 \
  --throttling-quota 9999999  >> /dev/null

echo "Warm up for 10 sec ..."
wrk -t1 -c1 -d10s -H "Host: test.foo.bar" http://127.0.0.1:8080/ >> /dev/null
echo "Actual test ..."
wrk -t2 -c200 -d60s -H "Host: test.foo.bar" --latency http://127.0.0.1:8080/

echo "Kill otoroshi"
ps aux | grep java | grep otoroshi.jar | awk '{print $2}' | xargs kill
