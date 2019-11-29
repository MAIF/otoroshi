const fs = require('fs');
const express = require('express');
const jwt = require('jsonwebtoken');
const bodyParser = require('body-parser');
const fetch = require('node-fetch');
const moment = require('moment');
const uuid = require('uuid/v4');
const base64 = require('base-64');
const _ = require('lodash');

const DEBUG = !!process.env.DEBUG || false;
const PORT = process.env.PORT || 9000;
const CONFIG_PATH = process.env.CONFIG_PATH || './config.json';
const CONFIG = JSON.parse(fs.readFileSync(CONFIG_PATH, 'utf8'));

const ADDITIONAL_HEADERS = CONFIG.otoroshi.additionalHeaders;
const POLL_INTERVAL = CONFIG.pollInterval;
const PUBLIC_SERVICES = CONFIG.otoroshi.publicServices;
const SERVICES_DOMAIN = CONFIG.otoroshi.servicesDomain;
const FORCE_HTTPS = CONFIG.otoroshi.forceHttps;
const ENFORCE_SECURE_COMMUNICATIONS = CONFIG.otoroshi.enforceSecureCommunication;
const OTOROSHI_URL = CONFIG.otoroshi.url;
const OTOROSHI_HOST = CONFIG.otoroshi.host;
const OTOROSHI_CLIENT_ID = CONFIG.otoroshi.clientId;
const OTOROSHI_CLIENT_SECRET = CONFIG.otoroshi.clientSecret;
const OTOROSHI_CLIENT_ID_HEADER = CONFIG.otoroshi.clientIdHeader;
const OTOROSHI_CLIENT_SECRET_HEADER = CONFIG.otoroshi.clientSecretHeader;
const RANCHER_URL = CONFIG.rancher.url;
const RANCHER_PROJECT = CONFIG.rancher.project;
const RANCHER_CLIENT_ID = CONFIG.rancher.clientId;
const RANCHER_CLIENT_SECRET = CONFIG.rancher.clientSecret;
const RANCHER_GROUP = CONFIG.rancher.group;
const RANCHER_API_KEY = CONFIG.rancher.apiKey;
const RANCHER_API_KEY_ID = CONFIG.rancher.apiKeyId;
const RANCHER_API_KEY_SECRET = CONFIG.rancher.apiKeySecret;

let syncing = false;
let lastSync = moment();
let lastDuration = moment();
let currentServices = 0;
let lastDeletedServices = 0;

function log(...args) {
  console.log(`[INFO] ${moment().format('YYYY-MM-DD HH:mm:ss:SSS')} - ${args.join(' ')}`);
}

function debug(...args) {
  console.log(`[DEBUG] ${moment().format('YYYY-MM-DD HH:mm:ss:SSS')} - ${args.join(' ')}`);
}

function debugSuccess(name) {
  return data => {
    if (DEBUG) debug(`${name} - ${JSON.stringify(data, null, 2)}\n`);
    return data;
  };
}

function debugError(name) {
  return data => {
    if (DEBUG) debug(`ERR - ${name} - ${JSON.stringify(data, null, 2)}\n`);
    return data;
  };
}

function fetchOtoroshiGroups() {
  return fetch(`${OTOROSHI_URL}/api/groups`, {
    method: 'GET',
    headers: {
      Host: OTOROSHI_HOST,
      Accept: 'application/json',
      [OTOROSHI_CLIENT_ID_HEADER]: OTOROSHI_CLIENT_ID,
      [OTOROSHI_CLIENT_SECRET_HEADER]: OTOROSHI_CLIENT_SECRET,
    },
  })
    .then(r => r.json(), debugError('fetchOtoroshiGroups'))
    .then(debugSuccess('fetchOtoroshiGroups'), debugError('fetchOtoroshiGroups'));
}

function createOtoroshiRancherGroup() {
  return fetch(`${OTOROSHI_URL}/api/groups`, {
    method: 'POST',
    headers: {
      Host: OTOROSHI_HOST,
      Accept: 'application/json',
      'Content-Type': 'application/json',
      [OTOROSHI_CLIENT_ID_HEADER]: OTOROSHI_CLIENT_ID,
      [OTOROSHI_CLIENT_SECRET_HEADER]: OTOROSHI_CLIENT_SECRET,
    },
    body: JSON.stringify({
      id: RANCHER_GROUP,
      name: RANCHER_GROUP,
      description: 'Group for Rancher services',
    }),
  })
    .then(r => r.json(), debugError('createOtoroshiRancherGroup'))
    .then(debugSuccess('createOtoroshiRancherGroup'), debugError('createOtoroshiRancherGroup'));
}

