#!/bin/sh

LOCATION=`pwd`

cd $LOCATION/docker/build
docker login -u ${DOCKER_USER} -p ${DOCKER_PASSWORD}
docker build --no-cache -t otoroshi .
docker tag otoroshi maif-docker-docker.bintray.io/otoroshi
docker push maif-docker-docker.bintray.io/otoroshi

# cd $LOCATION/docker/tryout
# docker build -t otoroshi-tryout .
# docker tag otoroshi-tryout maif-docker-docker.bintray.io/otoroshi-tryout
# docker push maif-docker-docker.bintray.io/otoroshi-tryout

# cd $LOCATION/docker/otoroshicli
# docker build -t otoroshicli .
# docker tag otoroshicli maif-docker-docker.bintray.io/otoroshicli
# docker push maif-docker-docker.bintray.io/otoroshicli

cd $LOCATION


