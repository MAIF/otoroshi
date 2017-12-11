#!/bin/bash

LOCATION=`pwd`

FILES="$LOCATION/documentation/src/main/paradox/schemas/*.ditaa"
for f in $FILES
do
  FILE=`echo $f | sed 's/ditaa/png/g'`
	java -jar $LOCATION/scripts/ditaa.jar -o $f $FILE
done