function fetchOtoroshiRancherServices() {
  return fetch(`${OTOROSHI_URL}/api/services`, {
    method: 'GET',
    headers: {
      Host: OTOROSHI_HOST,
      Accept: 'application/json',
      [OTOROSHI_CLIENT_ID_HEADER]: OTOROSHI_CLIENT_ID,
      [OTOROSHI_CLIENT_SECRET_HEADER]: OTOROSHI_CLIENT_SECRET,
    },
  })
    .then(r => r.json(), debugError('fetchOtoroshiRancherServices'))
    .then(services => {
      return services.filter(s => s.metadata.provider && s.metadata.provider === 'rancher');
    }, debugError('fetchOtoroshiRancherServices'))
    .then(debugSuccess('fetchOtoroshiRancherServices'), debugError('fetchOtoroshiRancherServices'));
}

function fetchOtoroshiApiKeys() {
  return fetch(`${OTOROSHI_URL}/api/groups/${RANCHER_GROUP}/apikeys`, {
    method: 'GET',
    headers: {
      Host: OTOROSHI_HOST,
      Accept: 'application/json',
      [OTOROSHI_CLIENT_ID_HEADER]: OTOROSHI_CLIENT_ID,
      [OTOROSHI_CLIENT_SECRET_HEADER]: OTOROSHI_CLIENT_SECRET,
    },
  })
    .then(r => r.json(), debugError('fetchOtoroshiApiKeys'))
    .then(debugSuccess('fetchOtoroshiApiKeys'), debugError('fetchOtoroshiApiKeys'));
}

function createOtoroshiRancherApiKey() {
  return fetch(`${OTOROSHI_URL}/api/groups/${RANCHER_GROUP}/apikeys`, {
    method: 'POST',
    headers: {
      Host: OTOROSHI_HOST,
      Accept: 'application/json',
      'Content-Type': 'application/json',
      [OTOROSHI_CLIENT_ID_HEADER]: OTOROSHI_CLIENT_ID,
      [OTOROSHI_CLIENT_SECRET_HEADER]: OTOROSHI_CLIENT_SECRET,
    },
    body: JSON.stringify({
      clientId: RANCHER_API_KEY_ID,
      clientSecret: RANCHER_API_KEY_SECRET,
      clientName: RANCHER_API_KEY,
      authorizedGroup: RANCHER_GROUP,
    }),
  })
    .then(r => r.json(), debugError('createOtoroshiRancherApiKey'))
    .then(debugSuccess('createOtoroshiRancherApiKey'), debugError('createOtoroshiRancherApiKey'));
}

function createOtoroshiRancherService(id, name, targets) {
  const serviceTemplate = {
    id: `rancher-service-${id}`,
    groupId: RANCHER_GROUP,
    name: 'rancher-' + name,
    env: 'prod',
    domain: SERVICES_DOMAIN,
    subdomain: name,
    targets: targets,
    root: '/',
    matchingRoot: null,
    redirectToLocal: false,
    enabled: true,
    privateApp: false,
    forceHttps: FORCE_HTTPS,
    maintenanceMode: false,
    buildMode: false,
    enforceSecureCommunication: ENFORCE_SECURE_COMMUNICATIONS,
    publicPatterns: PUBLIC_SERVICES ? ['/.*'] : [],
    privatePatterns: [],
    additionalHeaders: ADDITIONAL_HEADERS,
    matchingHeaders: {},
    ipFiltering: { whitelist: [], blacklist: [] },
    api: { exposeApi: false },
    healthCheck: { enabled: false, url: '/' },
    clientConfig: {
      useCircuitBreaker: true,
      retries: 1,
      maxErrors: 20,
      retryInitialDelay: 50,
      backoffFactor: 2,
      callTimeout: 30000,
      globalTimeout: 30000,
      sampleInterval: 2000,
    },
    metadata: {
      provider: 'rancher',
      rancherId: id,
    },
  };
  return fetch(`${OTOROSHI_URL}/api/services`, {
    method: 'POST',
    headers: {
      Host: OTOROSHI_HOST,
      Accept: 'application/json',
      'Content-Type': 'application/json',
      [OTOROSHI_CLIENT_ID_HEADER]: OTOROSHI_CLIENT_ID,
      [OTOROSHI_CLIENT_SECRET_HEADER]: OTOROSHI_CLIENT_SECRET,
    },
    body: JSON.stringify(serviceTemplate),
  })
    .then(r => r.json(), debugError('createOtoroshiRancherService'))
    .then(debugSuccess('createOtoroshiRancherService'), debugError('createOtoroshiRancherService'));
}

