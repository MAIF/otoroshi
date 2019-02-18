#!/bin/sh

# echo "Uploading otoroshicli to bintray"
# unamestr=`uname`
# if [ "$unamestr" == 'Linux' ]; then
# 	curl -T ./clients/cli/target/release/otoroshicli -umathieuancelin:$BINTRAY_API_KEY -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' https://api.bintray.com/content/maif/binaries/linux-otoroshicli/snapshot/otoroshicli
# elif [ "$unamestr" == 'Darwin' ]; then
# 	curl -T ./clients/cli/target/release/otoroshicli -umathieuancelin:$BINTRAY_API_KEY -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' https://api.bintray.com/content/maif/binaries/mac-otoroshicli/snapshot/otoroshicli
# fi

# curl -T ./linux-otoroshicli -umathieuancelin:$BINTRAY_API_KEY -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' -H 'X-Bintray-Version: snapshot' -H 'X-Bintray-Package: linux-otoroshicli' https://api.bintray.com/content/maif/binaries/linux-otoroshicli/snapshot/otoroshicli
# curl -T ./macos-otoroshicli -umathieuancelin:$BINTRAY_API_KEY -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' -H 'X-Bintray-Version: snapshot' -H 'X-Bintray-Package: mac-otoroshicli' https://api.bintray.com/content/maif/binaries/mac-otoroshicli/snapshot/otoroshicli
# curl -T ./win-otoroshicli.exe -umathieuancelin:$BINTRAY_API_KEY -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' -H 'X-Bintray-Version: snapshot' -H 'X-Bintray-Package: win-otoroshicli' https://api.bintray.com/content/maif/binaries/win-otoroshicli/snapshot/otoroshicli.exe

echo "Will upload artifacts for $TRAVIS_BRANCH / $TRAVIS_PULL_REQUEST"
if test "$TRAVIS_BRANCH" = "master"
then
  if test "$TRAVIS_PULL_REQUEST" = "false"
  then
    echo "Uploading otoroshi.jar"
    curl -T ./otoroshi/target/scala-2.12/otoroshi.jar -umathieuancelin:$BINTRAY_API_KEY -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' -H 'X-Bintray-Version: snapshot' -H 'X-Bintray-Package: otoroshi.jar' https://api.bintray.com/content/maif/binaries/otoroshi.jar/snapshot/otoroshi.jar
    curl -T ./otoroshi/target/universal/otoroshi-1.4.6.zip -umathieuancelin:$BINTRAY_API_KEY -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' -H 'X-Bintray-Version: snapshot' -H 'X-Bintray-Package: otoroshi-dist' https://api.bintray.com/content/maif/binaries/otoroshi-dist/snapshot/otoroshi-dist.zip
    # curl -T ./clients/cli/target/release/otoroshicli  -umathieuancelin:$BINTRAY_API_KEY -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' -H 'X-Bintray-Version: snapshot' -H 'X-Bintray-Package: linux-otoroshicli' https://api.bintray.com/content/maif/binaries/linux-otoroshicli/snapshot/otoroshicli
  fi
fi
