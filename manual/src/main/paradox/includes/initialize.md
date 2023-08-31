<!--- #initialize-otoroshi --->

If you already have an up and running otoroshi instance, you can skip the following instructions


@@@div { .instructions }

<div id="instructions-toggle">
<span class="instructions-title">Set up an Otoroshi</span>
<button id="instructions-toggle-button">close</button>
</div>

Let's start by downloading the latest Otoroshi.

```sh
curl -L -o otoroshi.jar 'https://github.com/MAIF/otoroshi/releases/download/v16.8.0/otoroshi.jar'
```

then you can run start Otoroshi :

```sh
java -Dotoroshi.adminPassword=password -jar otoroshi.jar 
```

Now you can log into Otoroshi at http://otoroshi.oto.tools:8080 with `admin@otoroshi.io/password`

Create a new route, exposed on `http://myservice.oto.tools:8080`, which will forward all requests to the mirror `https://mirror.otoroshi.io`. Each call to this service will returned the body and the headers received by the mirror.

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
        "hostname": "mirror.otoroshi.io",
        "port": 443,
        "tls": true
      }
    ]
  }
}
EOF
```

<button id="instructions-toggle-confirm">Confirm the installation</button>
@@@
<!--- #initialize-otoroshi --->