# Try Otoroshi in 5 minutes

what you will need :

* JDK 8
* wget
* 5 minutes of free time

## The elevator pitch

Otoroshi is an awesome reverse proxy built in scala that handle all the calls to and between your microservices without service locator and let you change configuration dynamicaly at runtime.

## Now some sh :)

```sh
wget --quiet https://github.com/MAIF/otoroshi/releases/download/v1.0.0/otoroshi.jar
wget --quiet https://github.com/MAIF/otoroshi/releases/download/v1.0.0/macos-otoroshicli -O otoroshicli
# or if you use linux
wget --quiet https://github.com/MAIF/otoroshi/releases/download/v1.0.0/linux-otoroshicli -O otoroshicli
# or if you use windows
wget --quiet https://github.com/MAIF/otoroshi/releases/download/v1.0.0/win-otoroshicli.exe -O otoroshicli.exe

# Run the Otoroshi server
java -jar otoroshi.jar &

# Check if admin api works
./otoroshicli services all
./otoroshicli apikeys all
./otoroshicli groups all

# Create a service that will proxy call to https://freegeoip.net through http://ip.geo.com:8080
./otoroshicli services create --group default --name geo-ip-api --env prod \
  --domain geo.com --subdomain ip --root /json/ --target https://freegeoip.net \
  --public-pattern '/.*' --no-force-https

# Then test it
./otoroshicli tryout call "http://127.0.0.1:8080/" -X GET -H 'Host: ip.geo.com'
# works with curl -X GET -H 'Host: ip.geo.com' "http://127.0.0.1:8080/" | jqn

# Run 3 new microservices in 3 new terminal processes
./otoroshicli tryout serve 9901
./otoroshicli tryout serve 9902
./otoroshicli tryout serve 9903

# Create a service that will loadbalance between these 3 microservices and serves them through
# http://api.hello.com:8080
./otoroshicli services create --group default --id hello-api --name hello-api \
  --env prod --domain hello.com --subdomain api --root / \
  --target "http://127.0.0.1:9901" \
  --target "http://127.0.0.1:9902" \
  --public-pattern '/.*' --no-force-https --client-retries 3

# Then test it multiple time to observe loadbalancing
# You can also define '127.0.0.1  api.hello.com' in your /etc/hosts file and test it in your browser
./otoroshicli tryout call "http://127.0.0.1:8080/" -H 'Host: api.hello.com' -H 'Accept: application/json'
sudo echo "127.0.0.1    api.hello.com" >> /etc/hosts
open "http://api.hello.com:8080/"

# Then add a new target
./otoroshicli services add-target hello-api --target="http://127.0.0.1:9903"

# Then test it multiple time to observe loadbalancing
./otoroshicli tryout call "http://127.0.0.1:8080/" -H 'Host: api.hello.com' -H 'Accept: application/json'

# Then kill one of the microservices and test it multiple time to observe loadbalancing
./otoroshicli tryout call "http://127.0.0.1:8080/" -H 'Host: api.hello.com' -H 'Accept: application/json'

# Then kill a second microservices and test it multiple time to observe loadbalancing
./otoroshicli tryout call "http://127.0.0.1:8080/" -H 'Host: api.hello.com' -H 'Accept: application/json'

# Then kill the last microservices and test it to observe connection error
./otoroshicli tryout call "http://127.0.0.1:8080/" -H 'Host: api.hello.com' -H 'Accept: application/json'

# Then delete your service
./otoroshicli services delete hello-api
```

## What about the UI

first you need to add the following line to your `/etc/hosts` file.

```
127.0.0.1     otoroshi-api.foo.bar otoroshi.foo.bar otoroshi-admin-internal-api.foo.bar privateapps.foo.bar
```

you can user the following command 

```sh
sudo echo "127.0.0.1     otoroshi-api.foo.bar otoroshi.foo.bar otoroshi-admin-internal-api.foo.bar privateapps.foo.bar" >> /etc/hosts
```

Then go to <a href="http://otoroshi.foo.bar:8080/" target="_blank">http://otoroshi.foo.bar:8080/</a> and login with `admin@otoroshi.io:password` ;-)

If you want to know more about Otoroshi, you should continue reading the documentation starting with @ref:[how to get Otoroshi](./getotoroshi/index.md)
