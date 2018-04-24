#!/bin/sh

LOCATION=`pwd`
VERSION="$1"
NEXT_VERSION="$2"

echo "Releasing Otoroshi version $VERSION ..."
echo " "

#ack '(1\.1\.1|1\.1\.2)' --ignore-dir=node_modules --ignore-dir=docs --ignore-dir=target --ignore-dir=bundle --ignore-file=is:yarn.lock --ignore-file=is:Cargo.lock --ignore-dir=.idea --ignore-dir=otoroshi/.idea --ignore-file=is:swagger-ui-bundle.js --ignore-dir=otoroshi/project --ignore-dir=manual/project --ignore-dir=docker/dev

mkdir -p "release-$VERSION"

# format code
sh ./scripts/fmt.sh

# clean
sh ./scripts/build.sh clean

# build doc with schemas
sh ./scripts/doc.sh all

# build ui
sh ./scripts/build.sh ui

# build server
sh ./scripts/build.sh server

cp -v "./otoroshi/target/scala-2.12/otoroshi.jar" "$LOCATION/release-$VERSION"
cp -v "./otoroshi/target/universal/otoroshi-$VERSION.zip" "$LOCATION/release-$VERSION"

# build cli for mac
sh ./scripts/build.sh cli
cp -v "./clients/cli/target/release/otoroshicli" "$LOCATION/release-$VERSION"
mv "$LOCATION/release-$VERSION/otoroshicli" "$LOCATION/release-$VERSION/mac-otoroshicli"

# build cli for linux
sh ./scripts/cli-linux-build.sh
cp -v "./clients/cli/target/release/otoroshicli" "$LOCATION/release-$VERSION"
mv "$LOCATION/release-$VERSION/otoroshicli" "$LOCATION/release-$VERSION/linux-otoroshicli"

# TODO : build cli for windows

# tag github
git commit -am "Prepare the release of Otoroshi version $VERSION"
git push origin master
git tag -am "Release Otoroshi version $VERSION" "v$VERSION"
git push --tags

# push otoroshi.jar on bintray
curl -T "$LOCATION/release-$VERSION/otoroshi.jar" -umathieuancelin:$BINTRAY_API_KEY -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' -H "X-Bintray-Version: $VERSION" -H 'X-Bintray-Package: otoroshi.jar' "https://api.bintray.com/content/maif/binaries/otoroshi.jar/$VERSION/otoroshi.jar"
# push otoroshi-dist on bintray
curl -T "$LOCATION/release-$VERSION/otoroshi-$VERSION.zip" -umathieuancelin:$BINTRAY_API_KEY -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' -H "X-Bintray-Version: $VERSION" -H 'X-Bintray-Package: otoroshi-dist' "https://api.bintray.com/content/maif/binaries/otoroshi-dist/$VERSION/otoroshi-dist.zip"

# push mac-otoroshicli on bintray
curl -T "$LOCATION/release-$VERSION/linux-otoroshicli" -umathieuancelin:$BINTRAY_API_KEY -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' -H "X-Bintray-Version: $VERSION" -H 'X-Bintray-Package: linux-otoroshicli' "https://api.bintray.com/content/maif/binaries/linux-otoroshicli/$VERSION/otoroshicli"
# push linux-otoroshicli on bintray
curl -T "$LOCATION/release-$VERSION/mac-otoroshicli" -umathieuancelin:$BINTRAY_API_KEY -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' -H "X-Bintray-Version: $VERSION" -H 'X-Bintray-Package: mac-otoroshicli' "https://api.bintray.com/content/maif/binaries/mac-otoroshicli/$VERSION/otoroshicli"
# push win-otoroshicli.exe on bintray
# curl -T "$LOCATION/release-$VERSION/win-otoroshicli.exe" -umathieuancelin:$BINTRAY_API_KEY -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' -H "X-Bintray-Version: $VERSION" -H 'X-Bintray-Package: win-otoroshicli' "https://api.bintray.com/content/maif/binaries/win-otoroshicli/$VERSION/otoroshicli.exe"

