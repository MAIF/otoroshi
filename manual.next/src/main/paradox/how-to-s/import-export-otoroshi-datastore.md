# Import and export Otoroshi datastore

### Cover by this tutorial
- [Start Otoroshi with an initial datastore](#start-otoroshi-with-an-initial-state)
- [Export the current datastore via the danger zone](#export-the-current-datastore-via-the-danger-zone)
- [Import a datastore from file via the danger zone](#import-a-datastore-from-file-via-the-danger-zone)

### Start Otoroshi with an initial datastore

Let's start by downloading the latest Otoroshi
```sh
curl -L -o otoroshi.jar 'https://github.com/MAIF/otoroshi/releases/download/v1.5.0-dev/otoroshi.jar'
```

By default, Otoroshi starts with domain `oto.tools` that targets `127.0.0.1`
```sh
sudo nano /etc/hosts

# Add this line at the bottom of your file
127.0.0.1	otoroshi.oto.tools privateapps.oto.tools otoroshi-api.oto.tools otoroshi-admin-internal-api.oto.tools localhost
```

Now you are almost ready to run Otoroshi for the first time, we want run it with an initial data.

To do that, you need to add the app.importFrom setting to the Otoroshi configuration (of $APP_IMPORT_FROM env).

It can be a file path or a URL.

The content of the initial datastore.

```json
{
  "label": "Otoroshi initial datastore",
  "admins": [],
  "simpleAdmins": [
    {
      "_loc": {
        "tenant": "default",
        "teams": [
          "default"
        ]
      },
      "username": "admin@otoroshi.io",
      "password": "$2a$10$iQRkqjKTW.5XH8ugQrnMDeUstx4KqmIeQ58dHHdW2Dv1FkyyAs4C.",
      "label": "Otoroshi Admin",
      "createdAt": 1634651307724,
      "type": "SIMPLE",
      "metadata": {},
      "tags": [],
      "rights": [
        {
          "tenant": "*:rw",
          "teams": [
            "*:rw"
          ]
        }
      ]
    }
  ],
  "serviceGroups": [
    {
      "_loc": {
        "tenant": "default",
        "teams": [
          "default"
        ]
      },
      "id": "admin-api-group",
      "name": "Otoroshi Admin Api group",
      "description": "No description",
      "tags": [],
      "metadata": {}
    },
    {
      "_loc": {
        "tenant": "default",
        "teams": [
          "default"
        ]
      },
      "id": "default",
      "name": "default-group",
      "description": "The default service group",
      "tags": [],
      "metadata": {}
    }
  ],
  "apiKeys": [
    {
      "_loc": {
        "tenant": "default",
        "teams": [
          "default"
        ]
      },
      "clientId": "admin-api-apikey-id",
      "clientSecret": "admin-api-apikey-secret",
      "clientName": "Otoroshi Backoffice ApiKey",
      "description": "The apikey use by the Otoroshi UI",
      "authorizedGroup": "admin-api-group",
      "authorizedEntities": [
        "group_admin-api-group"
      ],
      "enabled": true,
      "readOnly": false,
      "allowClientIdOnly": false,
      "throttlingQuota": 10000,
      "dailyQuota": 10000000,
      "monthlyQuota": 10000000,
      "constrainedServicesOnly": false,
      "restrictions": {
        "enabled": false,
        "allowLast": true,
        "allowed": [],
        "forbidden": [],
        "notFound": []
      },
      "rotation": {
        "enabled": false,
        "rotationEvery": 744,
        "gracePeriod": 168,
        "nextSecret": null
      },
      "validUntil": null,
      "tags": [],
      "metadata": {}
    }
  ],
  "serviceDescriptors": [
    {
      "_loc": {
        "tenant": "default",
        "teams": [
          "default"
        ]
      },
      "id": "admin-api-service",
      "groupId": "admin-api-group",
      "groups": [
        "admin-api-group"
      ],
      "name": "otoroshi-admin-api",
      "description": "",
      "env": "prod",
      "domain": "oto.tools",
      "subdomain": "otoroshi-api",
      "targetsLoadBalancing": {
        "type": "RoundRobin"
      },
      "targets": [
        {
          "host": "127.0.0.1:9999",
          "scheme": "http",
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
          "protocol": "HTTP/1.1",
          "predicate": {
            "type": "AlwaysMatch"
          },
          "ipAddress": null
        }
      ],
      "root": "/",
      "matchingRoot": null,
      "stripPath": true,
      "localHost": "127.0.0.1:9999",
      "localScheme": "http",
      "redirectToLocal": false,
      "enabled": true,
      "userFacing": false,
      "privateApp": false,
      "forceHttps": false,
      "logAnalyticsOnServer": false,
      "useAkkaHttpClient": true,
      "useNewWSClient": false,
      "tcpUdpTunneling": false,
      "detectApiKeySooner": false,
      "maintenanceMode": false,
      "buildMode": false,
      "strictlyPrivate": false,
      "enforceSecureCommunication": true,
      "sendInfoToken": true,
      "sendStateChallenge": true,
      "sendOtoroshiHeadersBack": true,
      "readOnly": false,
      "xForwardedHeaders": false,
      "overrideHost": true,
      "allowHttp10": true,
      "letsEncrypt": false,
      "secComHeaders": {
        "claimRequestName": null,
        "stateRequestName": null,
        "stateResponseName": null
      },
      "secComTtl": 30000,
      "secComVersion": 1,
      "secComInfoTokenVersion": "Legacy",
      "secComExcludedPatterns": [],
      "securityExcludedPatterns": [],
      "publicPatterns": [
        "/health",
        "/metrics"
      ],
      "privatePatterns": [],
      "additionalHeaders": {
        "Host": "otoroshi-admin-internal-api.oto.tools"
      },
      "additionalHeadersOut": {},
      "missingOnlyHeadersIn": {},
      "missingOnlyHeadersOut": {},
      "removeHeadersIn": [],
      "removeHeadersOut": [],
      "headersVerification": {},
      "matchingHeaders": {},
      "ipFiltering": {
        "whitelist": [],
        "blacklist": []
      },
      "api": {
        "exposeApi": false
      },
      "healthCheck": {
        "enabled": false,
        "url": "/"
      },
      "clientConfig": {
        "useCircuitBreaker": true,
        "retries": 1,
        "maxErrors": 20,
        "retryInitialDelay": 50,
        "backoffFactor": 2,
        "callTimeout": 30000,
        "callAndStreamTimeout": 120000,
        "connectionTimeout": 10000,
        "idleTimeout": 60000,
        "globalTimeout": 30000,
        "sampleInterval": 2000,
        "proxy": {},
        "customTimeouts": [],
        "cacheConnectionSettings": {
          "enabled": false,
          "queueSize": 2048
        }
      },
      "canary": {
        "enabled": false,
        "traffic": 0.2,
        "targets": [],
        "root": "/"
      },
      "gzip": {
        "enabled": false,
        "excludedPatterns": [],
        "whiteList": [
          "text/*",
          "application/javascript",
          "application/json"
        ],
        "blackList": [],
        "bufferSize": 8192,
        "chunkedThreshold": 102400,
        "compressionLevel": 5
      },
      "metadata": {},
      "tags": [],
      "chaosConfig": {
        "enabled": false,
        "largeRequestFaultConfig": null,
        "largeResponseFaultConfig": null,
        "latencyInjectionFaultConfig": null,
        "badResponsesFaultConfig": null
      },
      "jwtVerifier": {
        "type": "ref",
        "ids": [],
        "id": null,
        "enabled": false,
        "excludedPatterns": []
      },
      "secComSettings": {
        "type": "HSAlgoSettings",
        "size": 512,
        "secret": "${config.app.claim.sharedKey}",
        "base64": false
      },
      "secComUseSameAlgo": true,
      "secComAlgoChallengeOtoToBack": {
        "type": "HSAlgoSettings",
        "size": 512,
        "secret": "secret",
        "base64": false
      },
      "secComAlgoChallengeBackToOto": {
        "type": "HSAlgoSettings",
        "size": 512,
        "secret": "secret",
        "base64": false
      },
      "secComAlgoInfoToken": {
        "type": "HSAlgoSettings",
        "size": 512,
        "secret": "secret",
        "base64": false
      },
      "cors": {
        "enabled": false,
        "allowOrigin": "*",
        "exposeHeaders": [],
        "allowHeaders": [],
        "allowMethods": [],
        "excludedPatterns": [],
        "maxAge": null,
        "allowCredentials": true
      },
      "redirection": {
        "enabled": false,
        "code": 303,
        "to": "https://www.otoroshi.io"
      },
      "authConfigRef": null,
      "clientValidatorRef": null,
      "transformerRef": null,
      "transformerRefs": [],
      "transformerConfig": {},
      "apiKeyConstraints": {
        "basicAuth": {
          "enabled": true,
          "headerName": null,
          "queryName": null
        },
        "customHeadersAuth": {
          "enabled": true,
          "clientIdHeaderName": null,
          "clientSecretHeaderName": null
        },
        "clientIdAuth": {
          "enabled": true,
          "headerName": null,
          "queryName": null
        },
        "jwtAuth": {
          "enabled": true,
          "secretSigned": true,
          "keyPairSigned": true,
          "includeRequestAttributes": false,
          "maxJwtLifespanSecs": null,
          "headerName": null,
          "queryName": null,
          "cookieName": null
        },
        "routing": {
          "noneTagIn": [],
          "oneTagIn": [],
          "allTagsIn": [],
          "noneMetaIn": {},
          "oneMetaIn": {},
          "allMetaIn": {},
          "noneMetaKeysIn": [],
          "oneMetaKeyIn": [],
          "allMetaKeysIn": []
        }
      },
      "restrictions": {
        "enabled": false,
        "allowLast": true,
        "allowed": [],
        "forbidden": [],
        "notFound": []
      },
      "accessValidator": {
        "enabled": false,
        "refs": [],
        "config": {},
        "excludedPatterns": []
      },
      "preRouting": {
        "enabled": false,
        "refs": [],
        "config": {},
        "excludedPatterns": []
      },
      "plugins": {
        "enabled": false,
        "refs": [],
        "config": {},
        "excluded": []
      },
      "hosts": [
        "otoroshi-api.oto.tools"
      ],
      "paths": [],
      "handleLegacyDomain": true,
      "issueCert": false,
      "issueCertCA": null
    }
  ],
  "errorTemplates": [],
  "jwtVerifiers": [],
  "authConfigs": [],
  "certificates": [],
  "clientValidators": [],
  "scripts": [],
  "tcpServices": [],
  "dataExporters": [],
  "tenants": [
    {
      "id": "default",
      "name": "Default organization",
      "description": "The default organization",
      "metadata": {},
      "tags": []
    }
  ],
  "teams": [
    {
      "id": "default",
      "tenant": "default",
      "name": "Default Team",
      "description": "The default Team of the default organization",
      "metadata": {},
      "tags": []
    }
  ]
}
```

Run an Otoroshi with the previous file as parameter.

```sh
java \
-Dapp.adminPassword=password \
-Dhttp.port=9999 \
-Dhttps.port=9998 \
-Dapp.importFrom=./initial-state.json \
-jar otoroshi.jar 
```

This should display

```sh
...
[info] otoroshi-env - Importing from: ./initial-state.json
[info] otoroshi-env - Successful import !
...
[info] p.c.s.AkkaHttpServer - Listening for HTTP on /0:0:0:0:0:0:0:0:9999
[info] p.c.s.AkkaHttpServer - Listening for HTTPS on /0:0:0:0:0:0:0:0:9998
...
```

> Warning : when you using Otoroshi with a datastore different from file or in-memory, Otoroshi doesn't will reload the initialization script. If you expected it, you need to clean manually your store.

### Export the current datastore via the danger zone

When Otoroshi is running, you can backup the global configuration store from the UI. Navigate to your instance (in our case *http://otoroshi.oto.tools:9999/bo/dashboard/dangerzone*) and scroll to the bottom page. 

Click on `Full export` button to download the full global configuration.

### Import a datastore from file via the danger zone

When Otoroshi is running, you can recover a global configuration from the UI. Navigate to your instance (in our case *http://otoroshi.oto.tools:9999/bo/dashboard/dangerzone*) and scroll to the bottom page. 

Click on `Recover from a full export file` button to apply all configurations from a file.
