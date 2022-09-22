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
  echo "build version $OTO_VERSION with jdk $JDK_VERSION from eclipse-temurin"
  docker build --build-arg "IMG_FROM=eclipse-temurin:$JDK_VERSION" --no-cache -f ./Dockerfile -t "otoroshi-temurin-jdk$JDK_VERSION" .
  echo "build version $OTO_VERSION with jdk $JDK_VERSION from amazon-correto"
  docker build --build-arg "IMG_FROM=amazoncorretto:$JDK_VERSION"  --no-cache -f ./Dockerfile -t "otoroshi-correto-jdk$JDK_VERSION" .
  docker tag "otoroshi-jdk$JDK_VERSION" "maif/otoroshi:$OTO_VERSION-jdk$JDK_VERSION"
  docker tag "otoroshi-temurin-jdk$JDK_VERSION" "maif/otoroshi:$OTO_VERSION-temurin-jdk$JDK_VERSION"
  docker tag "otoroshi-correto-jdk$JDK_VERSION" "maif/otoroshi:$OTO_VERSION-correto-jdk$JDK_VERSION"
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

build_jar_templates () {
  OTO_VERSION="$1"
  build_jar_template_version "$OTO_VERSION" "11"
  build_jar_template_version "$OTO_VERSION" "17"
  build_jar_template_version "$OTO_VERSION" "19"
}

push_jar_templates () {
  OTO_VERSION="$1"
  docker tag otoroshi-jdk11 "maif/otoroshi:latest" 
  docker tag otoroshi-jdk11 "maif/otoroshi:$OTO_VERSION" 
  echo "\nnow pushing maif/otoroshi:$OTO_VERSION\n"
  docker push "maif/otoroshi:$OTO_VERSION"
  echo "\nnow pushing maif/otoroshi:latest\n"
  docker push "maif/otoroshi:latest"
  push_otoroshi_version "$OTO_VERSION" "11"
  push_otoroshi_version "$OTO_VERSION" "17"
  push_otoroshi_version "$OTO_VERSION" "19"
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
    build_jar_templates "$OTO_VERSION"
    build_graal "$OTO_VERSION"
    cleanup
    ;;
  push-all)
    OTO_VERSION="$2"
    prepare_build
    build_jar_templates "$OTO_VERSION"
    build_graal "$OTO_VERSION"
    cleanup
    push_jar_templates "$OTO_VERSION"
    push_graal "$OTO_VERSION"
    ;;
  test-release-builds)
    OTO_VERSION="dev"
    prepare_build
    build_jar_templates "$OTO_VERSION"
    build_graal "$OTO_VERSION"
    cleanup
    #push_jar_templates "$OTO_VERSION"
    #push_graal "$OTO_VERSION"
    ;;
  build-and-push-snapshot)
    NBR=`date +%s`
    OTO_VERSION="dev-${NBR}"
    echo "Will build version $OTO_VERSION"
    copy_build
    build_jar_template_version "$OTO_VERSION" "19"
    push_otoroshi_version "$OTO_VERSION" "19"
    cleanup
    ;;
  build-snapshot)
    NBR=`date +%s`
    OTO_VERSION="dev-${NBR}"
    echo "Will build version $OTO_VERSION"
    copy_build
    build_jar_template_version "$OTO_VERSION" "19"
    cleanup
    ;;
  build-and-push-local)
    NBR=`local`
    OTO_VERSION="dev-${NBR}"
    echo "Will build version $OTO_VERSION"
    copy_build
    build_jar_template_version "$OTO_VERSION" "19"
    docker tag otoroshi-jdk19 "registry.oto.tools:5000/maif/otoroshi:${OTO_VERSION}"
    docker push "registry.oto.tools:5000/maif/otoroshi:${OTO_VERSION}"
    cleanup
    ;;
  prepare)
    copy_build
    ;;
  build_jar_templates)
    copy_build
    build_jar_templates "dev"
    cleanup
    ;;
  *)
    echo "Build otoroshi docker images"
    echo ""
    echo "possible commands: "
    echo ""
    echo " - prepare-build"
    echo " - cleanup"
    echo " - build-all {version}"
    echo " - push-all {version}"
    echo " - test-release-builds"
    echo " - build-and-push-snapshot"
    echo " - build-snapshot"
    echo " - build-and-push-local"
    echo " - prepare"
    ;;
esac

exit ${?}