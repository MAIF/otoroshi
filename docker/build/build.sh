#!/bin/sh

LOCATION=`pwd`

cleanup () {
  rm -rf ./otoroshi
  rm -f ./otoroshi-dist.zip
}

prepare_build () {
  rm -rf ./otoroshi
  if [ ! -f ./otoroshi-dist.zip ]; then
    cd $LOCATION/../../otoroshi/javascript
    yarn install
    yarn build
    cd $LOCATION/../../otoroshi
    sbt dist
    cd $LOCATION
    cp ../../otoroshi/target/universal/otoroshi-1.2.0-dev.zip ./otoroshi-dist.zip
  fi
  unzip otoroshi-dist.zip
  mv otoroshi-1.2.0-dev otoroshi
  chmod +x ./otoroshi/bin/otoroshi
  mkdir -p ./otoroshi/imports
  mkdir -p ./otoroshi/leveldb
  mkdir -p ./otoroshi/logs
  touch ./otoroshi/logs/application.log
}

build_jdk8 () {
  docker build --no-cache -t otoroshi .
  docker tag otoroshi "maif/otoroshi:$1"
  docker tag otoroshi "maif/otoroshi:jdk8-$1"
}

build_jdk9 () {
  docker build --no-cache -f ./Dockerfile-jdk9 -t otoroshi-jdk9 .
  docker tag otoroshi-jdk9 "maif/otoroshi:jdk9-$1"
}

build_jdk10 () {
  docker build --no-cache -f ./Dockerfile-jdk10 -t otoroshi-jdk10 .
  docker tag otoroshi-jdk9 "maif/otoroshi:jdk10-$1"
}

build_jdk11 () {
  docker build --no-cache -f ./Dockerfile-jdk11 -t otoroshi-jdk11 .
  docker tag otoroshi-jdk9 "maif/otoroshi:jdk11-$1"
}

case "${1}" in
  prepare-build)
    prepare_build
    ;;
  cleanup)
    cleanup
    ;;
  build-all)
    prepare_build
    build_jdk8 $2
    build_jdk9 $2
    build_jdk10 $2
    build_jdk11 $2
    cleanup
    ;;
  push-all)
    prepare_build
    build_jdk8 $2
    build_jdk9 $2
    build_jdk10 $2
    build_jdk11 $2
    cleanup
    docker push "maif/otoroshi:$2"
    docker push "maif/otoroshi:jdk8-$2"
    docker push "maif/otoroshi:jdk9-$2"
    docker push "maif/otoroshi:jdk10-$2"
    docker push "maif/otoroshi:jdk11-$2"
    ;;
  *)
    echo "Build otoroshi docker images"
esac

exit ${?}