#!/bin/sh

LOCATION=`pwd`

clean () {
  cd $LOCATION/clients/cli
  cargo clean
  cd $LOCATION
  rm -rf $LOCATION/otoroshi/target/universal
  rm -rf $LOCATION/manual/target/universal
  rm -rf $LOCATION/docs/manual
}

build_cli () {
  cd $LOCATION/clients/cli
  cargo build --release
}

build_ui () {
  cd $LOCATION/otoroshi/javascript
  yarn install
  yarn build
}

build_manual () {
  cd $LOCATION/manual
  sbt ';clean;paradox'
  cp -r $LOCATION/manual/target/paradox/site/main $LOCATION/docs
  mv $LOCATION/docs/main $LOCATION/docs/manual
}

build_server () {
  cd $LOCATION/otoroshi
  sbt ';clean;compile;dist;assembly'
}

test_server () {
  cd $LOCATION/otoroshi
  TEST_STORE=inmemory sbt test
  TEST_STORE=leveldb sbt test
  TEST_STORE=redis sbt test
  TEST_STORE=cassandra sbt test
}

case "${1}" in
  all)
    clean
    build_ui
    build_manual
    build_server
    test_server
    build_cli
    ;;
  cli)
    build_cli
    ;;
  ui)
    build_ui
    ;;
  clean)
    clean
    ;;
  manual)
    manual
    ;;
  server)
    build_server
    ;;
  *)
    clean
    build_ui
    build_manual
    build_server
    test_server
    build_cli
esac

exit ${?}