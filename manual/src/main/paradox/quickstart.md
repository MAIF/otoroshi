# Try Otoroshi in 5 minutes

what you will need :

* JDK 11
* curl
* jq
* 5 minutes of free time

## The elevator pitch

Otoroshi is an awesome reverse proxy built with Scala that handles all the calls to and between your microservices without service locator and lets you change configuration dynamically at runtime.

## Download otoroshi

```sh
curl -L -o otoroshi.jar 'https://github.com/MAIF/otoroshi/releases/download/v1.5.0-alpha.6/otoroshi.jar'
```

If you don’t/can’t have these tools on your machine, you can start a sandboxed environment using here with the following command

```sh
docker run -p "8080:8080" maif/otoroshi
```

## Start otoroshi

to start otoroshi, just run the following command 

```sh
java -jar otoroshi.jar
```

this will start an in-memory otoroshi instance with a generated password that will be printed in the logs. You can set the password with the following flags

```sh
java -Dapp.adminLogin=admin@foo.bar -Dapp.adminPassword=password -jar otoroshi.jar
```

if you want to have otoroshi content persisted between launch without having to setup a datastore, just usse the following flag

```sh
java -Dapp.storage=file -jar otoroshi.jar
```

as the result, you will see something like

```log
$ java -jar otoroshi.jar

[info] otoroshi-env - Otoroshi version 1.5.0-alpha.6
[info] otoroshi-env - Admin API exposed on http://otoroshi-api.oto.tools:8080
[info] otoroshi-env - Admin UI  exposed on http://otoroshi.oto.tools:8080
[warn] otoroshi-env - Scripting is enabled on this Otoroshi instance !
[info] otoroshi-in-memory-datastores - Now using InMemory DataStores
[info] otoroshi-env - The main datastore seems to be empty, registering some basic services
[info] otoroshi-env - You can log into the Otoroshi admin console with the following credentials: admin@otoroshi.io / xol1Kwjzqe9OXjqDxxPPbPb9p0BPjhCO
[info] play.api.Play - Application started (Prod)
[info] otoroshi-script-manager - Compiling and starting scripts ...
[info] otoroshi-script-manager - Finding and starting plugins ...
[info] otoroshi-script-manager - Compiling and starting scripts done in 18 ms.
[info] p.c.s.AkkaHttpServer - Listening for HTTP on /0:0:0:0:0:0:0:0:8080
[info] p.c.s.AkkaHttpServer - Listening for HTTPS on /0:0:0:0:0:0:0:0:8443
[info] otoroshi-script-manager - Finding and starting plugins done in 4681 ms.
[info] otoroshi-env - Generating CA certificate for Otoroshi self signed certificates ...
[info] otoroshi-env - Generating a self signed SSL certificate for https://*.oto.tools ...
```

## Log into the admin UI

just go to http://otoroshi.oto.tools:8080 and log in with the credentials printed in the logs

## Create you first service

to create your first service you can either do it using the admin UI or using the admin API. Let's use the admin API.

By default, otoroshi registers an admin apikey with `admin-api-apikey-id:admin-api-apikey-secret` value (those values can be tuned at first startup). Of course you can create your own with

```sh
curl -X POST -H 'Content-Type: application/json' \
  http://otoroshi-api.oto.tools:8080/api/apikeys/_template \
  -u admin-api-apikey-id:admin-api-apikey-secret \
  -d '{
  "clientId": "quickstart",
  "clientSecret": "secret",
  "clientName": "quickstart-apikey",
  "authorizedEntities": ["group_admin-api-group"]
}' | jq
```

now let create a new service to proxy `https://maif.gitub.io` on domain `maif.oto.tools`. This service will be public and will not require an apikey to pass

```sh
curl -X POST -H 'Content-Type: application/json' \
  http://otoroshi-api.oto.tools:8080/api/services/_template \
  -u quickstart:secret \
  -d '{
  "name": "quickstart-service", 
  "hosts": ["maif.oto.tools"], 
  "targets": [{ "host": "maif.github.io", "scheme": "https" }], 
  "publicPatterns": ["/.*"]
}' | jq
```

now just go to `http://maif.oto.tools:8080` to check if it works

## Create a service to proxy an api

now will we proxy the api at `https://aws.random.cat/meow` that returns random cat pictures and make it use apikeys.

```sh
$ curl https://aws.random.cat/meow | jq

{
  "file": "https://purr.objects-us-east-1.dream.io/i/20161003_163413.jpg"
}
```

