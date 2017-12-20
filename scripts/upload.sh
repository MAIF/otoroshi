#!/bin/bash

curl -T ./otoroshi/target/scala-2.11/otoroshi.jar -umathieuancelin:$BINTRAY_API_KEY https://api.bintray.com/content/mathieuancelin/otoroshi/otoroshi.jar/lastest/otoroshi.jar
# curl -T ./clients/cli/target/release/otoroshicli -umathieuancelin:$BINTRAY_API_KEY https://api.bintray.com/content/mathieuancelin/otoroshi/otoroshicli/lastest/otoroshicli
