#!/bin/bash

LOCATION=`pwd`

sh $LOCATION/scripts/schemas.sh
rm -rf $LOCATION/manual/target/paradox
rm -rf $LOCATION/docs/manual
cd $LOCATION/manual
sbt ';clean;paradox'
cp -r $LOCATION/manual/target/paradox/site/main $LOCATION/docs
mv $LOCATION/docs/main $LOCATION/docs/manual