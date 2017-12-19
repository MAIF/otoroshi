const fs = require('fs');
const express = require('express');
const jwt = require('jsonwebtoken');
const bodyParser = require('body-parser');
const CleverAPI = require('clever-client');
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

const CLEVERCLOUD_ORGA_ID = CONFIG.clevercloud.orgaId;
const CLEVERCLOUD_API_HOST = CONFIG.clevercloud.apiHost;
const CLEVERCLOUD_CONSUMER_KEY = CONFIG.clevercloud.consumerKey;
const CLEVERCLOUD_CONSUMER_SECRET = CONFIG.clevercloud.consumerSecret;
const CLEVERCLOUD_OAUTH_TOKEN = CONFIG.clevercloud.oauthToken;
const CLEVERCLOUD_OAUTH_SECRET = CONFIG.clevercloud.oauthSecret;
const CLEVERCLOUD_GROUP = CONFIG.clevercloud.group;
const CLEVERCLOUD_API_KEY = CONFIG.clevercloud.apiKey;
const CLEVERCLOUD_API_KEY_ID = CONFIG.clevercloud.apiKeyId;
const CLEVERCLOUD_API_KEY_SECRET = CONFIG.clevercloud.apiKeySecret;

let syncing = false;
let lastSync = moment();
let lastDuration = moment();
let currentServices = 0;
let lastDeletedServices = 0;

const client = (() => {
  const cli = CleverAPI({
    API_HOST: CLEVERCLOUD_API_HOST,
    API_CONSUMER_KEY: CLEVERCLOUD_CONSUMER_KEY,
    API_CONSUMER_SECRET: CLEVERCLOUD_CONSUMER_SECRET,
    API_OAUTH_TOKEN: CLEVERCLOUD_OAUTH_TOKEN,
    API_OAUTH_TOKEN_SECRET: CLEVERCLOUD_OAUTH_SECRET,
    logger: console,
  });
  cli.session.getAuthorization = (httpMethod, url, params) => {
    return cli.session.getHMACAuthorization(httpMethod, url, params, {
      user_oauth_token: CLEVERCLOUD_OAUTH_TOKEN,
      user_oauth_token_secret: CLEVERCLOUD_OAUTH_SECRET,
    });
  };
  return cli;
})();

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

function createOtoroshiCleverCloudGroup() {
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
      id: CLEVERCLOUD_GROUP,
      name: CLEVERCLOUD_GROUP,
      description: 'Group for CleverCloud services',
    }),
  })
    .then(r => r.json(), debugError('createOtoroshiCleverCloudGroup'))
    .then(
      debugSuccess('createOtoroshiCleverCloudGroup'),
      debugError('createOtoroshiCleverCloudGroup')
    );
}

function fetchOtoroshiCleverCloudServices() {
  return fetch(`${OTOROSHI_URL}/api/services`, {
    method: 'GET',
    headers: {
      Host: OTOROSHI_HOST,
      Accept: 'application/json',
      [OTOROSHI_CLIENT_ID_HEADER]: OTOROSHI_CLIENT_ID,
      [OTOROSHI_CLIENT_SECRET_HEADER]: OTOROSHI_CLIENT_SECRET,
    },
  })
    .then(r => r.json(), debugError('fetchOtoroshiCleverCloudServices'))
    .then(services => {
      return services.filter(s => s.metadata.provider && s.metadata.provider === 'clevercloud');
    }, debugError('fetchOtoroshiCleverCloudServices'))
    .then(
      debugSuccess('fetchOtoroshiCleverCloudServices'),
      debugError('fetchOtoroshiCleverCloudServices')
    );
}

