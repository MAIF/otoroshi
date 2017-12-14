#!/bin/bash

LOCATION=`pwd`

rm -rf $LOCATION/otoroshi/target/universal
rm -rf $LOCATION/manual/target/universal
rm -rf $LOCATION/docs/manual
cd $LOCATION/otoroshi/javascript
yarn install
yarn build
cd $LOCATION/manual
sbt ';clean;paradox'
cp -r $LOCATION/manual/target/paradox/site/main $LOCATION/docs
mv $LOCATION/docs/main $LOCATION/docs/manual
cd $LOCATION
sbt ';clean;compile;dist;assembly'