First let's create the service 

```sh
curl -X POST -H 'Content-Type: application/json' \
  http://otoroshi-api.oto.tools:8080/api/services/_template \
  -u quickstart:secret \
  -d '{
  "id": "cats-api",
  "name": "cats-api", 
  "hosts": ["cats.oto.tools"], 
  "targets": [{ "host": "aws.random.cat", "scheme": "https" }],
  "root": "/meow"
}' | jq
```

but if you try to use it, you will have something like :

```sh
$ curl http://cats.oto.tools:8080 | jq

{
  "Otoroshi-Error": "No ApiKey provided"
}
```

that's because the api is not public and needs apikeys to access it. So let's create an apikey

```sh
curl -X POST -H 'Content-Type: application/json' \
  http://otoroshi-api.oto.tools:8080/api/apikeys/_template \
  -u quickstart:secret \
  -d '{
  "clientId": "apikey1",
  "clientSecret": "secret",
  "clientName": "quickstart-apikey-1",
  "authorizedEntities": ["group_default"]
}' | jq
```  

and try again

```sh
$ curl http://cats.oto.tools:8080 -u apikey1:secret | jq

{
  "file": "https://purr.objects-us-east-1.dream.io/i/vICG4.gif"
}
```

now let's try to play with quotas. First, we need to know what is the current state of the apikey quotas by enabling otoroshi headers about consumptions

```sh
curl -X PATCH -H 'Content-Type: application/json' \
  http://otoroshi-api.oto.tools:8080/api/services/cats-api \
  -u quickstart:secret \
  -d '[
  { "op": "replace", "path": "/sendOtoroshiHeadersBack", "value": true }
]' | jq
```

and retry the call with 

```sh
$ curl http://cats.oto.tools:8080 -u apikey1:secret --include

HTTP/1.1 200 OK
Date: Tue, 10 Mar 2020 12:56:08 GMT
Server: Apache
Expires: Mon, 26 Jul 1997 05:00:00 GMT
Cache-Control: no-cache, must-revalidate
Otoroshi-Request-Id: 1237361356529729796
Otoroshi-Proxy-Latency: 79
Otoroshi-Upstream-Latency: 416
Otoroshi-Request-Timestamp: 2020-03-10T13:55:11.195+01:00
Access-Control-Allow-Origin: *
Access-Control-Allow-Methods: GET
Otoroshi-Daily-Calls-Remaining: 9999998
Otoroshi-Monthly-Calls-Remaining: 9999998
Content-Type: application/json
Content-Length: 71

{"file":"https:\/\/purr.objects-us-east-1.dream.io\/i\/beerandcat.jpg"}
```

now let's try to allow only 10 request per day on the apikey

```sh
curl -X PATCH -H 'Content-Type: application/json' \
  http://otoroshi-api.oto.tools:8080/api/services/cats-api/apikeys/apikey1 \
  -u quickstart:secret \
  -d '[
  { "op": "replace", "path": "/dailyQuota", "value": 10 }
]' | jq
```

then try to call you api again

```sh
$ curl http://cats.oto.tools:8080 -u apikey1:secret --include

HTTP/1.1 200 OK
Date: Tue, 10 Mar 2020 13:00:01 GMT
Server: Apache
Expires: Mon, 26 Jul 1997 05:00:00 GMT
Cache-Control: no-cache, must-revalidate
Otoroshi-Request-Id: 1237362334930829633
Otoroshi-Proxy-Latency: 71
Otoroshi-Upstream-Latency: 92
Otoroshi-Request-Timestamp: 2020-03-10T13:59:04.456+01:00
Access-Control-Allow-Origin: *
Access-Control-Allow-Methods: GET
Otoroshi-Daily-Calls-Remaining: 7
Otoroshi-Monthly-Calls-Remaining: 9999997
Content-Type: application/json
Content-Length: 66

{"file":"https:\/\/purr.objects-us-east-1.dream.io\/i\/C1XNK.jpg"}
```

eventually you will get something like

```sh
$ curl http://cats.oto.tools:8080 -u apikey1:secret --include

HTTP/1.1 429 Too Many Requests
Otoroshi-Error: true
Otoroshi-Error-Msg: You performed too much requests
Otoroshi-State-Resp: --
Date: Tue, 10 Mar 2020 12:59:11 GMT
Content-Type: application/json
Content-Length: 52

{"Otoroshi-Error":"You performed too much requests"}
```