function fetchOtoroshiApiKeys() {
  return fetch(`${OTOROSHI_URL}/api/groups/${CLEVERCLOUD_GROUP}/apikeys`, {
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

function createOtoroshiCleverCloudApiKey() {
  return fetch(`${OTOROSHI_URL}/api/groups/${CLEVERCLOUD_GROUP}/apikeys`, {
    method: 'POST',
    headers: {
      Host: OTOROSHI_HOST,
      Accept: 'application/json',
      'Content-Type': 'application/json',
      [OTOROSHI_CLIENT_ID_HEADER]: OTOROSHI_CLIENT_ID,
      [OTOROSHI_CLIENT_SECRET_HEADER]: OTOROSHI_CLIENT_SECRET,
    },
    body: JSON.stringify({
      clientId: CLEVERCLOUD_API_KEY_ID,
      clientSecret: CLEVERCLOUD_API_KEY_SECRET,
      clientName: CLEVERCLOUD_API_KEY,
      authorizedGroup: CLEVERCLOUD_GROUP,
    }),
  })
    .then(r => r.json(), debugError('createOtoroshiCleverCloudApiKey'))
    .then(
      debugSuccess('createOtoroshiCleverCloudApiKey'),
      debugError('createOtoroshiCleverCloudApiKey')
    );
}

function createOtoroshiCleverCloudService(id, name, targets) {
  const serviceTemplate = {
    id: `clevercloud-service-${id}`,
    groupId: CLEVERCLOUD_GROUP,
    name: 'clevercloud-' + name,
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
      provider: 'clevercloud',
      clevercloudId: id,
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
    .then(r => r.json(), debugError('createOtoroshiCleverCloudService'))
    .then(
      debugSuccess('createOtoroshiCleverCloudService'),
      debugError('createOtoroshiCleverCloudService')
    );
}

function updateOtoroshiCleverCloudService(id, name, targets) {
  return fetch(`${OTOROSHI_URL}/api/services/clevercloud-service-${id}`, {
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
    .then(r => r.json(), debugError('updateOtoroshiCleverCloudService'))
    .then(
      debugSuccess('updateOtoroshiCleverCloudService'),
      debugError('updateOtoroshiCleverCloudService')
    );
}

function deleteOtoroshiCleverCloudService(id) {
  return fetch(`${OTOROSHI_URL}/api/services/clevercloud-service-${id}`, {
    method: 'DELETE',
    headers: {
      Host: OTOROSHI_HOST,
      Accept: 'application/json',
      [OTOROSHI_CLIENT_ID_HEADER]: OTOROSHI_CLIENT_ID,
      [OTOROSHI_CLIENT_SECRET_HEADER]: OTOROSHI_CLIENT_SECRET,
    },
  })
    .then(r => (r.status === 404 ? null : r.json()), debugError('deleteOtoroshiCleverCloudService'))
    .then(
      debugSuccess('deleteOtoroshiCleverCloudService'),
      debugError('deleteOtoroshiCleverCloudService')
    );
}

function deleteCleverCloudService(id) {
  return fetch(`${OTOROSHI_URL}/api/services/${id}`, {
    method: 'DELETE',
    headers: {
      Host: OTOROSHI_HOST,
      Accept: 'application/json',
      [OTOROSHI_CLIENT_ID_HEADER]: OTOROSHI_CLIENT_ID,
      [OTOROSHI_CLIENT_SECRET_HEADER]: OTOROSHI_CLIENT_SECRET,
    },
  })
    .then(r => (r.status === 404 ? null : r.json()), debugError('deleteCleverCloudService'))
    .then(debugSuccess('deleteCleverCloudService'), debugError('deleteCleverCloudService'));
}

function fetchOtoroshiCleverCloudService(id) {
  return fetch(`${OTOROSHI_URL}/api/services/clevercloud-service-${id}`, {
    method: 'GET',
    headers: {
      Host: OTOROSHI_HOST,
      Accept: 'application/json',
      [OTOROSHI_CLIENT_ID_HEADER]: OTOROSHI_CLIENT_ID,
      [OTOROSHI_CLIENT_SECRET_HEADER]: OTOROSHI_CLIENT_SECRET,
    },
  })
    .then(r => (r.status === 404 ? null : r.json()), debugError('fetchOtoroshiCleverCloudService'))
    .then(
      debugSuccess('fetchOtoroshiCleverCloudService'),
      debugError('fetchOtoroshiCleverCloudService')
    );
}

///////////////////////////////////////////////////////////////////////////////////////////////////
///////////////////////////////////////////////////////////////////////////////////////////////////
///////////////////////////////////////////////////////////////////////////////////////////////////

function LazyPromiseAll(arr, par, f) {
  return new Promise((success, error) => {
    const tasks = [...arr];
    const results = [];
    function performTask() {
      const task = tasks.pop();
      if (task) {
        f(task).then(
          res => {
            results.push(res);
            setTimeout(performTask);
          },
          e => error(e)
        );
      } else if (!task && results.length === arr.length) {
        success(results);
      } else {
        // console.log('Worker dying ...');
      }
    }
    for (let i = 0; i < par; i++) {
      performTask();
    }
  });
}

function fetchCleverCloudServices() {
  return client.organisations._.applications
    .get()
    .withParams([CLEVERCLOUD_ORGA_ID])
    .send()
    .toPromise()
    .then(apps => {
      return LazyPromiseAll(apps, 4, app => {
        return client.organisations._.applications._.env
          .get()
          .withParams([CLEVERCLOUD_ORGA_ID, app.id])
          .send()
          .toPromise()
          .then(
            envValues => {
              const env = {};
              envValues.forEach(e => (env[e.name] = e.value));
              return Object.assign({}, app, { env });
            },
            err => {
              console.log(`Error while fetching envs for ${app.id}`);
              return [];
            }
          );
      });
    })
    .then(apps => {
      return apps.filter(s => {
        return (
          s &&
          s.state === 'SHOULD_BE_UP' &&
          s.env['SERVICE_TYPE'] &&
          s.env['SERVICE_TYPE'] === 'otoroshi-capable'
        );
      });
    })
    .then(apps => {
      //console.log('CleverApps', JSON.stringify(apps, null, 2));
      return apps;
    });
}

///////////////////////////////////////////////////////////////////////////////////////////////////
///////////////////////////////////////////////////////////////////////////////////////////////////
///////////////////////////////////////////////////////////////////////////////////////////////////

function setupOtoroshi() {
  log('Checking Otoroshi setup ...');
  return fetchOtoroshiGroups()
    .then(groups => {
      const clevercloudGroup = groups.filter(g => g.name === CLEVERCLOUD_GROUP)[0];
      if (clevercloudGroup) {
        log('  CleverCloud group already exists ...');
        return clevercloudGroup;
      } else {
        log('  Create CleverCloud group in Otoroshi');
        return createOtoroshiCleverCloudGroup();
      }
    })
    .then(group => {
      return fetchOtoroshiApiKeys(CLEVERCLOUD_GROUP).then(apikeys => {
        const clevercloudApiKey = apikeys.filter(g => g.clientName === CLEVERCLOUD_API_KEY)[0];
        if (clevercloudApiKey) {
          log('  CleverCloud api key already exists ...');
          return clevercloudApiKey;
        } else {
          log('  Create CleverCloud api key in Otoroshi');
          return createOtoroshiCleverCloudApiKey();
        }
      });
    })
    .then(() => log('Otoroshi setup done !\n'));
}

function syncOtoroshiWithCleverCloud() {
  syncing = true;
  const start = Date.now();
  fetchCleverCloudServices()
    .then(rs => {
      return fetchOtoroshiCleverCloudServices().then(os => [rs, os]);
    })
    .then(arr => {
      const [clevercloudServices, otoroshiServices] = arr;
      currentServices = clevercloudServices.length;
      const tasks = clevercloudServices.map(clevercloudService => {
        const otoroshiService = otoroshiServices.filter(
          s => s.metadata.clevercloudId && s.metadata.clevercloudId === clevercloudService.id
        )[0];
        const targets = clevercloudService.vhosts.map(ep => ({
          scheme: 'https',
          host: `${ep.fqdn}`,
        }));
        const targetHosts = _.sortBy(targets.map(i => i.host), i => i);
        const otoHosts = otoroshiService
          ? _.sortBy(otoroshiService.targets.map(i => i.host), i => i)
          : [];
        if (!otoroshiService) {
          log(
            `Creating Otoroshi service '${clevercloudService.name}' with id: '${
              clevercloudService.id
            }' (${targets.length} targets)`
          );
          return createOtoroshiCleverCloudService(
            clevercloudService.id,
            clevercloudService.name,
            targets
          );
        } else if (otoroshiService && !_.isEqual(targetHosts, otoHosts)) {
          log(
            `Updating Otoroshi service '${clevercloudService.name}' with id: '${
              clevercloudService.id
            }' (${targets.length} targets)`
          );
          return updateOtoroshiCleverCloudService(
            clevercloudService.id,
            clevercloudService.name,
            targets
          );
        } else {
          return new Promise(s => s());
        }
      });
      return Promise.all(tasks).then(() => [clevercloudServices, otoroshiServices]);
    })
    .then(arr => {
      return fetchOtoroshiCleverCloudServices().then(os => [arr[0], os]);
    })
    .then(arr => {
      const [clevercloudServices, otoroshiServices] = arr;
      const tasks = otoroshiServices.map(otoroshiService => {
        const clevercloudService = clevercloudServices.filter(
          s => s.id && s.id === otoroshiService.metadata.clevercloudId
        )[0];
        if (!clevercloudService) {
          log(`Deleting Otoroshi service ${otoroshiService.name} with id: ${otoroshiService.id}`);
          return deleteCleverCloudService(otoroshiService.id);
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
      setTimeout(syncOtoroshiWithCleverCloud, POLL_INTERVAL);
    });
}

const app = express();

app.get('/', (req, res) => {
  res.status(200).type('text/html').send(`<html>
  <head>
    <title>Otoroshi - CleverCloud Connector - Status</title>
  </head>
  <body style="display: flex; justify-content: center; align-items: center;flex-direction: column">
    <h1>Otoroshi - CleverCloud Connector - Status</h1>
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
  console.log('\n# Welcome to the Otoroshi Clevercloud synchronizer daemon');
  console.log(`# The daemon will synchronize every ${POLL_INTERVAL} millis.`);
  console.log(`# The daemon status is available at http://127.0.0.1:${PORT}/status\n`);
  //log('==========================\n');
  setupOtoroshi().then(() => {
    //log('==========================\n');
    setTimeout(syncOtoroshiWithCleverCloud, 500);
  });
});
