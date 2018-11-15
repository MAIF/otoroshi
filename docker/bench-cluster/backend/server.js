const { fork, exec } = require('child_process');
const express = require('express');
const _ = require('lodash');
const argv = require('minimist')(process.argv.slice(2));
const from = argv.fromPort;
const to = argv.toPort + 1;
const processes = [];
const otoPort = argv.otoPort || 8091;
const pass = "UdjDabLqV0JcHEaZQYaPjevjAbNUEBqh";
const a = {
  "id": "lb-test",
  "groupId": "default",
  "name": "lb-test",
  "env": "prod",
  "domain": "foo.bar",
  "subdomain": "test",
  "targets": _.range(from, to).map(port => {
    return {
      "host" : `backend:${port}`,
      "scheme": "http"
    };
  }),
  "root": "/",
  "matchingRoot": null,
  "localHost": `backend:${otoPort}`,
  "localScheme": "http",
  "redirectToLocal": false,
  "enabled": true,
  "privateApp": false,
  "forceHttps": false,
  "maintenanceMode": false,
  "buildMode": false,
  "enforceSecureCommunication": false,
  "sendOtoroshiHeadersBack": true,
  "secComExcludedPatterns": [],
  "publicPatterns": ['/.*'],
  "privatePatterns": [],
  "additionalHeaders": {},
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
 
_.range(from, to).forEach((port, idx) => {
  const cp = fork('fork.js', [`--port=${port}`, `--idx=${idx}`]);
  processes.push(cp);
});

const configPort = argv.port;
const configApp = express();
configApp.get('/otoroshi.json', (req, res) => {
  console.log('Serving test config');
  res.status(200).send({
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
      "elasticWritesConfigs": [
        {
          "clusterUri": "http://elastic:9200",
          "index": "otoroshi-events",
          "type": "event",
          "user": null,
          "password": null
        }
      ],
      "elasticReadsConfig": {
        "clusterUri": "http://elastic:9200",
        "index": "otoroshi-events",
        "type": "event",
        "user": null,
        "password": null
      },
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
        "clientId": "9HFCzZIPUQQvfxkq",
        "clientSecret": "lmwAGwqtJJM7nOMGKwSAdOjC3CZExfYC7qXd4aPmmseaShkEccAnmpULvgnrt6tp",
        "clientName": "default-apikey",
        "authorizedGroup": "default",
        "enabled": true,
        "throttlingQuota": 10000000,
        "dailyQuota": 10000000,
        "monthlyQuota": 10000000,
        "metadata": {}
      }
    ],
    "serviceDescriptors": [
      a,
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
  });
});

configApp.listen(configPort, () => {
  console.log(`Config server listening on port ${configPort}!`);
});

function exitHandler(options, err) {
  processes.forEach(a => a.kill());
  if (err) console.log(err.stack);
  if (options.exit) process.exit();
}

process.on('exit', exitHandler.bind(null,{cleanup:true}));
process.on('SIGINT', exitHandler.bind(null, {exit:true}));
process.on('SIGUSR1', exitHandler.bind(null, {exit:true}));
process.on('SIGUSR2', exitHandler.bind(null, {exit:true}));
process.on('uncaughtException', exitHandler.bind(null, {exit:true}));
