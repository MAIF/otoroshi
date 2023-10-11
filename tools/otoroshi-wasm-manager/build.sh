#!/bin/sh

LOCATION=`pwd`

cleanup () {
  rm -rf ./node_modules
}

build () {
  cd ./ui
  npm install && npm run build && rm -rf node_modules
  cd ..
  docker build --no-cache -t otoroshi-wasm-manager .
  docker tag otoroshi-wasm-manager "maif/otoroshi-wasm-manager:$1"
  docker tag otoroshi-wasm-manager "maif/otoroshi-wasm-manager:latest"
}

echo "Docker images for otoroshi-wasm-manager version $1"

case "${1}" in
  cleanup)
    cleanup
    ;;
  build)
    cleanup
    build $2
    ;;
  push-all)
    cleanup
    build $2
    docker push "maif/otoroshi-wasm-manager:$2"
    docker push "maif/otoroshi-wasm-manager:latest"
    ;;
  *)
    echo "Build otoroshi-wasm-manager docker images"
    ;;
esac

exit ${?}
