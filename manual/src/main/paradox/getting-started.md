# Getting Started

- [Protect your service with Otoroshi ApiKey](#protect-your-service-with-otoroshi-apikey)
- [Secure your web app in 2 calls with an authentication](#secure-your-web-app-in-2-calls-with-an-authentication)

Download the latest jar of Otoroshi
```sh
curl -L -o otoroshi.jar 'https://github.com/MAIF/otoroshi/releases/download/v16.6.0/otoroshi.jar'
```

Once downloading, run Otoroshi.
```sh
java -Dotoroshi.adminPassword=password -jar otoroshi.jar 
```

Yes, that command is all it took to start it up.

## Protect your service with Otoroshi ApiKey

Create a new route, exposed on `http://myapi.oto.tools:8080`, which will forward all requests to the mirror `https://mirror.otoroshi.io`.

```sh
curl -X POST http://otoroshi-api.oto.tools:8080/api/routes \
-H "Content-type: application/json" \
-u admin-api-apikey-id:admin-api-apikey-secret \
-d @- <<'EOF'
{
  "name": "myapi",
  "frontend": {
    "domains": ["myapi.oto.tools"]
  },
  "backend": {
    "targets": [
      {
        "hostname": "mirror.otoroshi.io",
        "port": 443,
        "tls": true
      }
    ]
  },
  "plugins": [
    {
        "plugin": "cp:otoroshi.next.plugins.ApikeyCalls",
        "enabled": true,
        "config": {
            "validate": true,
            "mandatory": true,
            "update_quotas": true
        }
    }
  ]
}
EOF
```

Now that we have created our route, let’s see if our request reaches our upstream service. 
You should receive an error from Otoroshi about a missing api key in our request.

```sh
curl 'http://myapi.oto.tools:8080'
```

It looks like we don’t have access to it. Create your first api key with a quota of 10 calls by day and month.

```sh
curl -X POST 'http://otoroshi-api.oto.tools:8080/api/apikeys' \
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
curl 'http://myapi.oto.tools:8080' -u my-first-apikey-id:my-first-apikey-secret
```

```json
{
  "method": "GET",
  "path": "/",
  "headers": {
    "host": "mirror.otoroshi.io",
    "accept": "*/*",
    "user-agent": "curl/7.64.1",
    "authorization": "Basic bXktZmlyc3QtYXBpLWtleS1pZDpteS1maXJzdC1hcGkta2V5LXNlY3JldA==",
    "otoroshi-request-id": "1465298507974836306",
    "otoroshi-proxied-host": "myapi.oto.tools:8080",
    "otoroshi-request-timestamp": "2021-11-29T13:36:02.888+01:00",
  },
  "body": ""
}
```

Check your remaining quotas

```sh
curl 'http://myapi.oto.tools:8080' -u my-first-apikey-id:my-first-apikey-secret --include
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

Well done, you have secured your first api with the apikeys system with limited call quotas.

## Secure your web app in 2 calls with an authentication

Create an in-memory authentication module, with one registered user, to protect your service.

```sh
curl -X POST 'http://otoroshi-api.oto.tools:8080/api/auths' \
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

Then create a service secure by the previous authentication module, which proxies `google.fr` on `webapp.oto.tools`.

```sh
curl -X POST 'http://otoroshi-api.oto.tools:8080/api/routes' \
-H "Content-type: application/json" \
-u admin-api-apikey-id:admin-api-apikey-secret \
-d @- <<'EOF'
{
  "name": "myapi",
  "frontend": {
    "domains": ["myapi.oto.tools"]
  },
  "backend": {
    "targets": [
      {
        "hostname": "google.fr",
        "port": 443,
        "tls": true
      }
    ]
  },
  "plugins": [
    {
        "plugin": "cp:otoroshi.next.plugins.AuthModule",
        "enabled": true,
        "config": {
            "pass_with_apikey": false,
            "auth_module": null,
            "module": "auth_mod_in_memory_auth"
        }
    }
  ]
}
EOF
```

Navigate to http://webapp.oto.tools:8080, login with `user@foo.bar/password` and check that you're redirect to `google` page.

Well done! You completed the discovery tutorial.