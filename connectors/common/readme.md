# Generic Synchronization daemon

Basic logic to synchronize Otoroshi and whatever. This is an highly experimental tool, use with caution.

## Usage

for instance here, a daemon to synchronize Otoroshi with Rancher

```javascript
const OtoroshiSynchronizer = require('./common');

const rancherDaemon = OtoroshiSynchronizer.daemon(config => {

  const RANCHER_URL = config.rancher.url;
  const RANCHER_PROJECT = config.rancher.project;
  const RANCHER_CLIENT_ID = config.rancher.clientId;
  const RANCHER_CLIENT_SECRET = config.rancher.clientSecret;
  const RANCHER_GROUP = config.rancher.group;
  const RANCHER_API_KEY = config.rancher.apiKey;
  const RANCHER_API_KEY_ID = config.rancher.apiKeyId;
  const RANCHER_API_KEY_SECRET = config.rancher.apiKeySecret;

  function fetchServices() {
    const user = base64.encode(`${RANCHER_CLIENT_ID}:${RANCHER_CLIENT_SECRET}`);
    return fetch(`${RANCHER_URL}/v2-beta/projects/${RANCHER_PROJECT}/services`, {
      method: 'GET',
      headers: {
        Accept: 'application/json',
        Authorization: `Basic ${user}`,
      },
    })
    .then(r => r.json())
    .then(rancherServices => {
      return rancherServices.data.filter(
        s =>
          s.state === 'active' &&
          s.launchConfig.labels['service-type'] &&
          s.launchConfig.labels['service-type'] === 'otoroshi-capable'
      );
    }).map(service => {
      return {
        id: service.id,
        name: service.name,
        targets: service.publicEndpoints.map(pe => {
          return {
            ipAddress: pe.ipAddress,
            port: ep.port,
          };
        })
      };
    });
  }

  return {
    name: 'rancher',
    group: RANCHER_GROUP,
    apiKey: RANCHER_API_KEY,
    apiKeyId: RANCHER_API_KEY_ID,
    apiKeySecret: RANCHER_API_KEY_SECRET,
    fetchServices
  };
});

rancherDaemon.start();
```