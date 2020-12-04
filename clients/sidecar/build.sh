#!/bin/sh

LOCATION=`pwd`

cleanup () {
  rm -rf ./node_modules
}

prepare_build () {
  yarn install
}

build () {
  docker build --no-cache -t otoroshi-sidecar .
  docker tag otoroshi-sidecar "maif/otoroshi-sidecar:$1"
}

echo "Docker images for otoroshi-sidecar version $2"

case "${1}" in
  prepare-build)
    prepare_build
    ;;
  cleanup)
    cleanup
    ;;
  build)
    cleanup
    prepare_build
    build $2
    ;;
  push)
    cleanup
    prepare_build
    build $2
    docker push "maif/otoroshi-sidecar:$2"
    docker push "maif/otoroshi-sidecar:latest"
    ;;
  build-and-push-snapshot)
    cleanup
    prepare_build
    build "dev"
    docker tag otoroshi-sidecar "maif/otoroshi-sidecar:dev"
    docker tag otoroshi-sidecar "maif/otoroshi-sidecar:latest"
    docker push "maif/otoroshi-sidecar:dev"
    docker push "maif/otoroshi-sidecar:latest"
    ;;
  build-local)
    cleanup
    prepare_build
    build "dev"
    ;;
  build-and-push-local)
    cleanup
    prepare_build
    build "dev"
    docker tag otoroshi-sidecar "registry.oto.tools:5000/maif/otoroshi-sidecar:dev"
    docker tag otoroshi-sidecar "registry.oto.tools:5000/maif/otoroshi-sidecar:latest"
    docker push "registry.oto.tools:5000/maif/otoroshi-sidecar:dev"
    docker push "registry.oto.tools:5000/maif/otoroshi-sidecar:latest"
    ;;
  *)
    echo "Build otoroshi-sidecar docker images"
    ;;
esac

exit ${?}