#!/bin/sh

# echo "Uploading otoroshicli to bintray"
# unamestr=`uname`
# if [ "$unamestr" == 'Linux' ]; then
# 	curl -T ./clients/cli/target/release/otoroshicli -umathieuancelin:$BINTRAY_API_KEY -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' https://api.bintray.com/content/maif/binaries/linux-otoroshicli/latest/otoroshicli
# elif [ "$unamestr" == 'Darwin' ]; then
# 	curl -T ./clients/cli/target/release/otoroshicli -umathieuancelin:$BINTRAY_API_KEY -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' https://api.bintray.com/content/maif/binaries/mac-otoroshicli/latest/otoroshicli
# fi

# curl -T ./linux-otoroshicli -umathieuancelin:$BINTRAY_API_KEY -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' -H 'X-Bintray-Version: latest' -H 'X-Bintray-Package: linux-otoroshicli' https://api.bintray.com/content/maif/binaries/linux-otoroshicli/latest/otoroshicli
# curl -T ./macos-otoroshicli -umathieuancelin:$BINTRAY_API_KEY -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' -H 'X-Bintray-Version: latest' -H 'X-Bintray-Package: mac-otoroshicli' https://api.bintray.com/content/maif/binaries/mac-otoroshicli/latest/otoroshicli
# curl -T ./win-otoroshicli.exe -umathieuancelin:$BINTRAY_API_KEY -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' -H 'X-Bintray-Version: latest' -H 'X-Bintray-Package: win-otoroshicli' https://api.bintray.com/content/maif/binaries/win-otoroshicli/latest/otoroshicli.exe

echo "Uploading otoroshi.jar"
curl -T ./otoroshi/target/scala-2.11/otoroshi.jar -umathieuancelin:$BINTRAY_API_KEY -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' -H 'X-Bintray-Version: latest' -H 'X-Bintray-Package: otoroshi.jar' https://api.bintray.com/content/maif/binaries/otoroshi.jar/latest/otoroshi.jar
curl -T ./otoroshi/target/universal/otoroshi-1.0.0.zip -umathieuancelin:$BINTRAY_API_KEY -H 'X-Bintray-Publish: 1' -H 'X-Bintray-Override: 1' -H 'X-Bintray-Version: latest' -H 'X-Bintray-Package: otoroshi-dist' https://api.bintray.com/content/maif/binaries/otoroshi-dist/latest/otoroshi-dist.zip