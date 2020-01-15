#!/bin/sh

echo "Will upload artifacts for $TRAVIS_BRANCH / $TRAVIS_PULL_REQUEST"
if test "$TRAVIS_BRANCH" = "master"
then
  if test "$TRAVIS_PULL_REQUEST" = "false"
  then
    echo "Uploading otoroshi.jar"
    curl -T ./otoroshi/target/scala-2.12/otoroshi.jar -umathieuancelin:$BINTRAY_API_KEY -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' -H 'X-Bintray-Version: snapshot' -H 'X-Bintray-Package: otoroshi.jar' https://api.bintray.com/content/maif/binaries/otoroshi.jar/snapshot/otoroshi.jar
    curl -T ./otoroshi/target/universal/otoroshi-1.4.17-dev.zip -umathieuancelin:$BINTRAY_API_KEY -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' -H 'X-Bintray-Version: snapshot' -H 'X-Bintray-Package: otoroshi-dist' https://api.bintray.com/content/maif/binaries/otoroshi-dist/snapshot/otoroshi-dist.zip
  fi
fi
