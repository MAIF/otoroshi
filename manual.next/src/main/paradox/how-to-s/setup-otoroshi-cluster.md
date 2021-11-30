# Setup an Otoroshi cluster

In this tutorial, we will deploy an Otoroshi cluster with one leader and 2 workers. We will add a load balancer in front of the workers and validate the installation by adding a header on the requests.

Let's start by downloading the latest jar of Otoroshi.

```sh
curl -L -o otoroshi.jar 'https://github.com/MAIF/otoroshi/releases/download/v1.5.0-beta.8/otoroshi.jar'
```

Then create an instance of Otoroshi and indicates with the `otoroshi.cluster.mode` environment variable that it will be the leader.

```sh
java -Dhttp.port=8091 -Dhttps.port=9091 -Dotoroshi.cluster.mode=leader -jar otoroshi.jar
```

Once created, we have to create the two workers. For both workers, we have to set the ip and the url of their leader in the `otoroshi.cluster.leader.urls` environment variable.

The first worker will listen on the `:8082/:8092` ports
```sh
java 
-Dotoroshi.cluster.worker.name=worker-1 \
-Dhttp.port=8092 \
-Dhttps.port=9092 \
-Dotoroshi.cluster.mode=worker \
-Dotoroshi.cluster.leader.urls.0=http://127.0.0.1:8091 -jar otoroshi.jar
```

The second worker will listen on the `:8083/:8093` ports
```sh
java 
-Dotoroshi.cluster.worker.name=worker-2 \
-Dhttp.port=8093 \
-Dhttps.port=9093 \
-Dotoroshi.cluster.mode=worker \
-Dotoroshi.cluster.leader.urls.0=http://127.0.0.1:8091 -jar otoroshi.jar
```

Once launched, you can navigate to the @link:[cluster view](http://otoroshi.oto.tools:8091/bo/dashboard/cluster) { open=new }. If all is configured, you will see the leader, the 2 workers and a bunch of informations about each instance.


To complete our installation, we want to spread the incoming requests between our two instances of workers. 

In this tutorial, we will use NGINX to loadbalance the traffic but you can use anythig else to do it.

Create a new file, named `nginx.conf`, with the following content

```sh
events {}
http {
  upstream otoroshi {
    server host.docker.internal:8092 max_fails=1; # (1)
    server host.docker.internal:8093 max_fails=1; # (2)
  }

  server {
    listen 80;
    # http://nginx.org/en/docs/http/server_names.html
    server_name otoroshi.oto.tools otoroshi-api.oto.tools otoroshi-admin-internal-api.oto.tools privateapps.oto.tools; # (3)
    location / {
      # SSE config
      proxy_buffering off;
      proxy_cache off;
      proxy_set_header Connection '';
      proxy_http_version 1.1;
      chunked_transfer_encoding off;
    
      # websockets config
      proxy_set_header Upgrade $http_upgrade;
      proxy_set_header Connection "upgrade";
    
      # other config
      proxy_set_header Host $http_host;
      proxy_set_header X-Real-IP $remote_addr;
      proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
      proxy_set_header X-Forwarded-Proto $scheme;
      proxy_pass http://otoroshi;
    }
  }
}
```

* `(1)`and `(2)`: the list of our workers with their ports
* `(3)`: the list of domains and subdomains use by Otoroshi to expose the UI and your services

The last step is to create a service, add a rule to add, in the headers, a specific value to identify the worker used.

Create this service, exposed on `http://myapi.oto.tools:8080`, which will forward all requests to the mirror `https://mirror.otoroshi.io`.

```sh
curl -X POST http://otoroshi-api.oto.tools:8091/api/services \
-H "Content-type: application/json" \
-u admin-api-apikey-id:admin-api-apikey-secret \
-d @- <<'EOF'
{
    "enforceSecureCommunication": false,
    "forceHttps": false,
    "_loc": {
        "tenant": "default",
        "teams": [
            "default"
        ]
    },
    "groupId": "default",
    "groups": [
        "default"
    ],
    "id": "myapi",
    "name": "myapi",
    "description": "myapi",
    "env": "prod",
    "domain": "oto.tools",
    "subdomain": "api",
    "targetsLoadBalancing": {
        "type": "RoundRobin"
    },
    "targets": [
        {
            "host": "mirror.otoroshi.io",
            "scheme": "https",
            "weight": 1,
            "mtlsConfig": {
                "certs": [],
                "trustedCerts": [],
                "mtls": false,
                "loose": false,
                "trustAll": false
            },
            "tags": [],
            "metadata": {},
            "protocol": "HTTP\/1.1",
            "predicate": {
                "type": "AlwaysMatch"
            },
            "ipAddress": null
        }
    ],
    "root": "\/",
    "matchingRoot": null,
    "stripPath": true,
    "enabled": true,
    "publicPatterns": [
        "/.*"
    ],
    "kind": "ServiceDescriptor",
    "additionalHeaders": {
        "worker": "${config.otoroshi.cluster.worker.name}"
    }
}
EOF
```

Once created, call two times the service. If all is working, the header received by the downstream service will have `worker-1` and `worker-2` as value.

```sh
curl http://api.oto.tools 

## Response headers
{
    ...
    "worker-name": "worker-2"
    ...
}
```

This should output `worker-1`, then `worker-2`, etc. Well done, your loadbalancing is working and your cluster is set up correctly.