function updateOtoroshiRancherService(id, name, targets) {
  return fetch(`${OTOROSHI_URL}/api/services/rancher-service-${id}`, {
    method: 'PATCH',
    headers: {
      Host: OTOROSHI_HOST,
      Accept: 'application/json',
      'Content-Type': 'application/json',
      [OTOROSHI_CLIENT_ID_HEADER]: OTOROSHI_CLIENT_ID,
      [OTOROSHI_CLIENT_SECRET_HEADER]: OTOROSHI_CLIENT_SECRET,
    },
    body: JSON.stringify([
      { op: 'replace', path: '/targets', value: targets },
      { op: 'replace', path: '/name', value: name },
    ]),
  })
    .then(r => r.json(), debugError('updateOtoroshiRancherService'))
    .then(debugSuccess('updateOtoroshiRancherService'), debugError('updateOtoroshiRancherService'));
}

function deleteOtoroshiRancherService(id) {
  return fetch(`${OTOROSHI_URL}/api/services/rancher-service-${id}`, {
    method: 'DELETE',
    headers: {
      Host: OTOROSHI_HOST,
      Accept: 'application/json',
      [OTOROSHI_CLIENT_ID_HEADER]: OTOROSHI_CLIENT_ID,
      [OTOROSHI_CLIENT_SECRET_HEADER]: OTOROSHI_CLIENT_SECRET,
    },
  })
    .then(r => (r.status === 404 ? null : r.json()), debugError('deleteOtoroshiRancherService'))
    .then(debugSuccess('deleteOtoroshiRancherService'), debugError('deleteOtoroshiRancherService'));
}

function deleteRancherService(id) {
  return fetch(`${OTOROSHI_URL}/api/services/${id}`, {
    method: 'DELETE',
    headers: {
      Host: OTOROSHI_HOST,
      Accept: 'application/json',
      [OTOROSHI_CLIENT_ID_HEADER]: OTOROSHI_CLIENT_ID,
      [OTOROSHI_CLIENT_SECRET_HEADER]: OTOROSHI_CLIENT_SECRET,
    },
  })
    .then(r => (r.status === 404 ? null : r.json()), debugError('deleteRancherService'))
    .then(debugSuccess('deleteRancherService'), debugError('deleteRancherService'));
}

function fetchOtoroshiRancherService(id) {
  return fetch(`${OTOROSHI_URL}/api/services/rancher-service-${id}`, {
    method: 'GET',
    headers: {
      Host: OTOROSHI_HOST,
      Accept: 'application/json',
      [OTOROSHI_CLIENT_ID_HEADER]: OTOROSHI_CLIENT_ID,
      [OTOROSHI_CLIENT_SECRET_HEADER]: OTOROSHI_CLIENT_SECRET,
    },
  })
    .then(r => (r.status === 404 ? null : r.json()), debugError('fetchOtoroshiRancherService'))
    .then(debugSuccess('fetchOtoroshiRancherService'), debugError('fetchOtoroshiRancherService'));
}

///////////////////////////////////////////////////////////////////////////////////////////////////
///////////////////////////////////////////////////////////////////////////////////////////////////
///////////////////////////////////////////////////////////////////////////////////////////////////

function fetchRancherServices() {
  const user = base64.encode(`${RANCHER_CLIENT_ID}:${RANCHER_CLIENT_SECRET}`);
  return fetch(`${RANCHER_URL}/v2-beta/projects/${RANCHER_PROJECT}/services`, {
    method: 'GET',
    headers: {
      Accept: 'application/json',
      Authorization: `Basic ${user}`,
    },
  })
    .then(r => r.json(), debugError('fetchRancherServices'))
    .then(rancherServices => {
      return rancherServices.data.filter(
        s =>
          s.state === 'active' &&
          s.launchConfig.labels['service-type'] &&
          s.launchConfig.labels['service-type'] === 'otoroshi-capable'
      );
    }, debugError('fetchRancherServices'))
    .then(debugSuccess('fetchRancherServices'), debugError('fetchRancherServices'));
}

///////////////////////////////////////////////////////////////////////////////////////////////////
///////////////////////////////////////////////////////////////////////////////////////////////////
///////////////////////////////////////////////////////////////////////////////////////////////////

function setupOtoroshi() {
  log('Checking Otoroshi setup ...');
  return fetchOtoroshiGroups()
    .then(groups => {
      const rancherGroup = groups.filter(g => g.name === RANCHER_GROUP)[0];
      if (rancherGroup) {
        log('  Rancher group already exists ...');
        return rancherGroup;
      } else {
        log('  Create Rancher group in Otoroshi');
        return createOtoroshiRancherGroup();
      }
    })
    .then(group => {
      return fetchOtoroshiApiKeys(RANCHER_GROUP).then(apikeys => {
        const rancherApiKey = apikeys.filter(g => g.clientName === RANCHER_API_KEY)[0];
        if (rancherApiKey) {
          log('  Rancher api key already exists ...');
          return rancherApiKey;
        } else {
          log('  Create Rancher api key in Otoroshi');
          return createOtoroshiRancherApiKey();
        }
      });
    })
    .then(() => log('Otorshi setup done !\n'));
}

