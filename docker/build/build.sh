#!/bin/sh

LOCATION=`pwd`

cleanup () {
  rm -rf ./otoroshi
  rm -f ./otoroshi-dist.zip
  rm -f ./otoroshi.jar
}

copy_build () {
  cp ../../otoroshi/target/scala-2.12/otoroshi.jar ./otoroshi.jar
}

prepare_build () {
  if [ ! -f ./otoroshi.jar ]; then
    cd $LOCATION/../../otoroshi/javascript
    yarn install
    yarn build
    cd $LOCATION/../../otoroshi
    sbt assembly
    cd $LOCATION
  fi
  copy_build
}

build_graal () {
  docker build --build-arg "IMG_FROM=ghcr.io/graalvm/graalvm-ce:latest" --no-cache -f ./Dockerfile -t "otoroshi-grall" .
  docker tag otoroshi-graal "maif/otoroshi:$1-graal"
}

push_graal () {
  echo "\nnow pushing maif/otoroshi:$OTO_VERSION-graal\n"
  docker push "maif/otoroshi:$1-graal"
}

build_jar_template_version () {
  OTO_VERSION="$1"
  JDK_VERSION="$2"
  echo "build version $OTO_VERSION with jdk $JDK_VERSION"
  docker build --build-arg "IMG_FROM=eclipse-temurin:$JDK_VERSION" --no-cache -f ./Dockerfile -t "otoroshi-jdk$JDK_VERSION" .
  docker tag "otoroshi-jdk$JDK_VERSION" "maif/otoroshi:$OTO_VERSION-jdk$JDK_VERSION"
  echo "build version $OTO_VERSION with jdk $JDK_VERSION from eclipse-temurin"
  docker build --build-arg "IMG_FROM=eclipse-temurin:$JDK_VERSION" --no-cache -f ./Dockerfile -t "otoroshi-temurin-jdk$JDK_VERSION" .
  docker tag "otoroshi-temurin-jdk$JDK_VERSION" "maif/otoroshi:$OTO_VERSION-temurin-jdk$JDK_VERSION"
  echo "build version $OTO_VERSION with jdk $JDK_VERSION from amazon-correto"
  docker build --build-arg "IMG_FROM=amazoncorretto:$JDK_VERSION"  --no-cache -f ./Dockerfile -t "otoroshi-correto-jdk$JDK_VERSION" .
  docker tag "otoroshi-correto-jdk$JDK_VERSION" "maif/otoroshi:$OTO_VERSION-correto-jdk$JDK_VERSION"
}

build_and_push_jar_template_version_multi_arch () {
  OTO_VERSION="$1"
  JDK_VERSION="$2"
  #echo "build and push version $OTO_VERSION with jdk $JDK_VERSION"
  #docker buildx build --platform=linux/arm64,linux/amd64 --push --build-arg "IMG_FROM=eclipse-temurin:$JDK_VERSION" --no-cache -f ./Dockerfile -t "maif/otoroshi:$OTO_VERSION-jdk$JDK_VERSION" .
  echo "build and push version $OTO_VERSION with jdk $JDK_VERSION from eclipse-temurin"
  docker buildx build --platform=linux/arm64,linux/amd64 --push --build-arg "IMG_FROM=eclipse-temurin:$JDK_VERSION" --no-cache -f ./Dockerfile -t "maif/otoroshi:$OTO_VERSION-temurin-jdk$JDK_VERSION" -t "maif/otoroshi:$OTO_VERSION-jdk$JDK_VERSION" .
  echo "build and push version $OTO_VERSION with jdk $JDK_VERSION from amazon-correto"
  docker buildx build --platform=linux/arm64,linux/amd64 --push --build-arg "IMG_FROM=amazoncorretto:$JDK_VERSION"  --no-cache -f ./Dockerfile -t "maif/otoroshi:$OTO_VERSION-correto-jdk$JDK_VERSION" .
}

build_and_push_jar_template_version_multi_arch_latest () {
  OTO_VERSION="$1"
  JDK_VERSION="$2"
  echo "build and push version $OTO_VERSION with jdk $JDK_VERSION latest"
  docker buildx build --platform=linux/arm64,linux/amd64 --push --build-arg "IMG_FROM=eclipse-temurin:$JDK_VERSION" --no-cache -f ./Dockerfile -t "maif/otoroshi:$OTO_VERSION" -t "maif/otoroshi:latest" .
}

build_and_push_jar_template_version_multi_arch_temurin () {
  OTO_VERSION="$1"
  JDK_VERSION="$2"
  echo "build version $OTO_VERSION with jdk $JDK_VERSION"
  docker buildx build --platform=linux/arm64,linux/amd64 --push --build-arg "IMG_FROM=eclipse-temurin:$JDK_VERSION" --no-cache -f ./Dockerfile -t "maif/otoroshi:$OTO_VERSION-jdk$JDK_VERSION" -t "maif/otoroshi:dev"  .
}

build_and_push_jar_templates () {
  OTO_VERSION="$1"
  build_and_push_jar_template_version_multi_arch "$OTO_VERSION" "11"
  build_and_push_jar_template_version_multi_arch "$OTO_VERSION" "17"
  build_and_push_jar_template_version_multi_arch "$OTO_VERSION" "19"
  build_and_push_jar_template_version_multi_arch "$OTO_VERSION" "20"
}

setup_docker_builder () {
  # https://docs.docker.com/build/building/multi-platform/
  docker buildx create --name multiarchbuilder --driver docker-container --bootstrap
  docker buildx use multiarchbuilder
  docker buildx inspect
  docker buildx ls
}

echo "Docker images for otoroshi version $2"

case "${1}" in
  prepare-build)
    prepare_build
    ;;
  cleanup)
    cleanup
    ;;
  build-all)
    OTO_VERSION="$2"
    prepare_build
    build_and_push_jar_templates "$OTO_VERSION"
    build_and_push_jar_template_version_multi_arch_latest "$OTO_VERSION" "11"
    build_graal "$OTO_VERSION"
    cleanup
    push_graal "$OTO_VERSION"
    ;;
  test-release-builds)
    OTO_VERSION="dev"
    prepare_build
    build_and_push_jar_templates "$OTO_VERSION"
    cleanup
    ;;
  build-and-push-dev)
    OTO_VERSION="dev"
    copy_build
    build_and_push_jar_template_version_multi_arch_temurin "$OTO_VERSION" "20"
    cleanup
    ;;
  build-and-push-snapshot)
    NBR=`date +%s`
    OTO_VERSION="dev-${NBR}"
    echo "Will build version $OTO_VERSION"
    copy_build
    build_and_push_jar_template_version_multi_arch_temurin "$OTO_VERSION" "20"
    cleanup
    ;;
  build-snapshots)
    NBR=`date +%s`
    OTO_VERSION="dev-${NBR}"
    echo "Will build version $OTO_VERSION"
    copy_build
    build_and_push_jar_templates "$OTO_VERSION"
    cleanup
    ;;
  copy)
    copy_build
    ;;
  setup-docker)
    setup_docker_builder
    ;;
  *)
    echo "Build otoroshi docker images"
    echo ""
    echo "possible commands: "
    echo ""
    echo " - prepare-build: build otoroshi.jar if not available and copy it"
    echo " - prepare: copy otoroshi.jar"
    echo " - cleanup: delete otoroshi.jar"
    echo " - build-all {version}: build and tag all docker images"
    echo " - test-release-builds"
    echo " - build-and-push-snapshot"
    echo " - build-snapshots"
    echo " - build-and-push-dev"
    echo " - setup-docker: setup the docker builder"
    ;;
esac

exit ${?}