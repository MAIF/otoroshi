#!/bin/sh

LOCATION=`pwd`
VERSION="$1"

echo "releasing Otoroshi version $VERSION"

mkdir -p "release-$VERSION"

# format code
sh ./scripts/fmt.sh

# clean
sh ./scripts/build.sh clean

# build doc with schemas
sh ./scripts/docs.sh all

# build ui
sh ./scripts/build.sh build_ui

# build server
sh ./scripts/build.sh build_server

cp -v "./otoroshi/target/scala-2.11/otoroshi.jar" "$LOCATION/release-$VERSION"
cp -v "./otoroshi/target/universal/otoroshi-$VERSION.zip" "$LOCATION/release-$VERSION"

# build cli for mac
sh ./scripts/build.sh build_cli
cp -v "./clients/cli/target/release/otoroshicli" "$LOCATION/release-$VERSION"
mv "$LOCATION/release-$VERSION/otoroshicli" "$LOCATION/release-$VERSION/mac-otoroshicli"

# build cli for linux
sh ./scripts/cli-linux-build.sh
cp -v "./clients/cli/target/release/otoroshicli" "$LOCATION/release-$VERSION"
mv "$LOCATION/release-$VERSION/otoroshicli" "$LOCATION/release-$VERSION/linux-otoroshicli"

# TODO : build cli for windows

# tag github
git tag -am "Release Otoroshi version $VERSION" 1.0.0
git push --tags

# push otoroshi.jar on bintray
curl -T "$LOCATION/release-$VERSION/otoroshi.jar" -umathieuancelin:$BINTRAY_API_KEY -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' -H "X-Bintray-Version: $VERSION" -H 'X-Bintray-Package: otoroshi.jar' "https://api.bintray.com/content/maif/binaries/otoroshi.jar/$VERSION/otoroshi.jar"
# push otoroshi-dist on bintray
curl -T "$LOCATION/release-$VERSION/otoroshi-$VERSION.zip" -umathieuancelin:$BINTRAY_API_KEY -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' -H "X-Bintray-Version: $VERSION" -H 'X-Bintray-Package: otoroshi-dist' "https://api.bintray.com/content/maif/binaries/otoroshi.jar/$VERSION/otoroshi-dist.zip"

# push mac-otoroshicli on bintray
curl -T "$LOCATION/release-$VERSION/linux-otoroshicli" -umathieuancelin:$BINTRAY_API_KEY -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' -H "X-Bintray-Version: $VERSION" -H 'X-Bintray-Package: linux-otoroshicli' "https://api.bintray.com/content/maif/binaries/linux-otoroshicli/$VERSION/otoroshicli"
# push linux-otoroshicli on bintray
curl -T "$LOCATION/release-$VERSION/macos-otoroshicli" -umathieuancelin:$BINTRAY_API_KEY -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' -H "X-Bintray-Version: $VERSION" -H 'X-Bintray-Package: mac-otoroshicli' "https://api.bintray.com/content/maif/binaries/mac-otoroshicli/$VERSION/otoroshicli"
# push win-otoroshicli.exe on bintray
# curl -T "$LOCATION/release-$VERSION/win-otoroshicli.exe" -umathieuancelin:$BINTRAY_API_KEY -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' -H "X-Bintray-Version: $VERSION" -H 'X-Bintray-Package: win-otoroshicli' "https://api.bintray.com/content/maif/binaries/win-otoroshicli/$VERSION/otoroshicli.exe"

# TODO : push otoroshi.jar on github
# TODO : push otoroshi-dist on github
# TODO : push mac-otoroshicli on github
# TODO : push linux-otoroshicli on github
# TODO : push win-otoroshicli.exe on github

cd $LOCATION/docker/build
# build docker image
docker build --no-cache -t otoroshi .
# push docker image on bintray
docker tag otoroshi "maif-docker-docker.bintray.io/otoroshi:$VERSION"
docker push "maif-docker-docker.bintray.io/otoroshi:$VERSION"
cd $LOCATION

# TODO : update version number and commit / push