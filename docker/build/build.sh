#!/bin/sh

LOCATION=`pwd`

cleanup () {
  rm -rf ./otoroshi
  rm -f ./otoroshi-dist.zip
  rm -f ./otoroshi.jar
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
    cp ../../otoroshi/target/universal/otoroshi-1.5.0-dev.zip ./otoroshi-dist.zip
    cp ../../otoroshi/target/scala-2.12/otoroshi.jar ./otoroshi.jar
  fi
  unzip otoroshi-dist.zip
  mv otoroshi-1.5.0-dev otoroshi
  rm -rf otoroshi-dist.zip
  chmod +x ./otoroshi/bin/otoroshi
  mkdir -p ./otoroshi/imports
  mkdir -p ./otoroshi/leveldb
  mkdir -p ./otoroshi/logs
  touch ./otoroshi/logs/application.log
}

build_jdk8 () {
  # docker build --no-cache -f ./Dockerfile-jdk8 -t otoroshi-jdk8-no-scripting .
  docker build --no-cache -f ./Dockerfile-jdk8-jar -t otoroshi-jdk8 .
  # docker tag otoroshi-jdk8-no-scripting "maif/otoroshi:$1-jdk8-no-scripting"
  docker tag otoroshi-jdk8 "maif/otoroshi:$1-jdk8"
}

build_jdk11 () {
  # docker build --no-cache -f ./Dockerfile-jdk11 -t otoroshi-jdk11-no-scripting .
  docker build --no-cache -f ./Dockerfile-jdk11-jar -t otoroshi-jdk11 .
  docker tag otoroshi-jdk11 "maif/otoroshi:latest" 
  docker tag otoroshi-jdk11 "maif/otoroshi:$1" 
  # docker tag otoroshi-jdk11-no-scripting "maif/otoroshi:$1-jdk11-no-scripting"
  docker tag otoroshi-jdk11 "maif/otoroshi:$1-jdk11"
}

build_jdk15 () {
  docker build --no-cache -f ./Dockerfile-jdk15-jar -t otoroshi-jdk15 .
  docker tag otoroshi-jdk15 "maif/otoroshi:$1-jdk15"
}

build_jdk16 () {
  docker build --no-cache -f ./Dockerfile-jdk16-jar -t otoroshi-jdk16 .
  docker tag otoroshi-jdk16 "maif/otoroshi:$1-jdk16"
}

build_jdk17 () {
  docker build --no-cache -f ./Dockerfile-jdk17-jar -t otoroshi-jdk17 .
  docker tag otoroshi-jdk17 "maif/otoroshi:$1-jdk17"
}

build_jdk18 () {
  docker build --no-cache -f ./Dockerfile-jdk18-jar -t otoroshi-jdk18 .
  docker tag otoroshi-jdk18 "maif/otoroshi:$1-jdk18"
}

build_graal () {
  docker build --no-cache -f ./Dockerfile-graal -t otoroshi-graal .
  docker tag otoroshi-graal "maif/otoroshi:$1-graal"
}

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
  build-jdk11)
    prepare_build
    build_jdk11 $2
    cleanup
    ;;
  build-jdk15)
    prepare_build
    build_jdk15 $2
    cleanup
    ;;
  build-jdk16)
    prepare_build
    build_jdk16 $2
    cleanup
    ;;
  build-jdk17)
    prepare_build
    build_jdk17 $2
    cleanup
    ;;
  build-jdk18)
    prepare_build
    build_jdk18 $2
    cleanup
    ;;
  build-graal)
    prepare_build
    build_graal $2
    cleanup
    ;;
  build-all)
    prepare_build
    build_jdk8 $2
    build_jdk11 $2
    build_jdk16 $2
    cleanup
    ;;
  push-all)
    prepare_build
    build_jdk11 $2
    # build_jdk16 $2
    # build_jdk17 $2
    # build_jdk18 $2
    build_graal $2
    cleanup
    docker push "maif/otoroshi:$2"
    docker push "maif/otoroshi:$2-jdk11"
    #docker push "maif/otoroshi:$2-jdk16"
    #docker push "maif/otoroshi:$2-jdk17"
    #docker push "maif/otoroshi:$2-jdk18"
    docker push "maif/otoroshi:$2-graal"
    docker push "maif/otoroshi:latest"
    ;;
  build-and-push-snapshot)
    NBR=`date +%s`
    echo "Will build version 1.5.0-dev-$NBR"
    cp ../../otoroshi/target/scala-2.12/otoroshi.jar otoroshi.jar
    docker build --no-cache -f ./Dockerfile-jdk11-jar -t otoroshi-jdk11 .
    docker tag otoroshi-jdk11 "maif/otoroshi:1.5.0-dev-$NBR"
    docker tag otoroshi-jdk11 "maif/otoroshi:dev"
    cleanup
    docker push "maif/otoroshi:1.5.0-dev-$NBR"
    docker push "maif/otoroshi:dev"
    ;;
  build-and-push-local)
    cp ../../otoroshi/target/scala-2.12/otoroshi.jar otoroshi.jar
    docker build --no-cache -f ./Dockerfile-jdk11-jar -t otoroshi-jdk11 .
    docker tag otoroshi-jdk11 "registry.oto.tools:5000/maif/otoroshi:1.5.0-local"
    cleanup
    docker push "registry.oto.tools:5000/maif/otoroshi:1.5.0-local"
    ;;
  build-snapshot)
    cp ../../otoroshi/target/scala-2.12/otoroshi.jar otoroshi.jar
    docker build --no-cache -f ./Dockerfile-jdk18-jar -t otoroshi-jdk18 .
    docker tag otoroshi-jdk18 "maif/otoroshi:dev"
    cleanup
    ;;
  prepare)
    cp ../../otoroshi/target/universal/otoroshi-1.5.0-dev.zip ./otoroshi-dist.zip
    cp ../../otoroshi/target/scala-2.12/otoroshi.jar ./otoroshi.jar
    ;;
  *)
    echo "Build otoroshi docker images"
    ;;
esac

exit ${?}