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
const KUBERNETES_URL = CONFIG.kubernetes.url;
const KUBERNETES_NAMESPACE = CONFIG.kubernetes.namespace;
const KUBERNETES_BEARER = CONFIG.kubernetes.bearer;
const KUBERNETES_SERVICE_IP = CONFIG.kubernetes.ipAccessor;
const KUBERNETES_GROUP = CONFIG.kubernetes.group;
const KUBERNETES_API_KEY = CONFIG.kubernetes.apiKey;
const KUBERNETES_API_KEY_ID = CONFIG.kubernetes.apiKeyId;
const KUBERNETES_API_KEY_SECRET = CONFIG.kubernetes.apiKeySecret;

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

function createOtoroshiKubernetesGroup() {
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
      id: KUBERNETES_GROUP,
      name: KUBERNETES_GROUP,
      description: 'Group for Kubernetes services',
    }),
  })
    .then(r => r.json(), debugError('createOtoroshiKubernetesGroup'))
    .then(
      debugSuccess('createOtoroshiKubernetesGroup'),
      debugError('createOtoroshiKubernetesGroup')
    );
}

function fetchOtoroshiKubernetesServices() {
  return fetch(`${OTOROSHI_URL}/api/services`, {
    method: 'GET',
    headers: {
      Host: OTOROSHI_HOST,
      Accept: 'application/json',
      [OTOROSHI_CLIENT_ID_HEADER]: OTOROSHI_CLIENT_ID,
      [OTOROSHI_CLIENT_SECRET_HEADER]: OTOROSHI_CLIENT_SECRET,
    },
  })
    .then(r => r.json(), debugError('fetchOtoroshiKubernetesServices'))
    .then(services => {
      return services.filter(s => s.metadata.provider && s.metadata.provider === 'kubernetes');
    }, debugError('fetchOtoroshiKubernetesServices'))
    .then(
      debugSuccess('fetchOtoroshiKubernetesServices'),
      debugError('fetchOtoroshiKubernetesServices')
    );
}

