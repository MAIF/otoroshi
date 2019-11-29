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

exports.daemon = function(creator) {
  const orchestrator = creator(config);
  const ORCHESTRATOR_NAME = orchestrator.name;
  const ORCHESTRATOR_GROUP = orchestrator.group;
  const ORCHESTRATOR_API_KEY = orchestrator.apiKey;
  const ORCHESTRATOR_API_KEY_ID = orchestrator.apiKeyId;
  const ORCHESTRATOR_API_KEY_SECRET = orchestrator.apiKeySecret;
  const fetchOrchestratorServices = orchestrator.fetchServices;

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

  function createOtoroshiGroup() {
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
        id: ORCHESTRATOR_GROUP,
        name: ORCHESTRATOR_GROUP,
        description: `Group for ${ORCHESTRATOR_NAME} services`,
      }),
    })
      .then(r => r.json(), debugError('createOtoroshiGroup'))
      .then(debugSuccess('createOtoroshiGroup'), debugError('createOtoroshiGroup'));
  }

  function fetchOtoroshiServices() {
    return fetch(`${OTOROSHI_URL}/api/services`, {
      method: 'GET',
      headers: {
        Host: OTOROSHI_HOST,
        Accept: 'application/json',
        [OTOROSHI_CLIENT_ID_HEADER]: OTOROSHI_CLIENT_ID,
        [OTOROSHI_CLIENT_SECRET_HEADER]: OTOROSHI_CLIENT_SECRET,
      },
    })
      .then(r => r.json(), debugError('fetchOtoroshiServices'))
      .then(services => {
        return services.filter(
          s => s.metadata.provider && s.metadata.provider === ORCHESTRATOR_NAME
        );
      }, debugError('fetchOtoroshiServices'))
      .then(debugSuccess('fetchOtoroshiServices'), debugError('fetchOtoroshiServices'));
  }

  function fetchOtoroshiApiKeys() {
    return fetch(`${OTOROSHI_URL}/api/groups/${ORCHESTRATOR_GROUP}/apikeys`, {
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

  function createOtoroshiApiKey() {
    return fetch(`${OTOROSHI_URL}/api/groups/${ORCHESTRATOR_GROUP}/apikeys`, {
      method: 'POST',
      headers: {
        Host: OTOROSHI_HOST,
        Accept: 'application/json',
        'Content-Type': 'application/json',
        [OTOROSHI_CLIENT_ID_HEADER]: OTOROSHI_CLIENT_ID,
        [OTOROSHI_CLIENT_SECRET_HEADER]: OTOROSHI_CLIENT_SECRET,
      },
      body: JSON.stringify({
        clientId: ORCHESTRATOR_API_KEY_ID,
        clientSecret: ORCHESTRATOR_API_KEY_SECRET,
        clientName: ORCHESTRATOR_API_KEY,
        authorizedGroup: ORCHESTRATOR_GROUP,
      }),
    })
      .then(r => r.json(), debugError('createOtoroshiApiKey'))
      .then(debugSuccess('createOtoroshiApiKey'), debugError('createOtoroshiApiKey'));
  }

  function createOtoroshiService(id, name, targets) {
    const serviceTemplate = {
      id: `${ORCHESTRATOR_NAME}-service-${id}`,
      groupId: ORCHESTRATOR_GROUP,
      name: ORCHESTRATOR_NAME + '-' + name,
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
        provider: ORCHESTRATOR_NAME,
        orchestratorId: id,
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
      .then(r => r.json(), debugError('createOtoroshiService'))
      .then(debugSuccess('createOtoroshiService'), debugError('createOtoroshiService'));
  }

  function updateOtoroshiService(id, name, targets) {
    return fetch(`${OTOROSHI_URL}/api/services/${ORCHESTRATOR_NAME}-service-${id}`, {
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
      .then(r => r.json(), debugError('updateOtoroshiService'))
      .then(debugSuccess('updateOtoroshiService'), debugError('updateOtoroshiService'));
  }

  function deleteOtoroshiService(id) {
    return fetch(`${OTOROSHI_URL}/api/services/${ORCHESTRATOR_NAME}-service-${id}`, {
      method: 'DELETE',
      headers: {
        Host: OTOROSHI_HOST,
        Accept: 'application/json',
        [OTOROSHI_CLIENT_ID_HEADER]: OTOROSHI_CLIENT_ID,
        [OTOROSHI_CLIENT_SECRET_HEADER]: OTOROSHI_CLIENT_SECRET,
      },
    })
      .then(r => (r.status === 404 ? null : r.json()), debugError('deleteOtoroshiService'))
      .then(debugSuccess('deleteOtoroshiService'), debugError('deleteOtoroshiService'));
  }

  function deleteService(id) {
    return fetch(`${OTOROSHI_URL}/api/services/${id}`, {
      method: 'DELETE',
      headers: {
        Host: OTOROSHI_HOST,
        Accept: 'application/json',
        [OTOROSHI_CLIENT_ID_HEADER]: OTOROSHI_CLIENT_ID,
        [OTOROSHI_CLIENT_SECRET_HEADER]: OTOROSHI_CLIENT_SECRET,
      },
    })
      .then(r => (r.status === 404 ? null : r.json()), debugError('deleteService'))
      .then(debugSuccess('deleteService'), debugError('deleteService'));
  }

  function fetchOtoroshiService(id) {
    return fetch(`${OTOROSHI_URL}/api/services/${ORCHESTRATOR_NAME}-service-${id}`, {
      method: 'GET',
      headers: {
        Host: OTOROSHI_HOST,
        Accept: 'application/json',
        [OTOROSHI_CLIENT_ID_HEADER]: OTOROSHI_CLIENT_ID,
        [OTOROSHI_CLIENT_SECRET_HEADER]: OTOROSHI_CLIENT_SECRET,
      },
    })
      .then(r => (r.status === 404 ? null : r.json()), debugError('fetchOtoroshiService'))
      .then(debugSuccess('fetchOtoroshiService'), debugError('fetchOtoroshiService'));
  }

  ///////////////////////////////////////////////////////////////////////////////////////////////////
  ///////////////////////////////////////////////////////////////////////////////////////////////////
  ///////////////////////////////////////////////////////////////////////////////////////////////////

  function setupOtoroshi() {
    log('Checking Otoroshi setup ...');
    return fetchOtoroshiGroups()
      .then(groups => {
        const orchGroup = groups.filter(g => g.name === ORCHESTRATOR_GROUP)[0];
        if (orchGroup) {
          log(`  ${ORCHESTRATOR_NAME} group already exists ...`);
          return orchGroup;
        } else {
          log(`  Create ${ORCHESTRATOR_NAME} group in Otoroshi`);
          return createOtoroshiGroup();
        }
      })
      .then(group => {
        return fetchOtoroshiApiKeys(ORCHESTRATOR_GROUP).then(apikeys => {
          const orchApiKey = apikeys.filter(g => g.clientName === ORCHESTRATOR_API_KEY)[0];
          if (orchApiKey) {
            log(`  ${ORCHESTRATOR_NAME} api key already exists ...`);
            return orchApiKey;
          } else {
            log(`  Create ${ORCHESTRATOR_NAME} api key in Otoroshi`);
            return createOtoroshiApiKey();
          }
        });
      })
      .then(() => log('Otorshi setup done !\n'));
  }

  function syncOtoroshiWithOther() {
    syncing = true;
    const start = Date.now();
    fetchOrchestratorServices()
      .then(rs => {
        return fetchOtoroshiServices().then(os => [rs, os]);
      })
      .then(arr => {
        const [orchestratorServices, otoroshiServices] = arr;
        currentServices = orchestratorServices.length;
        const tasks = orchestratorServices.map(orchService => {
          const otoroshiService = otoroshiServices.filter(
            s => s.metadata.orchestratorId && s.metadata.orchestratorId === orchService.id
          )[0];
          const targets = orchService.targets.map(ep => ({
            scheme: 'http',
            host: `${ep.ipAddress}:${ep.port}`,
          }));
          const targetHosts = _.sortBy(targets.map(i => i.host), i => i);
          const otoHosts = otoroshiService
            ? _.sortBy(otoroshiService.targets.map(i => i.host), i => i)
            : [];
          const rootPath = orchService.name.split('-')[1];
          if (!otoroshiService) {
            log(
              `Creating Otoroshi service '${orchService.name}' with id: '${orchService.id}' (${targets.length} targets)`
            );
            return createOtoroshiService(orchService.id, orchService.name, targets);
          } else if (otoroshiService && !_.isEqual(targetHosts, otoHosts)) {
            log(
              `Updating Otoroshi service '${orchService.name}' with id: '${orchService.id}' (${targets.length} targets)`
            );
            return updateOtoroshiService(orchService.id, orchService.name, targets);
          } else {
            return new Promise(s => s());
          }
        });
        return Promise.all(tasks).then(() => [orchestratorServices, otoroshiServices]);
      })
      .then(arr => {
        return fetchOtoroshiServices().then(os => [arr[0], os]);
      })
      .then(arr => {
        const [orchestratorServices, otoroshiServices] = arr;
        const tasks = otoroshiServices.map(otoroshiService => {
          const orchService = orchestratorServices.filter(
            s => s.id && s.id === otoroshiService.metadata.orchestratorId
          )[0];
          if (!orchService) {
            log(`Deleting Otoroshi service ${otoroshiService.name} with id: ${otoroshiService.id}`);
            return deleteService(otoroshiService.id);
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
        setTimeout(syncOtoroshiWithOther, POLL_INTERVAL);
      });
  }

  const app = express();

  app.get('/', (req, res) => {
    res.status(200).type('text/html').send(`<html>
    <head>
      <title>Otoroshi Connector - Status</title>
    </head>
    <body style="display: flex; justify-content: center; align-items: center;flex-direction: column">
      <h1>Otoroshi Connector - Status</h1>
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

  function startDaemon() {
    app.listen(PORT, () => {
      console.log('\n# Welcome to the Otoroshi synchronizer daemon');
      console.log(`# The daemon will synchronize every ${POLL_INTERVAL} millis.`);
      console.log(`# The daemon status is available at http://127.0.0.1:${PORT}/status\n`);
      setupOtoroshi().then(() => {
        setTimeout(syncOtoroshiWithOther, 500);
      });
    });
  }

  return {
    start: startDaemon,
  };
};
