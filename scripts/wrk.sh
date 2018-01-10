#!/bin/sh

tune_linux () {
  sysctl -w fs.file-max="9999999"
  sysctl -w fs.nr_open="9999999"
  sysctl -w net.core.netdev_max_backlog="4096"
  sysctl -w net.core.rmem_max="16777216"
  sysctl -w net.core.somaxconn="65535"
  sysctl -w net.core.wmem_max="16777216"
  sysctl -w net.ipv4.ip_local_port_range="1025       65535"
  sysctl -w net.ipv4.tcp_fin_timeout="30"
  sysctl -w net.ipv4.tcp_keepalive_time="30"
  sysctl -w net.ipv4.tcp_max_syn_backlog="20480"
  sysctl -w net.ipv4.tcp_max_tw_buckets="400000"
  sysctl -w net.ipv4.tcp_no_metrics_save="1"
  sysctl -w net.ipv4.tcp_syn_retries="2"
  sysctl -w net.ipv4.tcp_synack_retries="2"
  sysctl -w net.ipv4.tcp_tw_recycle="1"
  sysctl -w net.ipv4.tcp_tw_reuse="1"
  sysctl -w vm.min_free_kbytes="65536"
  sysctl -w vm.overcommit_memory="1"
  ulimit -n 9999999
}


echo "Prepare test ...."

ROOT_LOCATION=`pwd`
if [ ! -d "$ROOT_LOCATION/wrk_test" ]; then
  mkdir wrk_test
fi

cd wrk_test

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
if [ ! -f "$LOCATION/traefik_darwin-amd64" ]; then	
  wget -q --show-progress https://github.com/containous/traefik/releases/download/v1.5.0-rc4/traefik_darwin-amd64
fi

if [ ! -f "$LOCATION/traefik.toml" ]; then	
  wget -q --show-progress https://gist.githubusercontent.com/mathieuancelin/a32506603c8425963b30d6d6a6c148fb/raw/c6bfec26078e44d21b4358efdf43f0cbeaaa5789/traefik.toml
fi

USE_CACHE=true JAVA_OPTS='-Xms2G -Xmx8G' java -jar otoroshi.jar >> /dev/null &

chmod +x traefik_darwin-amd64
./traefik_darwin-amd64 --configFile=traefik.toml &

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

# Warm up
wrk -t1 -c1 -d10s -H "Host: test.foo.bar" http://127.0.0.1:8080/ >> /dev/null
wrk -t1 -c1 -d10s -H "Host: test.foo.bar" http://127.0.0.1:8000/ >> /dev/null

echo "Running test at `date`"
wrk -t2 -c200 -d60s -H "Host: test.foo.bar" --latency http://127.0.0.1:8080/
wrk -t2 -c200 -d60s -H "Host: test.foo.bar" --latency http://127.0.0.1:8000/

docker kill $(docker ps -q) >> /dev/null
killall java  >> /dev/null
killall traefik_darwin-amd64  >> /dev/null
rm -f RUNNING_PID
rm -rf logs

case "${1}" in
  rm)
    cd $ROOT_LOCATION
    rm -rf $ROOT_LOCATION/wrk_test
    ;;
  *)
    echo "Done !"
esac

exit ${?}


