#!/bin/sh

LOCATION=`pwd`

cleanup () {
  rm -rf ./otoroshi
  rm -f ./otoroshi-dist.zip
  rm -f ./otoroshi.jar
}

prepare_build_old () {
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

prepare_build () {
  if [ ! -f ./otoroshi.jar ]; then
    cd $LOCATION/../../otoroshi/javascript
    yarn install
    yarn build
    cd $LOCATION/../../otoroshi
    sbt assembly
    cd $LOCATION
    cp ../../otoroshi/target/scala-2.12/otoroshi.jar ./otoroshi.jar
  fi
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

build_jdk19 () {
  docker build --no-cache -f ./Dockerfile-jdk19-jar -t otoroshi-jdk19 .
  docker tag otoroshi-jdk19 "maif/otoroshi:$1-jdk19"
}

build_jdk20 () {
  docker build --no-cache -f ./Dockerfile-jdk20-jar -t otoroshi-jdk20 .
  docker tag otoroshi-jdk20 "maif/otoroshi:$1-jdk20"
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
  build-jdk11)
    prepare_build
    build_jdk11 $2
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
  build-jdk19)
    prepare_build
    build_jdk19 $2
    cleanup
    ;;
  build-jdk20)
    prepare_build
    build_jdk20 $2
    cleanup
    ;;
  build-graal)
    prepare_build
    build_graal $2
    cleanup
    ;;
  build-all)
    prepare_build
    build_jdk11 $2
    build_jdk17 $2
    build_jdk18 $2
    build_jdk19 $2
    build_jdk20 $2
    build_graal $2
    cleanup
    ;;
  push-all)
    prepare_build
    build_jdk11 $2
    build_jdk17 $2
    build_jdk18 $2
    build_jdk19 $2
    build_jdk20 $2
    build_graal $2
    cleanup
    echo "\nnow pushing maif/otoroshi:$2\n"
    docker push "maif/otoroshi:$2"
    echo "\nnow pushing maif/otoroshi:$2-jdk11\n"
    docker push "maif/otoroshi:$2-jdk11"
    echo "\nnow pushing maif/otoroshi:$2-jdk17\n"
    docker push "maif/otoroshi:$2-jdk17"
    echo "\nnow pushing maif/otoroshi:$2-jdk18\n"
    docker push "maif/otoroshi:$2-jdk18"
    echo "\nnow pushing maif/otoroshi:$2-jdk19\n"
    docker push "maif/otoroshi:$2-jdk19"
     echo "\nnow pushing maif/otoroshi:$2-jdk20\n"
    docker push "maif/otoroshi:$2-jdk20"
    echo "\nnow pushing maif/otoroshi:$2-graal\n"
    docker push "maif/otoroshi:$2-graal"
    echo "\nnow pushing maif/otoroshi:latest\n"
    docker push "maif/otoroshi:latest"
    ;;
  test-release-builds)
    prepare_build
    build_jdk11 "dev"
    build_jdk17 "dev"
    build_jdk18 "dev"
    build_jdk19 "dev"
    build_jdk20 "dev"
    build_graal "dev"
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
    docker tag otoroshi-jdk11 "registry.oto.tools:5000/maif/otoroshi:1.5.0-dev-local"
    cleanup
    docker push "registry.oto.tools:5000/maif/otoroshi:1.5.0-dev-local"
    ;;
  build-snapshot)
    cp ../../otoroshi/target/scala-2.12/otoroshi.jar otoroshi.jar
    docker build --no-cache -f ./Dockerfile-jdk18-jar -t otoroshi-jdk18 .
    # docker tag otoroshi-jdk18 "maif/otoroshi:dev"
    docker tag otoroshi-jdk18 "maif/otoroshi:dev18"
    docker build --no-cache -f ./Dockerfile-jdk11-jar -t otoroshi-jdk11 .
    docker tag otoroshi-jdk11 "maif/otoroshi:dev11"
    cleanup
    ;;
  build-19)
    cp ../../otoroshi/target/scala-2.12/otoroshi.jar otoroshi.jar
    docker build --no-cache -f ./Dockerfile-jdk19-jar -t otoroshi-jdk19 .
    docker tag otoroshi-jdk19 "maif/otoroshi:dev19"
    cleanup
    ;;
  build-20)
    cp ../../otoroshi/target/scala-2.12/otoroshi.jar otoroshi.jar
    docker build --no-cache -f ./Dockerfile-jdk20-jar -t otoroshi-jdk20 .
    docker tag otoroshi-jdk20 "maif/otoroshi:dev20"
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