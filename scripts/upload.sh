#!/bin/bash

echo "Uploading otoroshicli"
# curl -T ./clients/cli/target/release/otoroshicli -umathieuancelin:$BINTRAY_API_KEY https://api.bintray.com/content/mathieuancelin/otoroshi/linux-otoroshicli/lastest/linux-otoroshicli

echo "Uploading otoroshi.jar"
curl -T ./otoroshi/target/scala-2.11/otoroshi.jar -umathieuancelin:$BINTRAY_API_KEY https://api.bintray.com/content/mathieuancelin/otoroshi/otoroshi.jar/lastest/otoroshi.jar