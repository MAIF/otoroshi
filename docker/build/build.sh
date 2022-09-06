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

build_jar_template_version () {
  OTO_VERSION="$1"
  JDK_VERSION="$2"
  echo "build version $OTO_VERSION with jdk $JDK_VERSION"
  docker build --build-arg "IMG_FROM=eclipse-temurin:$JDK_VERSION" --no-cache -f ./Dockerfile-gen -t "otoroshi-jdk$JDK_VERSION" .
  echo "build version $OTO_VERSION with jdk $JDK_VERSION from eclipse-temurin"
  docker build --build-arg "IMG_FROM=eclipse-temurin:$JDK_VERSION" --no-cache -f ./Dockerfile-gen -t "otoroshi-temurin-jdk$JDK_VERSION" .
  echo "build version $OTO_VERSION with jdk $JDK_VERSION from amazon-correto"
  docker build --build-arg "IMG_FROM=amazoncorretto:$JDK_VERSION"  --no-cache -f ./Dockerfile-gen -t "otoroshi-correto-jdk$JDK_VERSION" .
  docker tag "otoroshi-jdk$JDK_VERSION" "maif/otoroshi:$OTO_VERSION-jdk$JDK_VERSION"
  docker tag "otoroshi-temurin-jdk$JDK_VERSION" "maif/otoroshi:$OTO_VERSION-temurin-jdk$JDK_VERSION"
  docker tag "otoroshi-correto-jdk$JDK_VERSION" "maif/otoroshi:$OTO_VERSION-correto-jdk$JDK_VERSION"
}

build_jar_templates () {
  OTO_VERSION="$1"
  build_jar_template_version "$OTO_VERSION" "8"
  build_jar_template_version "$OTO_VERSION" "11"
  build_jar_template_version "$OTO_VERSION" "17"
  build_jar_template_version "$OTO_VERSION" "18"
}

push_otoroshi_version () {
  OTO_VERSION="$1"
  JDK_VERSION="$2"
  echo "\nnow pushing maif/otoroshi:$OTO_VERSION-jdk$JDK_VERSION\n"
  docker push "maif/otoroshi:$OTO_VERSION-jdk$JDK_VERSION"
  echo "\nnow pushing maif/otoroshi:$OTO_VERSION-temurin-jdk$JDK_VERSION\n"
  docker push "maif/otoroshi:$OTO_VERSION-temurin-jdk$JDK_VERSION"
  echo "\nnow pushing maif/otoroshi:$OTO_VERSION-correto-jdk$JDK_VERSION\n"
  docker push "maif/otoroshi:$OTO_VERSION-correto-jdk$JDK_VERSION"
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
    
    OTO_VERSION="$2"

    prepare_build

    build_jar_template_version "$OTO_VERSION" "11"
    build_jar_template_version "$OTO_VERSION" "17"
    build_jar_template_version "$OTO_VERSION" "18"
    build_graal "$OTO_VERSION"

    cleanup

    docker tag otoroshi-jdk11 "maif/otoroshi:latest" 
    docker tag otoroshi-jdk11 "maif/otoroshi:$OTO_VERSION" 
    echo "\nnow pushing maif/otoroshi:$2\n"
    docker push "maif/otoroshi:$2"
    echo "\nnow pushing maif/otoroshi:latest\n"
    docker push "maif/otoroshi:latest"

    push_otoroshi_version "$OTO_VERSION" "11"
    push_otoroshi_version "$OTO_VERSION" "17"
    push_otoroshi_version "$OTO_VERSION" "18"

    echo "\nnow pushing maif/otoroshi:$2-graal\n"
    docker push "maif/otoroshi:$2-graal"
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
  build_jar_templates)
    cp ../../otoroshi/target/scala-2.12/otoroshi.jar otoroshi.jar
    build_jar_templates "dev"
    rm -f ./otoroshi.jar
    ;;
  *)
    echo "Build otoroshi docker images"
    ;;
esac

exit ${?}