---
title: Initialize Otoroshi
---

:::tip Prerequisites
If you already have an up and running Otoroshi instance, you can skip the following instructions.
:::

Let's start by downloading the latest Otoroshi.

```sh
curl -L -o otoroshi.jar 'https://github.com/MAIF/otoroshi/releases/download/v17.14.0-dev/otoroshi.jar'
```

then you can start Otoroshi:

```sh
java -Dotoroshi.adminPassword=password -jar otoroshi.jar
```

Now you can log into Otoroshi at [http://otoroshi.oto.tools:8080](http://otoroshi.oto.tools:8080) with `admin@otoroshi.io/password`

Create a new route, exposed on `http://myservice.oto.tools:8080`, which will forward all requests to the mirror `https://request.otoroshi.io`. Each call to this service will return the body and the headers received by the mirror.

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
