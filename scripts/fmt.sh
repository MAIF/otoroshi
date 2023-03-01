#!/bin/sh

LOCATION=`pwd`

fmt_demo () {
  cd $LOCATION/demos/loadbalancing
  yarn prettier
  cd $LOCATION/demos/snowmonkey
  yarn prettier
}

fmt_ui () {
  cd $LOCATION/otoroshi/javascript
  yarn prettier
}

fmt_server () {
  cd $LOCATION/otoroshi
  sbt ';scalafmt;scalafmtSbt;test:scalafmt'
}

case "${1}" in
  all)
    fmt_demo
    fmt_ui
    fmt_server
    ;;
  demo)
    fmt_demo
    ;;
  ui)
    fmt_ui
    ;;
  server)
    fmt_server
    ;;
  *)
    fmt_demo
    fmt_ui
    fmt_server
esac

exit ${?}