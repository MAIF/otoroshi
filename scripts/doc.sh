#!/bin/bash

LOCATION=`pwd`

sh $LOCATION/scripts/schemas.sh
rm -rf $LOCATION/documentation/target/paradox
rm -rf $LOCATION/docs/manual
cd $LOCATION/documentation
sbt ';clean;paradox'
cp -r $LOCATION/documentation/target/paradox/site/main $LOCATION/docs
mv $LOCATION/docs/main $LOCATION/docs/manual