create_release () {
  curl -X POST -H 'Accept: application/json' -H 'Content-Type: application/json' -H "Authorization: token $GITHUB_TOKEN" "https://api.github.com/repos/MAIF/otoroshi/releases" -d "
  {
    \"tag_name\": \"v$VERSION\",
    \"name\": \"$VERSION\",
    \"body\": \"Otoroshi version $VERSION\",
    \"draft\": true,
    \"prerelease\": false
  }" | jqn 'property("id")' --color=false
}

# Create github release
#ID=`create_release`
#echo "Release ID is $ID"
# push otoroshi.jar on github
# curl -T "$LOCATION/release-$VERSION/otoroshi.jar" -H "Content-Type: application/octet-stream" -H "Authorization: token $GITHUB_TOKEN" "https://uploads.github.com/repos/MAIF/otoroshi/releases/$ID/assets\?name\=otoroshi.jar"
# push otoroshi-dist on github
#curl -T "$LOCATION/release-$VERSION/otoroshi-$VERSION.zip" -H "Content-Type: application/zip" -H "Authorization: token $GITHUB_TOKEN" "https://uploads.github.com/repos/MAIF/otoroshi/releases/$ID/assets\?name\=otoroshi-dist.zip"
# push mac-otoroshicli on github
#curl -T "$LOCATION/release-$VERSION/mac-otoroshicli" -H "Content-Type: application/octet-stream" -H "Authorization: token $GITHUB_TOKEN" "https://uploads.github.com/repos/MAIF/otoroshi/releases/$ID/assets\?name\=mac-otoroshicli"
# push linux-otoroshicli on github
#curl -T "$LOCATION/release-$VERSION/linux-otoroshicli" -H "Content-Type: application/octet-stream" -H "Authorization: token $GITHUB_TOKEN" "https://uploads.github.com/repos/MAIF/otoroshi/releases/$ID/assets\?name\=linux-otoroshicli"
# push win-otoroshicli.exe on github
# curl -T "$LOCATION/release-$VERSION/win-otoroshicli.exe" -H "Content-Type: application/octet-stream"  -H "Authorization: token $GITHUB_TOKEN" "https://uploads.github.com/repos/MAIF/otoroshi/releases/$ID/assets\?name\=otoroshicli.exe",

cd $LOCATION/docker/build
cp ../../otoroshi/target/universal/otoroshi-$VERSION.zip ./otoroshi-dist.zip
# build docker image
docker build --no-cache -t otoroshi .
rm ./otoroshi-dist.zip
# push docker image on dockerhub
docker tag otoroshi "maif/otoroshi:$VERSION"
docker push "maif/otoroshi:$VERSION"

cd $LOCATION/docker/otoroshicli
cp ../../clients/cli/target/release/otoroshicli ./otoroshicli
# build docker image
docker build --no-cache -t otoroshicli .
rm ./otoroshicli
# push docker image on dockerhub
docker tag otoroshicli "maif/otoroshicli:$VERSION"
docker push "maif/otoroshicli:$VERSION"
cd $LOCATION

cd $LOCATION/docker/demo
# build docker image
docker build --no-cache -t otoroshi-demo .
# push docker image on dockerhub
docker tag otoroshicli "maif/otoroshi-demo:$VERSION"
docker push "maif/otoroshi-demo:$VERSION"
cd $LOCATION

# cd $LOCATION/docker/dev
# build docker image
# docker build --no-cache -t otoroshi-dev .
# push docker image on dockerhub
# docker tag otoroshicli "maif/otoroshi-dev:$VERSION"
# docker push "maif/otoroshi-dev:$VERSION"
# cd $LOCATION

# update version number and commit / push
echo "Please change version in the following files and commit / push"
echo "                                                            "
echo "  * clients/cli/Cargo.lock                                  "
echo "  * clients/cli/Cargo.toml                                  "
echo "  * clients/cli/src/main.rs                                 "
echo "  * docker/build/Dockerfile                                 "
echo "  * docker/otoroshicli/Dockerfile                           "
echo "  * otoroshi/app/controllers/SwaggerController.scala        "
echo "  * otoroshi/build.sbt                                      "
echo "  * otoroshi/javascript/package.json                        "
echo "  * scripts/upload.sh                                       "
echo "                                                            "

# remove release folder
# rm -rf "$LOCATION/release-$VERSION"