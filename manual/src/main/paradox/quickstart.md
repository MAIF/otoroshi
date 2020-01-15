# Try Otoroshi in 5 minutes

what you will need :

* JDK 8
* curl
* 5 minutes of free time

If you don't/can't have these tools on your machine, you can start a sandboxed environment using here with the following command

```sh
docker run -p "8080:8080" -it maif/otoroshi bash
```

or you can also try Otoroshi online with Google Cloud Shell if you're a user of the Google Cloud Platform

@@@ div { .centered-img }
[![Open in Cloud Shell](http://gstatic.com/cloudssh/images/open-btn.svg)](https://console.cloud.google.com/cloudshell/open?git_repo=https%3A%2F%2Fgithub.com%2Fmathieuancelin%2Fotoroshi-tutorial&page=shell&tutorial=tutorial.md)
@@@

## The elevator pitch

Otoroshi is an awesome reverse proxy built with Scala that handles all the calls to and between your microservices without service locator and lets you change configuration dynamically at runtime.

## I like sh but I really want to see the UI

As Otoroshi uses Otoroshi to serve its own admin UI and admin API, you won't be able to access the admin UI on `http://localhost:8080`. Otoroshi needs a domain name to know that you want to access the admin UI. By default, the admin UI is exposed on `http://otoroshi.oto.tools:8080`. Of course you can @ref:[configure](./firstrun/configfile.md#common-configuration) the domain of the subdomain. To configure access to the admin, just go to the [UI section of the quickstart](#what-about-the-ui).

## Now some sh :)

```sh
curl -L -o otoroshi.jar https://github.com/MAIF/otoroshi/releases/download/v1.4.17-dev/otoroshi.jar
curl -L -o otoroshicli https://github.com/MAIF/otoroshi/releases/download/v1.4.17-dev/linux-otoroshicli

chmod +x otoroshicli

# Run the Otoroshi server on Java 8
java -jar otoroshi.jar &

# Run the Otoroshi server on Java 9 and 10
java --add-modules java.xml.bind -jar otoroshi.jar &

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

Go to <a href="http://otoroshi.oto.tools:8080/" target="_blank">http://otoroshi.oto.tools:8080/</a> and **log in with the credential generated in the logs** during first startup ;-)

If you want to know more about Otoroshi, you should continue reading the documentation starting with @ref:[how to get Otoroshi](./getotoroshi/index.md)

## I don't have JDK 8 on my machine but I have Docker :)

If you want to use Docker, just follow these instructions

```sh
# here be careful, if you want to change the OTOROSHI_PORT, change the same value in the `otoroshicli.toml` config file
export OTOROSHI_PORT=8080
export LOCAL_IP_ADDRESS=999.999.999.999 # use your real local ip address here

curl -L -o otoroshicli https://github.com/MAIF/otoroshi/releases/download/v1.4.17-dev/linux-otoroshicli

chmod +x otoroshicli 

docker run -p "$OTOROSHI_PORT:8080" maif/otoroshi &

# Check if admin api works
./otoroshicli services all
./otoroshicli apikeys all
./otoroshicli groups all

# Create a service that will proxy call to https://freegeoip.net through http://ip.geo.com:$OTOROSHI_PORT
./otoroshicli services create --group default --name geo-ip-api --env prod \
  --domain geo.com --subdomain ip --root /json/ --target https://freegeoip.net \
  --public-pattern '/.*' --no-force-https

# Then test it
./otoroshicli tryout call "http://127.0.0.1:$OTOROSHI_PORT/" -X GET -H 'Host: ip.geo.com'
# works with curl -X GET -H 'Host: ip.geo.com' "http://127.0.0.1:$OTOROSHI_PORT/" | jqn

# Run 3 new microservices in 3 new terminal processes
./otoroshicli tryout serve 9901
./otoroshicli tryout serve 9902
./otoroshicli tryout serve 9903

# Create a service that will loadbalance between these 3 microservices and serves them through
# http://api.hello.com:$OTOROSHI_PORT
./otoroshicli services create --group default --id hello-api --name hello-api \
  --env prod --domain hello.com --subdomain api --root / \
  --target "http://$LOCAL_IP_ADDRESS:9901" \
  --target "http://$LOCAL_IP_ADDRESS:9902" \
  --public-pattern '/.*' --no-force-https --client-retries 3

# Then test it multiple time to observe loadbalancing
# You can also define '127.0.0.1  api.hello.com' in your /etc/hosts file and test it in your browser
./otoroshicli tryout call "http://127.0.0.1:$OTOROSHI_PORT/" -H 'Host: api.hello.com' -H 'Accept: application/json'
sudo echo "127.0.0.1    api.hello.com" >> /etc/hosts
open "http://api.hello.com:$OTOROSHI_PORT/"

# Then add a new target
./otoroshicli services add-target hello-api --target="http://$LOCAL_IP_ADDRESS:9903"

# Then test it multiple time to observe loadbalancing
./otoroshicli tryout call "http://127.0.0.1:$OTOROSHI_PORT/" -H 'Host: api.hello.com' -H 'Accept: application/json'

# Then kill one of the microservices and test it multiple time to observe loadbalancing
./otoroshicli tryout call "http://127.0.0.1:$OTOROSHI_PORT/" -H 'Host: api.hello.com' -H 'Accept: application/json'

# Then kill a second microservices and test it multiple time to observe loadbalancing
./otoroshicli tryout call "http://127.0.0.1:$OTOROSHI_PORT/" -H 'Host: api.hello.com' -H 'Accept: application/json'

# Then kill the last microservices and test it to observe connection error
./otoroshicli tryout call "http://127.0.0.1:$OTOROSHI_PORT/" -H 'Host: api.hello.com' -H 'Accept: application/json'

# Then delete your service
./otoroshicli services delete hello-api
```
