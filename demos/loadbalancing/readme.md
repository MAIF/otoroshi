# Otoroshi demo

a small server/injector based demo of Otoroshi and it loadbalancing/retry capabilities. Before anything else, don't forget to install dependencies with `yarn install`

# Scenario

get and start Otoroshi

```sh
curl -L -o otoroshi.jar https://github.com/MAIF/otoroshi/releases/download/v1.4.10/otoroshi.jar
curl -L -o otoroshicli.toml https://raw.githubusercontent.com/MAIF/otoroshi/master/clients/cli/otoroshicli.toml
curl -L -o otoroshicli  https://github.com/MAIF/otoroshi/releases/download/v1.4.10/linux-otoroshicli
chmod +x otoroshicli

java -jar otoroshi.jar &
```

then launch some test servers

```sh
node demo.js server --port 8081 --name "server 1" &
node demo.js server --port 8082 --name "server 2" &
node demo.js server --port 8083 --name "server 3" &
```

now, create a new Otoroshi service

```sh
./otoroshicli services create \
  --group default \
  --id hello-api \
  --name hello-api \
  --env prod \
  --domain foo.bar \
  --subdomain api \
  --root / \
  --target "http://127.0.0.1:8081" \
  --public-pattern '/.*' \
  --no-force-https
```

and run the injector

```sh
node demo.js injector
```

then add/remove targets and see what happens

```sh
./otoroshicli services add-target hello-api --target "http://127.0.0.1:8082"
./otoroshicli services add-target hello-api --target "http://127.0.0.1:8083"
./otoroshicli services rem-target hello-api --target "http://127.0.0.1:8083"
./otoroshicli services rem-target hello-api --target "http://127.0.0.1:8082"
```

now, add all the targets and try to stop some servers. 
You will see error count incrementing. 

Now add some retry to the service client

```sh
./otoroshicli services update hello-api --client-retries 3
```

and try to stop/start servers again