function fetchOtoroshiApiKeys() {
  return fetch(`${OTOROSHI_URL}/api/groups/${KUBERNETES_GROUP}/apikeys`, {
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

function createOtoroshiKubernetesApiKey() {
  return fetch(`${OTOROSHI_URL}/api/groups/${KUBERNETES_GROUP}/apikeys`, {
    method: 'POST',
    headers: {
      Host: OTOROSHI_HOST,
      Accept: 'application/json',
      'Content-Type': 'application/json',
      [OTOROSHI_CLIENT_ID_HEADER]: OTOROSHI_CLIENT_ID,
      [OTOROSHI_CLIENT_SECRET_HEADER]: OTOROSHI_CLIENT_SECRET,
    },
    body: JSON.stringify({
      clientId: KUBERNETES_API_KEY_ID,
      clientSecret: KUBERNETES_API_KEY_SECRET,
      clientName: KUBERNETES_API_KEY,
      authorizedGroup: KUBERNETES_GROUP,
    }),
  })
    .then(r => r.json(), debugError('createOtoroshiKubernetesApiKey'))
    .then(
      debugSuccess('createOtoroshiKubernetesApiKey'),
      debugError('createOtoroshiKubernetesApiKey')
    );
}

function createOtoroshiKubernetesService(id, name, targets) {
  const serviceTemplate = {
    id: `kubernetes-service-${id}`,
    groupId: KUBERNETES_GROUP,
    name: 'kubernetes-' + name,
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
      provider: 'kubernetes',
      kubernetesId: id,
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
    .then(r => r.json(), debugError('createOtoroshiKubernetesService'))
    .then(
      debugSuccess('createOtoroshiKubernetesService'),
      debugError('createOtoroshiKubernetesService')
    );
}

function updateOtoroshiKubernetesService(id, name, targets) {
  return fetch(`${OTOROSHI_URL}/api/services/kubernetes-service-${id}`, {
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
    .then(r => r.json(), debugError('updateOtoroshiKubernetesService'))
    .then(
      debugSuccess('updateOtoroshiKubernetesService'),
      debugError('updateOtoroshiKubernetesService')
    );
}

function deleteOtoroshiKubernetesService(id) {
  return fetch(`${OTOROSHI_URL}/api/services/kubernetes-service-${id}`, {
    method: 'DELETE',
    headers: {
      Host: OTOROSHI_HOST,
      Accept: 'application/json',
      [OTOROSHI_CLIENT_ID_HEADER]: OTOROSHI_CLIENT_ID,
      [OTOROSHI_CLIENT_SECRET_HEADER]: OTOROSHI_CLIENT_SECRET,
    },
  })
    .then(r => (r.status === 404 ? null : r.json()), debugError('deleteOtoroshiKubernetesService'))
    .then(
      debugSuccess('deleteOtoroshiKubernetesService'),
      debugError('deleteOtoroshiKubernetesService')
    );
}

function deleteKubernetesService(id) {
  return fetch(`${OTOROSHI_URL}/api/services/${id}`, {
    method: 'DELETE',
    headers: {
      Host: OTOROSHI_HOST,
      Accept: 'application/json',
      [OTOROSHI_CLIENT_ID_HEADER]: OTOROSHI_CLIENT_ID,
      [OTOROSHI_CLIENT_SECRET_HEADER]: OTOROSHI_CLIENT_SECRET,
    },
  })
    .then(r => (r.status === 404 ? null : r.json()), debugError('deleteKubernetesService'))
    .then(debugSuccess('deleteKubernetesService'), debugError('deleteKubernetesService'));
}

function fetchOtoroshiKubernetesService(id) {
  return fetch(`${OTOROSHI_URL}/api/services/kubernetes-service-${id}`, {
    method: 'GET',
    headers: {
      Host: OTOROSHI_HOST,
      Accept: 'application/json',
      [OTOROSHI_CLIENT_ID_HEADER]: OTOROSHI_CLIENT_ID,
      [OTOROSHI_CLIENT_SECRET_HEADER]: OTOROSHI_CLIENT_SECRET,
    },
  })
    .then(r => (r.status === 404 ? null : r.json()), debugError('fetchOtoroshiKubernetesService'))
    .then(
      debugSuccess('fetchOtoroshiKubernetesService'),
      debugError('fetchOtoroshiKubernetesService')
    );
}

///////////////////////////////////////////////////////////////////////////////////////////////////
///////////////////////////////////////////////////////////////////////////////////////////////////
///////////////////////////////////////////////////////////////////////////////////////////////////

function fetchKubernetesServices() {
  return fetch(`${KUBERNETES_URL}/api/v1/namespaces/${KUBERNETES_NAMESPACE}/pods`, {
    method: 'GET',
    headers: {
      Accept: 'application/json',
      Authorization: `Bearer ${KUBERNETES_BEARER}`,
    },
  })
    .then(r => r.json(), debugError('fetchKubernetesServices pods'))
    .then(podsResponse => {
      return fetch(`${KUBERNETES_URL}/api/v1/namespaces/${KUBERNETES_NAMESPACE}/services`, {
        method: 'GET',
        headers: {
          Accept: 'application/json',
          Authorization: `Bearer ${KUBERNETES_BEARER}`,
        },
      })
        .then(r => r.json(), debugError('fetchKubernetesServices services'))
        .then(servicesResponse => {
          return fetch(`${KUBERNETES_URL}/api/v1/namespaces/${KUBERNETES_NAMESPACE}/endpoints`, {
            method: 'GET',
            headers: {
              Accept: 'application/json',
              Authorization: `Bearer ${KUBERNETES_BEARER}`,
            },
          })
            .then(r => r.json(), debugError('fetchKubernetesServices endpoints'))
            .then(endpointsResponse => {
              return fetch(`${KUBERNETES_URL}/apis/extensions/v1beta1/ingresses`, {
                method: 'GET',
                headers: {
                  Accept: 'application/json',
                  Authorization: `Bearer ${KUBERNETES_BEARER}`,
                },
              })
                .then(r => r.json(), debugError('fetchKubernetesServices ingresses'))
                .then(ingressesResponse => {
                  return {
                    ingresses: ingressesResponse.items,
                    pods: podsResponse.items,
                    services: servicesResponse.items,
                    endpoints: endpointsResponse.items,
                  };
                });
            });
        });
    })
    .then(items => {
      const { ingresses, pods, services, endpoints } = items;
      // console.log('ingresses', JSON.stringify(ingresses, null, 2))
      // console.log('pods', JSON.stringify(pods, null, 2))
      // console.log('services', JSON.stringify(services, null, 2))
      // console.log('endpoints', JSON.stringify(endpoints, null, 2))
      const targetedServices = services
        .map(service => {
          return {
            name: service.metadata.name,
            id: service.metadata.uid,
            containerPorts: service.spec.ports.map(p => p.port),
            ports: service.spec.ports.map(p => p.nodePort),
            clusterIP: service.spec.clusterIP,
          };
        })
        .filter(s => s.name !== 'kubernetes')
        .map(service => {
          const rawPods = pods.filter(p => p.spec.containers.find(c => c.name === service.name));
          const servicePods = rawPods.map(pod => {
            return {
              hostIP: pod.status.hostIP,
              podIP: pod.status.podIP,
              ready: pod.status.containerStatuses
                .filter(s => s.name === service.name)
                .map(s => s.ready)[0],
            };
          });
          const publicEndpoints = _.uniqBy(
            servicePods.map(pod => {
              return {
                ipAddress: pod[KUBERNETES_SERVICE_IP],
                port: service.ports[0],
              };
            }),
            ep => `${ep.ipAddress}:${ep.port}`
          );
          return { ...service, pods: servicePods, publicEndpoints };
        });
      return targetedServices;
    }, debugError('fetchKubernetesServices'))
    .then(debugSuccess('fetchKubernetesServices'), debugError('fetchKubernetesServices'));
}

///////////////////////////////////////////////////////////////////////////////////////////////////
///////////////////////////////////////////////////////////////////////////////////////////////////
///////////////////////////////////////////////////////////////////////////////////////////////////

function setupOtoroshi() {
  log('Checking Otoroshi setup ...');
  return fetchOtoroshiGroups()
    .then(groups => {
      const kubernetesGroup = groups.filter(g => g.name === KUBERNETES_GROUP)[0];
      if (kubernetesGroup) {
        log('  Kubernetes group already exists ...');
        return kubernetesGroup;
      } else {
        log('  Create Kubernetes group in Otoroshi');
        return createOtoroshiKubernetesGroup();
      }
    })
    .then(group => {
      return fetchOtoroshiApiKeys(KUBERNETES_GROUP).then(apikeys => {
        const kubernetesApiKey = apikeys.filter(g => g.clientName === KUBERNETES_API_KEY)[0];
        if (kubernetesApiKey) {
          log('  Kubernetes api key already exists ...');
          return kubernetesApiKey;
        } else {
          log('  Create Kubernetes api key in Otoroshi');
          return createOtoroshiKubernetesApiKey();
        }
      });
    })
    .then(() => log('Otorshi setup done !\n'));
}

function syncOtoroshiWithKubernetes() {
  // log('Synchronizing Otoroshi with Kubernetes ...');
  syncing = true;
  const start = Date.now();
  fetchKubernetesServices()
    .then(rs => {
      return fetchOtoroshiKubernetesServices().then(os => [rs, os]);
    })
    .then(arr => {
      const [kubernetesServices, otoroshiServices] = arr;
      currentServices = kubernetesServices.length;
      //log('  Synchronizing Kubernetes => Otoroshi');
      const tasks = kubernetesServices.map(kubernetesService => {
        const otoroshiService = otoroshiServices.filter(
          s => s.metadata.kubernetesId && s.metadata.kubernetesId === kubernetesService.id
        )[0];
        const targets = kubernetesService.publicEndpoints.map(ep => ({
          scheme: 'http',
          host: `${ep.ipAddress}:${ep.port}`,
        }));
        const targetHosts = _.sortBy(targets.map(i => i.host), i => i);
        const otoHosts = otoroshiService
          ? _.sortBy(otoroshiService.targets.map(i => i.host), i => i)
          : [];
        const rootPath = kubernetesService.name.split('-')[1];
        if (!otoroshiService) {
          log(
            `Creating Otoroshi service '${kubernetesService.name}' with id: '${kubernetesService.id}' (${targets.length} targets)`
          );
          return createOtoroshiKubernetesService(
            kubernetesService.id,
            kubernetesService.name,
            targets
          );
        } else if (otoroshiService && !_.isEqual(targetHosts, otoHosts)) {
          log(
            `Updating Otoroshi service '${kubernetesService.name}' with id: '${kubernetesService.id}' (${targets.length} targets)`
          );
          return updateOtoroshiKubernetesService(
            kubernetesService.id,
            kubernetesService.name,
            targets
          );
        } else {
          return new Promise(s => s());
        }
      });
      return Promise.all(tasks).then(() => [kubernetesServices, otoroshiServices]);
    })
    .then(arr => {
      return fetchOtoroshiKubernetesServices().then(os => [arr[0], os]);
    })
    .then(arr => {
      //log('  Synchronizing Otoroshi => Kubernetes');
      const [kubernetesServices, otoroshiServices] = arr;
      const tasks = otoroshiServices.map(otoroshiService => {
        const kubernetesService = kubernetesServices.filter(
          s => s.id && s.id === otoroshiService.metadata.kubernetesId
        )[0];
        if (!kubernetesService) {
          log(`Deleting Otoroshi service ${otoroshiService.name} with id: ${otoroshiService.id}`);
          return deleteKubernetesService(otoroshiService.id);
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
      setTimeout(syncOtoroshiWithKubernetes, POLL_INTERVAL);
    });
}

const app = express();

app.get('/', (req, res) => {
  res.status(200).type('text/html').send(`<html>
  <head>
    <title>Otoroshi - Kubernetes Connector - Status</title>
  </head>
  <body style="display: flex; justify-content: center; align-items: center;flex-direction: column">
    <h1>Otoroshi - Kubernetes Connector - Status</h1>
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
  console.log('\n# Welcome to the Otoroshi Kubernetes synchronizer daemon');
  console.log(`# The daemon will synchronize every ${POLL_INTERVAL} millis.`);
  console.log(`# The daemon status is available at http://127.0.0.1:${PORT}/status\n`);
  //log('==========================\n');
  setupOtoroshi().then(() => {
    //log('==========================\n');
    setTimeout(syncOtoroshiWithKubernetes, 500);
  });
});
