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
    sbt assembly
    cd $LOCATION
    cp ../../otoroshi/target/universal/otoroshi-1.2.1-dev.zip ./otoroshi-dist.zip
    cp ../../otoroshi/target/scala-2.12/otoroshi.jar ./otoroshi.jar
  fi
  unzip otoroshi-dist.zip
  mv otoroshi-1.2.1-dev otoroshi
  chmod +x ./otoroshi/bin/otoroshi
  mkdir -p ./otoroshi/imports
  mkdir -p ./otoroshi/leveldb
  mkdir -p ./otoroshi/logs
  touch ./otoroshi/logs/application.log
}

build_jdk8 () {
  docker build --no-cache -t otoroshi .
  docker tag otoroshi "maif/otoroshi:latest" "maif/otoroshi:$1" "maif/otoroshi:$1-jdk8"
}

build_jdk9 () {
  docker build --no-cache -f ./Dockerfile-jdk9 -t otoroshi-jdk9 .
  docker tag otoroshi-jdk9 "maif/otoroshi:$1-jdk9"
}

build_jdk10 () {
  docker build --no-cache -f ./Dockerfile-jdk10 -t otoroshi-jdk10 .
  docker tag otoroshi-jdk10 "maif/otoroshi:$1-jdk10"
}

build_jdk11 () {
  docker build --no-cache -f ./Dockerfile-jdk11 -t otoroshi-jdk11 .
  docker tag otoroshi-jdk11 "maif/otoroshi:$1-jdk11"
}

build_jdk12 () {
  docker build --no-cache -f ./Dockerfile-jdk12 -t otoroshi-jdk12 .
  docker tag otoroshi-jdk12 "maif/otoroshi:$1-jdk12"
}

build_graal () {
  docker build --no-cache -f ./Dockerfile-graal -t otoroshi-graal .
  docker tag otoroshi-graal "maif/otoroshi:$1-graal"
}

# sh ./build.sh build-all 1.2.1-dev-$(date +%s)
echo "Docker images for otoroshi version $2"

case "${1}" in
  prepare-build)
    prepare_build
    ;;
  cleanup)
    cleanup
    ;;
  build-jdk8)
    prepare_build
    build_jdk8 $2
    cleanup
    ;;
  build-jdk9)
    prepare_build
    build_jdk9 $2
    cleanup
    ;;
  build-jdk10)
    prepare_build
    build_jdk10 $2
    cleanup
    ;;
  build-jdk11)
    prepare_build
    build_jdk11 $2
    cleanup
    ;;
  build-jdk12)
    prepare_build
    build_jdk12 $2
    cleanup
    ;;
  build-all)
    prepare_build
    build_jdk8 $2
    build_jdk9 $2
    build_jdk10 $2
    build_jdk11 $2
    build_jdk12 $2
    build_graal $2
    cleanup
    ;;
  push-all)
    prepare_build
    build_jdk8 $2
    build_jdk9 $2
    build_jdk10 $2
    build_jdk11 $2
    build_jdk12 $2
    build_graal $2
    cleanup
    docker push "maif/otoroshi:$2"
    docker push "maif/otoroshi:$2-jdk8"
    docker push "maif/otoroshi:$2-jdk9"
    docker push "maif/otoroshi:$2-jdk10"
    docker push "maif/otoroshi:$2-jdk11"
    docker push "maif/otoroshi:$2-jdk12"
    docker push "maif/otoroshi:$2-graal"
    docker push "maif/otoroshi:latest"
    ;;
  *)
    echo "Build otoroshi docker images"
    ;;
esac

exit ${?}