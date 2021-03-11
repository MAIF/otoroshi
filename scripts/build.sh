#!/usr/bin/env bash

export SBT_OPTS="-XX:MaxPermSize=2048m -Xmx2048m -Xss8M"

LOCATION=`pwd`

clean () {
  cd $LOCATION/clients/cli
  cd $LOCATION
  rm -rf $LOCATION/otoroshi/target/universal
  rm -rf $LOCATION/manual/target/universal
  rm -rf $LOCATION/docs/manual
}

build_cli () {
  cd $LOCATION/clients/cli
  cargo clean
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

compile_server () {
  cd $LOCATION/otoroshi
  sbt ';clean;compile;assembly'
}

test_server () {
  cd $LOCATION/otoroshi
  TEST_STORE=inmemory sbt 'testOnly OtoroshiTests'
  rc=$?; if [ $rc != 0 ]; then exit $rc; fi
  # TEST_STORE=leveldb sbt test
  # rc=$?; if [ $rc != 0 ]; then exit $rc; fi
  # TEST_STORE=redis sbt test
  # rc=$?; if [ $rc != 0 ]; then exit $rc; fi
  # TEST_STORE=cassandra sbt test
  # rc=$?; if [ $rc != 0 ]; then exit $rc; fi
  # TEST_STORE=mongo sbt test
  # rc=$?; if [ $rc != 0 ]; then exit $rc; fi
}

test_mtls () {
  cd $LOCATION/scripts/mtls-tests
  sh ./test.sh
  rc=$?; if [ $rc != 0 ]; then exit $rc; fi
}

case "${1}" in
  all)
    clean
    build_ui
    rc=$?; if [ $rc != 0 ]; then exit $rc; fi
    # build_manual
    build_server
    rc=$?; if [ $rc != 0 ]; then exit $rc; fi
    test_server
    rc=$?; if [ $rc != 0 ]; then exit $rc; fi
    test_mtls
    rc=$?; if [ $rc != 0 ]; then exit $rc; fi
    # build_cli
    ;;
  test_all)
    clean
    build_ui
    rc=$?; if [ $rc != 0 ]; then exit $rc; fi
    # build_manual
    compile_server
    rc=$?; if [ $rc != 0 ]; then exit $rc; fi
    test_server
    rc=$?; if [ $rc != 0 ]; then exit $rc; fi
    test_mtls
    rc=$?; if [ $rc != 0 ]; then exit $rc; fi
    # build_cli
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
  test_mtls)
    test_mtls
    ;;
  *)
     clean
    build_ui
    build_manual
    build_server
    test_server
    test_mtls
    # build_cli
esac

exit ${?}