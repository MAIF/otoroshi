#!/bin/bash

LOCATION=`pwd`
cd $LOCATION/clients/cli
rustup run nightly cargo fmt
cd $LOCATION/otoroshi/javascript
yarn prettier
cd $LOCATION/connectors/clevercloud
yarn prettier
cd $LOCATION/connectors/common
yarn prettier
cd $LOCATION/connectors/elasticsearch
yarn prettier
cd $LOCATION/connectors/kubernetes
yarn prettier
cd $LOCATION/connectors/rancher
yarn prettier
cd $LOCATION/otoroshi
sbt ';scalafmt;sbt:scalafmt;test:scalafmt'
