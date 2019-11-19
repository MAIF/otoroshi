#!/usr/bin/env bash

# eval "$(curl -sL https://raw.githubusercontent.com/travis-ci/gimme/master/gimme | GIMME_GO_VERSION=1.13 bash)"

# sh ./certs.sh
# curl -k -H "Host: mtls.oto.tools" https://mtls.oto.tools:18443/ --include
go run backendmtls.go &
sleep 5
go run clientbackend.go > clientbackend.out
cp ../../otoroshi/target/scala-2.12/otoroshi.jar ./otoroshi.jar
java -Dapp.domain=oto.tools -Dhttp.port=18080 -Dhttps.port=18443 -jar otoroshi.jar &
sleep 20
yarn install
node oto.js
sleep 10
go run clientfrontend.go > clientfrontend.out
sleep 5
# curl -k -H "Host: mtls.oto.tools" https://mtls.oto.tools:18443/ --include
node check.js
rc=$?; if [ $rc != 0 ]; 
then 
  kill $(ps aux | grep 'backendmtls.go' | awk '{print $2}')
  kill $(ps aux | grep 'backendmtls' | awk '{print $2}')
  kill $(ps aux | grep 'clientbackend' | awk '{print $2}')
  kill $(ps aux | grep 'clientfrontend' | awk '{print $2}')
  kill $(ps aux | grep 'otoroshi.jar' | awk '{print $2}')
  exit $rc
else 
  kill $(ps aux | grep 'backendmtls.go' | awk '{print $2}')
  kill $(ps aux | grep 'backendmtls' | awk '{print $2}')
  kill $(ps aux | grep 'clientbackend' | awk '{print $2}')
  kill $(ps aux | grep 'clientfrontend' | awk '{print $2}')
  kill $(ps aux | grep 'otoroshi.jar' | awk '{print $2}')
  exit 0
fi

