#!/bin/sh

LOCATION=`pwd`

build_schemas () {
  sh $LOCATION/scripts/schemas.sh
}

clean () {
  rm -rf $LOCATION/manual/target/paradox
  rm -rf $LOCATION/docs/manual
}

build () {
  cd $LOCATION/manual
  node indexer.js
  sbt ';clean;paradox'
  cp -r $LOCATION/manual/target/paradox/site/main $LOCATION/docs
  mv $LOCATION/docs/main $LOCATION/docs/manual
}

buildDev () {
  cd $LOCATION/manual
  node indexer.js
  sbt ';clean;paradox'
  rm -rf $LOCATION/docs/devmanual
  cp -r $LOCATION/manual/target/paradox/site/main $LOCATION/docs
  mv $LOCATION/docs/main $LOCATION/docs/devmanual
}

case "${1}" in
  all)
    build_schemas
    clean
    build
    ;;
  build_schemas)
    build_schemas
    ;;
  clean)
    clean
    ;;
  build)
    build
    ;;
  buildDev)
    buildDev
    ;;
  cleanbuild)
    clean
    build
    ;;
  *)
    build_schemas
    clean
    build
esac

exit ${?}