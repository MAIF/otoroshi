#!/bin/sh

echo "Prepare test ...."

LOCATION=`pwd`

if [ ! -f "$LOCATION/otoroshi.jar" ]; then
    wget -q --show-progress 'https://dl.bintray.com/maif/binaries/otoroshi.jar/latest/otoroshi.jar'
fi 
if [ ! -f "$LOCATION/otoroshicli.toml" ]; then
    wget -q --show-progress https://raw.githubusercontent.com/MAIF/otoroshi/master/clients/cli/otoroshicli.toml
fi
if [ ! -f "$LOCATION/otoroshicli" ]; then	
	wget -q --show-progress https://dl.bintray.com/maif/binaries/mac-otoroshicli/latest/otoroshicli
fi

USE_CACHE=true JAVA_OPTS='-Xms2G -Xmx8G' java -jar otoroshi.jar >> /dev/null &

docker run -d -p "8081:80" emilevauge/whoami  >> /dev/null
docker run -d -p "8082:80" emilevauge/whoami  >> /dev/null
docker run -d -p "8083:80" emilevauge/whoami  >> /dev/null

sleep 5

chmod +x otoroshicli

./otoroshicli services create --group default --id oto-test --name oto-test --env prod \
  --domain foo.bar --subdomain test \
  --target http://127.0.0.1:8081 \
  --target http://127.0.0.1:8082 \
  --target http://127.0.0.1:8083 \
  --public-pattern '/.*' --no-force-https >> /dev/null
 
./otoroshicli config update \
  --max-concurrent-requests 9999999 \
  --per-ip-throttling-quota 9999999 \
  --throttling-quota 9999999  >> /dev/null

echo "Running test at `date`"
# wrk -t20 -c1000 -d60s -H "Host: test.foo.bar" --latency http://127.0.0.1:8080/
wrk -t2 -c200 -d60s -H "Host: test.foo.bar" --latency http://127.0.0.1:8080/

docker kill $(docker ps -q) >> /dev/null
killall java  >> /dev/null
rm -f RUNNING_PID
rm -rf logs


