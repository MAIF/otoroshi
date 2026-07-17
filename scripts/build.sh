#!/usr/bin/env bash

export SBT_OPTS="-Xmx8G -Xss16M"

LOCATION=`pwd`

clean () {
  cd $LOCATION
  rm -rf $LOCATION/otoroshi/target/universal
  rm -rf $LOCATION/manual/target/universal
  rm -rf $LOCATION/docs/manual
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
  sbt ';clean;compile'
}

test_server () {
  cd $LOCATION/otoroshi
  # Browser-tagged tests (playwright-java) are excluded here and run in the
  # dedicated "Server Browser Tests" workflow (server_browser_tests.yaml).
  TEST_STORE=inmemory sbt ';testOnly OtoroshiTests;testOnly ExpressionLanguageTests;testOnly BackendMtlsTests;testOnly FrontendTlsTests;testOnly functional.PluginsTestSpec -- -l Browser'
  rc=$?; if [ $rc != 0 ]; then exit $rc; fi
  # TEST_STORE=redis sbt test
  # rc=$?; if [ $rc != 0 ]; then exit $rc; fi
  # TEST_STORE=cassandra sbt test
  # rc=$?; if [ $rc != 0 ]; then exit $rc; fi
}

test_server_with_browser () {
  cd $LOCATION/otoroshi
  TEST_STORE=inmemory sbt ';testOnly functional.PluginsTestSpec -- -n Browser'
  rc=$?; if [ $rc != 0 ]; then exit $rc; fi
}

test_mtls () {
  cd $LOCATION/scripts/tools/mtls-tests
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
    ;;
  test_server_with_browser)
    clean
    build_ui
    rc=$?; if [ $rc != 0 ]; then exit $rc; fi
    compile_server
    rc=$?; if [ $rc != 0 ]; then exit $rc; fi
    test_server_with_browser
    rc=$?; if [ $rc != 0 ]; then exit $rc; fi
    ;;
  test_server)
    clean
    compile_server
    rc=$?; if [ $rc != 0 ]; then exit $rc; fi
    test_server
    rc=$?; if [ $rc != 0 ]; then exit $rc; fi
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
esac

exit ${?}