#!/usr/bin/env bash

eval "$(curl -sL https://raw.githubusercontent.com/travis-ci/gimme/master/gimme | GIMME_GO_VERSION=1.13 bash)"

sh ./certs.sh
go run backend.go &
go run clientbackend.go > clientbackend.out
cp ../../otoroshi/target/scala-2.12/otoroshi.jar ./otoroshi.jar
java -Dapp.domain=oto.tools -jar otoroshi.jar &
sleep 10
yarn install
node oto.js
sleep 10
go run clientfrontend.go > clientfrontend.out
node check.js