function syncOtoroshiWithRancher() {
  // log('Synchronizing Otoroshi with Rancher ...');
  syncing = true;
  const start = Date.now();
  fetchRancherServices()
    .then(rs => {
      return fetchOtoroshiRancherServices().then(os => [rs, os]);
    })
    .then(arr => {
      const [rancherServices, otoroshiServices] = arr;
      currentServices = rancherServices.length;
      //log('  Synchronizing Rancher => Otoroshi');
      const tasks = rancherServices.map(rancherService => {
        const otoroshiService = otoroshiServices.filter(
          s => s.metadata.rancherId && s.metadata.rancherId === rancherService.id
        )[0];
        const targets = rancherService.publicEndpoints.map(ep => ({
          scheme: 'http',
          host: `${ep.ipAddress}:${ep.port}`,
        }));
        const targetHosts = _.sortBy(targets.map(i => i.host), i => i);
        const otoHosts = otoroshiService
          ? _.sortBy(otoroshiService.targets.map(i => i.host), i => i)
          : [];
        const rootPath = rancherService.name.split('-')[1];
        if (!otoroshiService) {
          log(
            `Creating Otoroshi service '${rancherService.name}' with id: '${rancherService.id}' (${targets.length} targets)`
          );
          return createOtoroshiRancherService(rancherService.id, rancherService.name, targets);
        } else if (otoroshiService && !_.isEqual(targetHosts, otoHosts)) {
          log(
            `Updating Otoroshi service '${rancherService.name}' with id: '${rancherService.id}' (${targets.length} targets)`
          );
          return updateOtoroshiRancherService(rancherService.id, rancherService.name, targets);
        } else {
          return new Promise(s => s());
        }
      });
      return Promise.all(tasks).then(() => [rancherServices, otoroshiServices]);
    })
    .then(arr => {
      return fetchOtoroshiRancherServices().then(os => [arr[0], os]);
    })
    .then(arr => {
      //log('  Synchronizing Otoroshi => Rancher');
      const [rancherServices, otoroshiServices] = arr;
      const tasks = otoroshiServices.map(otoroshiService => {
        const rancherService = rancherServices.filter(
          s => s.id && s.id === otoroshiService.metadata.rancherId
        )[0];
        if (!rancherService) {
          log(`Deleting Otoroshi service ${otoroshiService.name} with id: ${otoroshiService.id}`);
          return deleteRancherService(otoroshiService.id);
        } else {
          return null;
        }
      });
      lastDeletedServices = tasks.filter(i => !!i).length;
      return Promise.all(tasks);
    })
    .then(() => {
      syncing = false;
      lastSync = moment();
      lastDuration = Date.now() - start;
      //log(`Synchronization done in ${Date.now() - start} millis !\n\n==========================\n`);
      setTimeout(syncOtoroshiWithRancher, POLL_INTERVAL);
    });
}

const app = express();

app.get('/', (req, res) => {
  res.status(200).type('text/html').send(`<html>
  <head>
    <title>Otoroshi - Rancher Connector - Status</title>
  </head>
  <body style="display: flex; justify-content: center; align-items: center;flex-direction: column">
    <h1>Otoroshi - Rancher Connector - Status</h1>
    <ul>
      <li>status: <span style="color: ${syncing ? 'green' : 'lightgreen'}">${
    syncing ? 'SYNCING' : 'SLEEPING'
  }</span></li>
      <li>current services: ${currentServices}</li>
      <li>last deleted services: ${lastDeletedServices}</li>
      <li>last sync: ${lastSync.format('DD/MM/YYYY HH:mm:ss')}</li>
      <li>last duration: ${lastDuration} ms.</li>
    </ul>
    <script>
      setTimeout(function() {
        window.location.reload();
      }, 30000);
    </script>
  </body>
</html>`);
});

app.get('/status', (req, res) => {
  res
    .status(200)
    .type('application/json')
    .send({
      status: syncing ? 'SYNCING' : 'SLEEPING',
      lastSync: lastSync.format(),
      lastDuration: lastDuration,
      lastDeletedServices: lastDeletedServices,
      currentServices: currentServices,
    });
});

app.get('/health', (req, res) => {
  res
    .status(200)
    .type('application/json')
    .send({
      status: 'RUNNING',
    });
});

app.listen(PORT, () => {
  console.log('\n# Welcome to the Otoroshi Rancher synchronizer daemon');
  console.log(`# The daemon will synchronize every ${POLL_INTERVAL} millis.`);
  console.log(`# The daemon status is available at http://127.0.0.1:${PORT}/status\n`);
  //log('==========================\n');
  setupOtoroshi().then(() => {
    //log('==========================\n');
    setTimeout(syncOtoroshiWithRancher, 500);
  });
});
