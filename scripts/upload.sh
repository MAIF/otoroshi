#!/bin/bash

echo "Uploading otoroshicli"
unamestr=`uname`
if [[ "$unamestr" == 'Linux' ]]; then
	curl -T ./clients/cli/target/release/otoroshicli -umathieuancelin:$BINTRAY_API_KEY -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' https://api.bintray.com/content/mathieuancelin/otoroshi/linux-otoroshicli/lastest/otoroshicli
elif [[ "$unamestr" == 'Darwin' ]]; then
	curl -T ./clients/cli/target/release/otoroshicli -umathieuancelin:$BINTRAY_API_KEY -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' https://api.bintray.com/content/mathieuancelin/otoroshi/mac-otoroshicli/lastest/otoroshicli
fi

echo "Uploading otoroshi.jar"
curl -T ./otoroshi/target/scala-2.11/otoroshi.jar -umathieuancelin:$BINTRAY_API_KEY -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' https://api.bintray.com/content/mathieuancelin/otoroshi/otoroshi.jar/lastest/otoroshi.jar