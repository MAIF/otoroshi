const express = require('express');
const fetch = require('node-fetch');
const app = express();
const port = process.env.PORT || 5432;

const otoPort = 8080;

function generateService(name, isSelf, overridePublic) {
  return {
    "id": `descriptor-${name}`,
    "groupId": "default",
    "name": name,
    "env": "prod",
    "domain": "foo.bar",
    "subdomain": name,
    "targets": [
      {
        "host" : `otoroshi-${name}:8080`,
        "scheme": "http"
      }
    ],
    "root": "/",
    "matchingRoot": null,
    "localHost": `${name}:5432`,
    "localScheme": "http",
    "redirectToLocal": isSelf,
    "enabled": true,
    "privateApp": false,
    "forceHttps": false,
    "maintenanceMode": false,
    "buildMode": false,
    "enforceSecureCommunication": false,
    "sendOtoroshiHeadersBack": true,
    "secComExcludedPatterns": [],
    "publicPatterns": overridePublic ? ['/.*'] : (!isSelf ? ['/.*'] : []), // local service does not have to provide an apikey
    "privatePatterns": [],
    "additionalHeaders": {
      "Host": `${name}.foo.bar`,
      "Otoroshi-Client-Id": "service-frontend-apikey",
      "Otoroshi-Client-Secret": "service-frontend-apikey",
    },
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
      "retries": 3,
      "maxErrors": 20,
      "retryInitialDelay": 50,
      "backoffFactor": 2,
      "callTimeout": 30000,
      "globalTimeout": 30000,
      "sampleInterval": 2000
    },
    "canary": {
      "enabled": false,
      "traffic": 0.2,
      "targets": [],
      "root": "/"
    },
    "metadata": {}
  };
};

app.get('/*', (req, res) => {
  const bigConfig = {
    "config": {
      "lines": [
        "prod"
      ],
      "streamEntityOnly": true,
      "autoLinkToDefaultGroup": true,
      "limitConcurrentRequests": false,
      "maxConcurrentRequests": 100000,
      "maxHttp10ResponseSize": 4194304,
      "useCircuitBreakers": true,
      "apiReadOnly": false,
      "u2fLoginOnly": false,
      "ipFiltering": {
        "whitelist": [],
        "blacklist": []
      },
      "throttlingQuota": 100000,
      "perIpThrottlingQuota": 100000,
      "analyticsEventsUrl": null,
      "analyticsWebhooks": [],
      "alertsWebhooks": [],
      "alertsEmails": [],
      "endlessIpAddresses": [],
      "statsdConfig": null,
      "kafkaConfig": null,
      "backofficeAuth0Config": null,
      "privateAppsAuth0Config": null,
      "mailGunSettings": null,
      "cleverSettings": null,
      "maxWebhookSize": 100,
      "middleFingers": false,
      "maxLogsSize": 10000,
      "otoroshiId": "e67831243-9ca0-45d5-998b-e7649977d547"
    },
    "admins": [],
    "simpleAdmins": [
      {
        "username": "admin@otoroshi.io",
        "password": "$2a$10$7C6dGG1uHAjetnaMGrtgrewvLQUsNf3XdNWWwgpPC0sFRTTiMwnui",
        "label": "Otoroshi Admin",
        "authorizedGroup": null,
        "createdAt": 1527155236862
      }
    ],
    "serviceGroups": [
      {
        "id": "admin-api-group",
        "name": "Otoroshi Admin Api group",
        "description": "No description"
      },
      {
        "id": "default",
        "name": "default-group",
        "description": "The default service group"
      }
    ],
    "apiKeys": [
      {
        "clientId": "admin-api-apikey-id",
        "clientSecret": "admin-api-apikey-secret",
        "clientName": "Otoroshi Backoffice ApiKey",
        "authorizedGroup": "admin-api-group",
        "enabled": true,
        "throttlingQuota": 10000000,
        "dailyQuota": 10000000,
        "monthlyQuota": 10000000,
        "metadata": {}
      },
      {
        "clientId": "service-frontend-apikey",
        "clientSecret": "service-frontend-apikey",
        "clientName": "service-frontend-apikey",
        "authorizedGroup": "default",
        "enabled": true,
        "throttlingQuota": 10000000,
        "dailyQuota": 10000000,
        "monthlyQuota": 10000000,
        "metadata": {}
      }
    ],
    "serviceDescriptors": [
      {
        "id": "admin-api-service",
        "groupId": "admin-api-group",
        "name": "otoroshi-admin-api",
        "env": "prod",
        "domain": "foo.bar",
        "subdomain": "otoroshi-api",
        "targets": [
          {
            "host": `127.0.0.1:${otoPort}`,
            "scheme": "http"
          }
        ],
        "root": "/",
        "matchingRoot": null,
        "localHost": `127.0.0.1:${otoPort}`,
        "localScheme": "http",
        "redirectToLocal": false,
        "enabled": true,
        "privateApp": false,
        "forceHttps": false,
        "maintenanceMode": false,
        "buildMode": false,
        "enforceSecureCommunication": true,
        "sendOtoroshiHeadersBack": true,
        "secComExcludedPatterns": [],
        "publicPatterns": [],
        "privatePatterns": [],
        "additionalHeaders": {
          "Host": "otoroshi-admin-internal-api.foo.bar"
        },
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
          "globalTimeout": 30000,
          "sampleInterval": 2000
        },
        "canary": {
          "enabled": false,
          "traffic": 0.2,
          "targets": [],
          "root": "/"
        },
        "metadata": {}
      }
    ],
    "errorTemplates": []
  };
  const from = req.query.from;

  if (from === 'otoroshi-gateway') {
    const config = { ...bigConfig };
    const serviceFront = generateService("service-frontend", false, true);
    config.serviceDescriptors.push(serviceFront);
    console.log(`serving configuration for ${from}`)
    res.status(200).send(config);
  } else {
    const config = { ...bigConfig };
    const service1 = generateService("service-1", from === "service-1", false, false);
    const service2 = generateService("service-2", from === "service-2", false, false);
    const service3 = generateService("service-3", from === "service-3", false, false);
    const serviceFront = generateService("service-frontend", from === "service-frontend", false, false);
    config.serviceDescriptors.push(service1);
    config.serviceDescriptors.push(service2);
    config.serviceDescriptors.push(service3);
    config.serviceDescriptors.push(serviceFront);
    console.log(`serving configuration for ${from}`)
    res.status(200).send(config);
  }
});

app.listen(port, () => {
  console.log(`config-server listening on port ${port}!`);
});