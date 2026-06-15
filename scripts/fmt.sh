#!/bin/sh

LOCATION=`pwd`


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
    fmt_ui
    fmt_server
    ;;
  ui)
    fmt_ui
    ;;
  server)
    fmt_server
    ;;
  *)
    fmt_ui
    fmt_server
esac

exit ${?}