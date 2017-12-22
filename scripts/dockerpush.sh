#!/bin/sh

LOCATION=`pwd`

cd $LOCATION/docker/build
docker build -t otoroshi .
docker tag otoroshi maif-docker-docker.bintray.io/otoroshi
docker push maif-docker-docker.bintray.io/otoroshi

cd $LOCATION/docker/tryout
docker build -t otoroshi-tryout .
docker tag otoroshi-tryout maif-docker-docker.bintray.io/otoroshi-tryout
docker push maif-docker-docker.bintray.io/otoroshi-tryout

cd $LOCATION


