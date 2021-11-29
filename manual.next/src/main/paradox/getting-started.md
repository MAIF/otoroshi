# Getting Started

- [Protect the access to your api with the Otoroshi management of api keys](#protect-the-access-to-your-api-with-the-otoroshi-management-of-api-keys)
- [Secure your web app in 5 minutes with an authentication](#secure-your-web-app-in-5-minutes-with-an-authentication)

Download the latest jar of Otoroshi
```sh
curl -L -o otoroshi.jar 'https://github.com/MAIF/otoroshi/releases/download/v1.5.0-beta.8/otoroshi.jar'
```

Once downloading, run Otoroshi.
```sh
java -Dapp.adminPassword=password -jar otoroshi.jar 
```

## Protect the access to your api with the Otoroshi management of api keys

Create a service, exposed on `http://myapi.oto.tools:8080`, which will forward all requests to the mirror `https://mirror.otoroshi.io`.

```sh
curl -X POST http://otoroshi-api.oto.tools:8080/api/services \
-d '{"enforceSecureCommunication": false, "forceHttps": false, "_loc":{"tenant":"default","teams":["default"]},"groupId":"default","groups":["default"],"name":"myapi","description":"a service","env":"prod","domain":"oto.tools","subdomain":"myapi","targetsLoadBalancing":{"type":"RoundRobin"},"targets":[{"host":"mirror.otoroshi.io","scheme":"https","weight":1,"mtlsConfig":{"certs":[],"trustedCerts":[],"mtls":false,"loose":false,"trustAll":false},"tags":[],"metadata":{},"protocol":"HTTP\/1.1","predicate":{"type":"AlwaysMatch"},"ipAddress":null}],"root":"\/","matchingRoot":null,"stripPath":true,"enabled":true,"secComHeaders":{"claimRequestName":null,"stateRequestName":null,"stateResponseName":null},"publicPatterns":[""],"privatePatterns":["\/.*"],"kind":"ServiceDescriptor"}' \
-H "Content-type: application/json" \
-u admin-api-apikey-id:admin-api-apikey-secret
```

Try to call this service. You should receive an error from Otoroshi about a missing api key in our request.

```sh
curl http://myapi.oto.tools:8080
```

Create your first api key with a quota of ten calls by day and month.

```sh
curl -X POST http://otoroshi-api.oto.tools:8080/api/apikeys \
-H "Content-type: application/json" \
-u admin-api-apikey-id:admin-api-apikey-secret \
-d @- <<'EOF'
{
    "clientId": "my-first-apikey-id",
    "clientSecret": "my-first-apikey-secret",
    "clientName": "my-first-apikey",
    "description": "my-first-apikey-description",
    "authorizedGroup": "default",
    "enabled": true,
    "throttlingQuota": 10,
    "dailyQuota": 10,
    "monthlyQuota": 10
}
EOF
```

Call your api with the generated apikey.

```sh
curl http://myapi.oto.tools:8080 -u my-first-apikey-id:my-first-apikey-secret
```

```json
{
  "method": "GET",
  "path": "/",
  "headers": {
    "host": "mirror.opunmaif.io",
    "accept": "*/*",
    "user-agent": "curl/7.64.1",
    "authorization": "Basic bXktZmlyc3QtYXBpLWtleS1pZDpteS1maXJzdC1hcGkta2V5LXNlY3JldA==",
    "opun-proxied-host": "mirror.otoroshi.io",
    "otoroshi-request-id": "1465298507974836306",
    "otoroshi-proxied-host": "myapi.oto.tools:8080",
    "opun-gateway-request-id": "1465298508335550350",
    "otoroshi-request-timestamp": "2021-11-29T13:36:02.888+01:00",
    "opun-gateway-request-timestamp": "2021-11-29T12:36:02.977+00:00"
  },
  "body": ""
}
```

Check your remaining quotas

```sh
curl http://myapi.oto.tools:8080 -u my-first-apikey-id:my-first-apikey-secret --include
```

This should output these following Otoroshi headers

```json
Otoroshi-Daily-Calls-Remaining: 6
Otoroshi-Monthly-Calls-Remaining: 6
```

Keep calling the api and confirm that Otoroshi is sending you an apikey exceeding quota error


```json
{ 
    "Otoroshi-Error": "You performed too much requests"
}
```

Well done, you have secured your api with an apikeys system with limited call quotas.

## Secure your web app in 5 minutes with an authentication

Create an authentication module to protect your service.

```sh
curl -X POST http://otoroshi-api.oto.tools:8080/api/auths \
-H "Otoroshi-Client-Id: admin-api-apikey-id" \
-H "Otoroshi-Client-Secret: admin-api-apikey-secret" \
-H 'Content-Type: application/json; charset=utf-8' \
-d @- <<'EOF'
{
    "type":"basic",
    "id":"auth_mod_in_memory_auth",
    "name":"in-memory-auth",
    "desc":"in-memory-auth",
    "users":[
        {
            "name":"User Otoroshi",
            "password":"$2a$10$oIf4JkaOsfiypk5ZK8DKOumiNbb2xHMZUkYkuJyuIqMDYnR/zXj9i",
            "email":"user@foo.bar",
            "metadata":{
                "username":"roger"
            },
            "tags":["foo"],
            "webauthn":null,
            "rights":[{
                "tenant":"*:r",
                "teams":["*:r"]
            }]
        }
    ],
    "sessionCookieValues":{
        "httpOnly":true,
        "secure":false
    }
}
EOF
```

Then create a service secure by the previous authentication module, which proxie `google.fr` on `webapp.oto.tools`.

```sh
curl -X POST http://otoroshi-api.oto.tools:8080/api/services \
-d '{"enforceSecureCommunication": false, "forceHttps": false, "_loc":{"tenant":"default","teams":["default"]},"groupId":"default","groups":["default"],"name":"webapp","description":"a service","env":"prod","domain":"oto.tools","subdomain":"webapp","targetsLoadBalancing":{"type":"RoundRobin"},"targets":[{"host":"google.fr","scheme":"https","weight":1,"mtlsConfig":{"certs":[],"trustedCerts":[],"mtls":false,"loose":false,"trustAll":false},"tags":[],"metadata":{},"protocol":"HTTP\/1.1","predicate":{"type":"AlwaysMatch"},"ipAddress":null}],"root":"\/","matchingRoot":null,"stripPath":true,"enabled":true,"secComHeaders":{"claimRequestName":null,"stateRequestName":null,"stateResponseName":null},"publicPatterns":["\/.*"],"privatePatterns":[""],"kind":"ServiceDescriptor","authConfigRef":"auth_mod_in_memory_auth","privateApp":true}' \
-H "Content-type: application/json" \
-u admin-api-apikey-id:admin-api-apikey-secret
```

Navigate to http://webapp.oto.tools:8080, login with `user@foo.bar/password` and check that you're redirect to `google` page.

Well done! You completed the discovery tutorial.