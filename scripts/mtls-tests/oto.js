const fs = require('fs');
const fetch = require('node-fetch');

const authToken = Buffer.from(`admin-api-apikey-id:admin-api-apikey-secret`).toString('base64');

const certFront = fs.readFileSync('./cert-frontend.pem').toString('utf8');
const certFrontKey = fs.readFileSync('./cert-frontend-key.pem').toString('utf8');
const certBack = fs.readFileSync('./cert-backend.pem').toString('utf8');
const certBackKey = fs.readFileSync('./cert-backend-key.pem').toString('utf8');

const apikey = {
  "clientId": "clientId",
  "clientSecret": "clientSecret",
  "clientName": "apikey",
  "authorizedEntities": ["group_default"],
  "enabled": true,
  "readOnly": false,
  "allowClientIdOnly": false,
  "throttlingQuota": 100,
  "dailyQuota": 1000000,
  "monthlyQuota": 1000000000000000000,
  "constrainedServicesOnly": false,
  "restrictions": {
    "enabled": false,
    "allowLast": true,
    "allowed": [],
    "forbidden": [],
    "notFound": []
  },
  "validUntil": null,
  "tags": [],
  "metadata": {}
};

const service = {
  "id": "service",
  "groupId": "default",
  "name": "mtls",
  "env": "prod",
  "domain": "oto.tools",
  "subdomain": "mtls",
  "targetsLoadBalancing": {
    "type": "RoundRobin"
  },
  "targets": [
    {
      "host": "localhost:18445",
      "scheme": "https",
      "weight": 1,
      "protocol": "HTTP/1.1",
      "predicate": {
        "type": "AlwaysMatch"
      },
      "ipAddress": null,
      "mtls": true,
      "certId": "otoCertBack"
    }
  ],
  "root": "/hello",
  "matchingRoot": null,
  "localHost": "localhost:18080",
  "localScheme": "http",
  "redirectToLocal": false,
  "enabled": true,
  "userFacing": false,
  "privateApp": false,
  "forceHttps": false,
  "logAnalyticsOnServer": false,
  "useAkkaHttpClient": true,
  "tcpTunneling": false,
  "detectApiKeySooner": false,
  "maintenanceMode": false,
  "buildMode": false,
  "strictlyPrivate": false,
  "enforceSecureCommunication": false,
  "sendInfoToken": true,
  "sendStateChallenge": true,
  "sendOtoroshiHeadersBack": false,
  "readOnly": false,
  "xForwardedHeaders": false,
  "overrideHost": true,
  "allowHttp10": true,
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
  "publicPatterns": [],
  "privatePatterns": [],
  "additionalHeaders": {},
  "additionalHeadersOut": {},
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
    "customTimeouts": []
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
  "chaosConfig": {
    "enabled": false,
    "largeRequestFaultConfig": {
      "ratio": 0.2,
      "additionalRequestSize": 0
    },
    "largeResponseFaultConfig": {
      "ratio": 0.2,
      "additionalResponseSize": 0
    },
    "latencyInjectionFaultConfig": {
      "ratio": 0.2,
      "from": 0,
      "to": 0
    },
    "badResponsesFaultConfig": {
      "ratio": 0.2,
      "responses": []
    }
  },
  "jwtVerifier": {
    "type": "ref",
    "id": null,
    "enabled": false,
    "excludedPatterns": []
  },
  "secComSettings": {
    "type": "HSAlgoSettings",
    "size": 512,
    "secret": "${config.app.claim.sharedKey}"
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
  "transformerConfig": {},
  "thirdPartyApiKey": {
    "enabled": false,
    "quotasEnabled": true,
    "uniqueApiKey": false,
    "type": "OIDC",
    "oidcConfigRef": null,
    "localVerificationOnly": false,
    "mode": "Tmp",
    "ttl": 0,
    "headerName": "Authorization",
    "throttlingQuota": 100,
    "dailyQuota": 10000000,
    "monthlyQuota": 10000000,
    "excludedPatterns": [],
    "scopes": [],
    "rolesPath": [],
    "roles": []
  },
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
      "allMetaIn": {}
    }
  },
  "restrictions": {
    "enabled": false,
    "allowLast": true,
    "allowed": [],
    "forbidden": [],
    "notFound": []
  }
};

const otoCertFront = {
  "id": "otoCertFront",
  "domain": "mtl.oto.tools",
  "chain": certFront,
  "caRef": null,
  "privateKey": certFrontKey,
  "selfSigned": true,
  "ca": false,
  "valid": true,
  "autoRenew": false,
  "subject": "CN=mtl.oto.tools",
  "from": 1569941242000,
  "to": 1885301242000
};

const otoCertBack = {
  "id": "otoCertBack",
  "domain": "localhost",
  "chain": certBack,
  "caRef": null,
  "privateKey": certBackKey,
  "selfSigned": true,
  "ca": false,
  "valid": true,
  "autoRenew": false,
  "subject": "CN=localhost",
  "from": 1569941242000,
  "to": 1885301242000
};

fetch('http://otoroshi-api.oto.tools:18080/api/certificates', {
  method: 'GET',
  headers: {
    'Accept': 'application/json',
    'Authorization': `Basic ${authToken}`
  }
}).then(r => r.json()).then(certs => {
  return Promise.all(certs.map(cert => {
    //console.log(cert)
    return fetch(`http://otoroshi-api.oto.tools:18080/api/certificates/${cert.id}`, {
      method: 'DELETE',
      headers: {
        'Accept': 'application/json',
        'Authorization': `Basic ${authToken}`
      }
    }).then(r => {
      // console.log('delete ', cert.domain, ':', r.status)
    });
  })).then(() => {
    return fetch(`http://otoroshi-api.oto.tools:18080/api/certificates`, {
      method: 'POST',
      headers: {
        'Accept': 'application/json',
        'Content-Type': 'application/json',
        'Authorization': `Basic ${authToken}`
      },
      body: JSON.stringify(otoCertFront)
    }).then(r => r.json()).then(() => {
      return fetch(`http://otoroshi-api.oto.tools:18080/api/certificates`, {
        method: 'POST',
        headers: {
          'Accept': 'application/json',
          'Content-Type': 'application/json',
          'Authorization': `Basic ${authToken}`
        },
        body: JSON.stringify(otoCertBack)
      }).then(r => r.json()).then(() => {
        return fetch(`http://otoroshi-api.oto.tools:18080/api/services`, {
          method: 'POST',
          headers: {
            'Accept': 'application/json',
            'Content-Type': 'application/json',
            'Authorization': `Basic ${authToken}`
          },
          body: JSON.stringify(service)
        }).then(r => r.json()).then(() => {
          return fetch(`http://otoroshi-api.oto.tools:18080/api/groups/default/apikeys`, {
            method: 'POST',
            headers: {
              'Accept': 'application/json',
              'Content-Type': 'application/json',
              'Authorization': `Basic ${authToken}`
            },
            body: JSON.stringify(apikey)
          }).then(r => r.json()).then(() => {
            return fetch('http://otoroshi-api.oto.tools:18080/api/certificates', {
              method: 'GET',
              headers: {
                'Accept': 'application/json',
                'Authorization': `Basic ${authToken}`
              }
            }).then(r => r.json()).then(finalCerts => {
              // console.log(finalCerts.map(c => c.domain).join(", "))
            });
          });          
        });
      });
    });
  });
});
