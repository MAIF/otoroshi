<!--- #initialize-otoroshi --->
Let's start by downloading the latest Otoroshi.

```sh
curl -L -o otoroshi.jar 'https://github.com/MAIF/otoroshi/releases/download/v1.5.0-beta.8/otoroshi.jar'
```

then you can run start Otoroshi :

```sh
java -Dapp.adminPassword=password -jar otoroshi.jar 
```

Now you can log into Otoroshi at http://otoroshi.oto.tools:8080 with `admin@otoroshi.io/password`

Create a service, exposed on `http://myservice.oto.tools:8080`, which will forward all requests to the mirror `https://mirror.otoroshi.io`. Each call to this service will returned the body and the headers received by the mirror.

```sh
curl -X POST 'http://otoroshi-api.oto.tools:8080/api/services' \
-d '{"enforceSecureCommunication": false, "forceHttps": false, "_loc":{"tenant":"default","teams":["default"]},"groupId":"default","groups":["default"],"name":"my-service","description":"a service","env":"prod","domain":"oto.tools","subdomain":"myservice","targetsLoadBalancing":{"type":"RoundRobin"},"targets":[{"host":"mirror.otoroshi.io","scheme":"https","weight":1,"mtlsConfig":{"certs":[],"trustedCerts":[],"mtls":false,"loose":false,"trustAll":false},"tags":[],"metadata":{},"protocol":"HTTP\/1.1","predicate":{"type":"AlwaysMatch"},"ipAddress":null}],"root":"\/","matchingRoot":null,"stripPath":true,"enabled":true,"secComHeaders":{"claimRequestName":null,"stateRequestName":null,"stateResponseName":null},"publicPatterns":["\/.*"],"privatePatterns":[],"kind":"ServiceDescriptor"}' \
-H "Content-type: application/json" \
-u admin-api-apikey-id:admin-api-apikey-secret
```
<!--- #initialize-otoroshi --->