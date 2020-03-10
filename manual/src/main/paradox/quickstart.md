# Try Otoroshi in 5 minutes

@@@ warning
This section is under construction
@@@

## Tools

here you will need 

* JDK 11
* curl
* jq
* a web browser ;)

## Download otoroshi

```sh
curl -L -o otoroshi.jar 'https://github.com/MAIF/otoroshi/releases/download/v1.4.21-dev/otoroshi.jar'
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
[info] otoroshi-env - Otoroshi version 1.4.21-dev
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
  "authorizedGroup": "admin-api-group"
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
  "name": "cats-api", 
  "hosts": ["cats.oto.tools"], 
  "targets": [{ "host": "aws.random.cat", "scheme": "https" }],
  "root": "/meow",
}' | jq
```

but if you try to use it, you will have something like :

```sh
$ curl http://cats.oto.tools:8080 | jq
{
  "Otoroshi-Error": "No ApiKey provided"
}
```

that's because the api is not public and needs apikeys to access it. So let's create apikeys

```sh
curl -X POST -H 'Content-Type: application/json' \
  http://otoroshi-api.oto.tools:8080/api/apikeys/_template \
  -u quickstart:secret \
  -d '{
  "clientId": "apikey1",
  "clientSecret": "secret",
  "clientName": "quickstart-apikey-1",
  "authorizedGroup": "default"
}' | jqs
```  

and try again

```sh
$ curl http://cats.oto.tools:8080 -u apikey1:secret | jq
{
  "file": "https://purr.objects-us-east-1.dream.io/i/vICG4.gif"
}
```