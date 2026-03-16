---
title: Secure the communication between a backend app and Otoroshi
sidebar_position: 18
---
# Secure the communication between a backend app and Otoroshi

<div style="display: flex; align-items: center; gap: .5rem;">
<span style="font-weight: bold">Route plugins:</span>
<a class="badge" href="https://maif.github.io/otoroshi/manual/plugins/built-in-plugins.html#otoroshi.next.plugins.OtoroshiChallenge">Otoroshi challenge token</a>
</div>

<details class="foldable-block">
<summary>Set up an Otoroshi</summary>

If you already have an up and running otoroshi instance, you can skip the following instructions.

Let's start by downloading the latest Otoroshi.

```sh
curl -L -o otoroshi.jar 'https://github.com/MAIF/otoroshi/releases/download/v17.14.0-dev/otoroshi.jar'
```

then you can run start Otoroshi :

```sh
java -Dotoroshi.adminPassword=password -jar otoroshi.jar
```

Now you can log into Otoroshi at [http://otoroshi.oto.tools:8080](http://otoroshi.oto.tools:8080) with `admin@otoroshi.io/password`

Create a new route, exposed on `http://myservice.oto.tools:8080`, which will forward all requests to the mirror `https://request.otoroshi.io`. Each call to this service will returned the body and the headers received by the mirror.

```sh
curl -X POST 'http://otoroshi-api.oto.tools:8080/api/routes' \
-H "Content-type: application/json" \
-u admin-api-apikey-id:admin-api-apikey-secret \
-d @- <<'EOF'
{
  "name": "my-service",
  "frontend": {
    "domains": ["myservice.oto.tools"]
  },
  "backend": {
    "targets": [
      {
        "hostname": "request.otoroshi.io",
        "port": 443,
        "tls": true
      }
    ]
  }
}
EOF
```

</details>

Let's create a new route with the Otorochi challenge plugin enabled.

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
        "hostname": "localhost",
        "port": 8081,
        "tls": true
      }
    ]
  },
  "plugins": [
    {
      "enabled": true,
      "plugin": "cp:otoroshi.next.plugins.OtoroshiChallenge",
      "config": {
        "version": 2,
        "ttl": 30,
        "request_header_name": "Otoroshi-State",
        "response_header_name": "Otoroshi-State-Resp",
        "algo_to_backend": {
            "type": "HSAlgoSettings",
            "size": 512,
            "secret": "secret",
            "base64": false
        },
        "algo_from_backend": {
            "type": "HSAlgoSettings",
            "size": 512,
            "secret": "secret",
            "base64": false
        },
        "state_resp_leeway": 10
      }
    }
  ]
}
EOF
```

Let's use the following application, developed in NodeJS, which supports both versions of the exchange protocol.

Clone this @link:[repository](https://github.com/MAIF/otoroshi/blob/master/demos/challenge) and run the installation of the dependencies.

```sh
git clone 'git@github.com:MAIF/otoroshi.git' --depth=1
cd ./otoroshi/demos/challenge
npm install
PORT=8081 node server.js
```

The last command should return : 

```sh
challenge-verifier listening on http://0.0.0.0:8081
```

This project runs an express client with one middleware. The middleware handles each request, and check if the header `State token header` is present in headers. By default, the incoming expected header is `Otoroshi-State` by the application and `Otoroshi-State-Resp` header in the headers of the return request. 

Try to call your service via http://myapi.oto.tools:8080/. This should return a successful response with all headers received by the backend app. 

Now try to disable the middleware in the nodejs file by commenting the following line. 

```js
// app.use(OtoroshiMiddleware());
```

Try to call again your service. This time, Otoroshi breaks the return response from your backend service, and returns.

```sh
Downstream microservice does not seems to be secured. Cancelling request !
```