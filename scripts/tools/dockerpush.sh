#!/bin/sh


LOCATION=`pwd`

echo "Will upload artifacts for $TRAVIS_BRANCH / $TRAVIS_PULL_REQUEST"
if test "$TRAVIS_BRANCH" = "master"
then
  if test "$TRAVIS_PULL_REQUEST" = "false"
  then
    # docker login -u $DOCKER_USER -p $DOCKER_PASSWORD

    cd $LOCATION/docker/build
    cp ../../otoroshi/target/universal/otoroshi-$VERSION.zip ./otoroshi-dist.zip
    docker build --no-cache -t otoroshi .
    rm ./otoroshi-dist.zip
    docker tag otoroshi maif/otoroshi
    docker push maif/otoroshi
  fi
fi

cd $LOCATION


