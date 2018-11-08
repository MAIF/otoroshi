import moment from 'moment';

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Should stay in BackOffice controller
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

export function syncWithMaster(config) {
  return fetch(`/bo/api/redis/sync`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(config),
  }).then(r => r.json());
}

export function resetDB() {
  return fetch(`/bo/api/resetdb`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function fetchLine(lineId) {
  throw new Error('Deprecated API. Should not be used anymore !');
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// should use api proxy
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

export function env() {
  return fetch('/bo/api/env', {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function version() {
  return fetch('/bo/api/version', {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function fetchClusterMembers() {
  return fetch(`/bo/api/proxy/api/cluster/members`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function fetchSnowMonkeyOutages() {
  return fetch(`/bo/api/proxy/api/snowmonkey/outages`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function fetchSnowMonkeyConfig() {
  return fetch(`/bo/api/proxy/api/snowmonkey/config`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function updateSnowMonkeyConfig(config) {
  return fetch(`/bo/api/proxy/api/snowmonkey/config`, {
    method: 'PUT',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(config),
  }).then(r => r.json());
}

export function startSnowMonkey() {
  return fetch(`/bo/api/proxy/api/snowmonkey/_start`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function stopSnowMonkey() {
  return fetch(`/bo/api/proxy/api/snowmonkey/_stop`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function fetchCanaryCampaign(serviceId) {
  return fetch(`/bo/api/proxy/api/services/${serviceId}/canary`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function resetCanaryCampaign(serviceId) {
  return fetch(`/bo/api/proxy/api/services/${serviceId}/canary`, {
    method: 'DELETE',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function allServices(env, group) {
  const url = env
    ? `/bo/api/proxy/api/services?env=${env}`
    : group ? `/bo/api/proxy/api/services?group=${group}` : `/bo/api/proxy/api/services`;
  return fetch(url, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function fetchRemainingQuotas(groupId, clientId) {
  return fetch(`/bo/api/proxy/api/groups/${groupId}/apikeys/${clientId}/quotas`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function resetRemainingQuotas(groupId, clientId) {
  return fetch(`/bo/api/proxy/api/groups/${groupId}/apikeys/${clientId}/quotas`, {
    method: 'DELETE',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function fetchServiceEvents(serviceId, from, to) {
  return fetch(
    `/bo/api/proxy/api/services/${serviceId}/events?from=${from.valueOf()}&to=${to.valueOf()}`,
    {
      method: 'GET',
      credentials: 'include',
      headers: {
        Accept: 'application/json',
      },
    }
  ).then(r => r.json());
}

export function fetchServiceStats(serviceId, from, to) {
  return fetch(
    `/bo/api/proxy/api/services/${serviceId}/stats?from=${from.valueOf()}&to=${to.valueOf()}`,
    {
      method: 'GET',
      credentials: 'include',
      headers: {
        Accept: 'application/json',
      },
    }
  ).then(
    r => {
      if (r.status === 200) {
        return r.json();
      }
      console.log('error while fetching global stats');
      return {};
    },
    e => {
      console.log('error while fetching global stats');
      return {};
    }
  );
}

export function fetchGlobalStats(from, to) {
  return fetch(`/bo/api/proxy/api/stats/global?from=${from.valueOf()}&to=${to.valueOf()}`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(
    r => {
      if (r.status === 200) {
        return r.json();
      }
      console.log('error while fetching global stats');
      return {};
    },
    e => {
      console.log('error while fetching global stats');
      return {};
    }
  );
}

export function fetchHealthCheckEvents(serviceId) {
  return fetch(`/bo/api/proxy/api/services/${serviceId}/health`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function fetchLines() {
  return fetch('/bo/api/proxy/api/lines', {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  })
    .then(
      r => r.json(),
      e => {
        console.log(e);
        return ['prod'];
      }
    )
    .then(
      r => r,
      e => {
        console.log(e);
        return ['prod'];
      }
    );
}

export function fetchApiKeys(lineId, serviceId) {
  return fetch(`/bo/api/proxy/api/services/${serviceId}/apikeys`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function deleteApiKey(serviceId, ak) {
  return fetch(`/bo/api/proxy/api/services/${serviceId}/apikeys/${ak.clientId}`, {
    method: 'DELETE',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function createApiKey(serviceId, ak) {
  return fetch(`/bo/api/proxy/api/services/${serviceId}/apikeys`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(ak),
  }).then(r => r.json());
}

export function updateApiKey(serviceId, ak) {
  return fetch(`/bo/api/proxy/api/services/${serviceId}/apikeys/${ak.clientId}`, {
    method: 'PUT',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(ak),
  }).then(r => r.json());
}

export function getGlobalConfig() {
  return fetch(`/bo/api/proxy/api/globalconfig`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function updateGlobalConfig(gc) {
  return fetch(`/bo/api/proxy/api/globalconfig`, {
    method: 'PUT',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(gc),
  }).then(r => r.json());
}

export function fetchService(lineId, serviceId) {
  return fetch(`/bo/api/proxy/api/services/${serviceId}`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function findServicesForGroup(group) {
  return fetch(`/bo/api/proxy/api/groups/${group.id}/services`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function findAllGroups() {
  return fetch('/bo/api/proxy/api/groups', {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function findGroupById(id) {
  return fetch(`/bo/api/proxy/api/groups/${id}`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function deleteGroup(ak) {
  return fetch(`/bo/api/proxy/api/groups/${ak.id}`, {
    method: 'DELETE',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function createGroup(ak) {
  return fetch(`/bo/api/proxy/api/groups`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(ak),
  }).then(r => r.json());
}

export function updateGroup(ak) {
  return fetch(`/bo/api/proxy/api/groups/${ak.id}`, {
    method: 'PUT',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(ak),
  }).then(r => r.json());
}

export function deleteService(service) {
  return fetch(`/bo/api/proxy/api/services/${service.id}`, {
    method: 'DELETE',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function createNewService() {
  return fetch(`/bo/api/proxy/api/new/service`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function saveService(service) {
  return fetch(`/bo/api/proxy/api/services`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(service),
  }).then(r => r.json());
}

export function updateService(serviceId, service) {
  return fetch(`/bo/api/proxy/api/services/${serviceId}`, {
    method: 'PUT',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(service),
  }).then(r => r.json());
}

export function findAllApps() {
  return fetch(`/bo/api/apps`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function discardAllSessions() {
  return fetch(`/bo/api/sessions`, {
    method: 'DELETE',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function discardSession(id) {
  return fetch(`/bo/api/sessions/${id}`, {
    method: 'DELETE',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function fetchSessions() {
  return fetch(`/bo/api/sessions`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function discardAllPrivateAppsSessions() {
  return fetch(`/bo/api/papps/sessions`, {
    method: 'DELETE',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function discardPrivateAppsSession(id) {
  return fetch(`/bo/api/papps/sessions/${id}`, {
    method: 'DELETE',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function fetchPrivateAppsSessions() {
  return fetch(`/bo/api/papps/sessions`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function panicMode() {
  return fetch(`/bo/api/panic`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
    body: '{}',
  }).then(r => r.json());
}

export function fetchAdmins() {
  return fetch(`/bo/u2f/admins`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  })
    .then(r => r.json())
    .then(_u2fAdmins => {
      return fetch(`/bo/simple/admins`, {
        method: 'GET',
        credentials: 'include',
        headers: {
          Accept: 'application/json',
        },
      })
        .then(r => r.json())
        .then(_admins => {
          const admins = _admins.map(admin => ({ ...admin, type: 'SIMPLE' }));
          const u2fAdmins = _u2fAdmins.map(admin => ({ ...admin, type: 'U2F' }));
          return [...u2fAdmins, ...admins];
        });
    });
}

export function discardAdmin(username, id) {
  if (!id) {
    return fetch(`/bo/simple/admins/${username}`, {
      method: 'DELETE',
      credentials: 'include',
      headers: {
        Accept: 'application/json',
      },
    }).then(r => r.json());
  } else {
    return fetch(`/bo/u2f/admins/${username}/${id}`, {
      method: 'DELETE',
      credentials: 'include',
      headers: {
        Accept: 'application/json',
      },
    }).then(r => r.json());
  }
}

export function fetchOtoroshi() {
  return fetch(`/bo/api/proxy/api/otoroshi.json`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function fetchAuditEvents() {
  return fetch(`/bo/api/events/audit`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function fetchAlertEvents() {
  return fetch(`/bo/api/events/alert`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function fetchLoggers() {
  return fetch(`/bo/api/loggers`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function changeLogLevel(name, level) {
  return fetch(`/bo/api/loggers/${name}/level?newLevel=${level}`, {
    method: 'PUT',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
    body: '{}',
  }).then(r => r.json());
}

export function fetchTop10() {
  return fetch(`/bo/api/services/top10`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function fetchServicesMap() {
  return fetch(`/bo/api/services/map`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function fetchServicesTree() {
  return new Promise(s =>
    s({
      nodes: [
        { id: 'Otoroshi', group: '1' },
        { id: 'Group 1', group: '2' },
        { id: 'Group 2', group: '3' },
        { id: 'Service 11', group: '2' },
        { id: 'Service 21', group: '2' },
        { id: 'Service 12', group: '3' },
        { id: 'Service 22', group: '3' },
      ],
      links: [
        { source: 'Otoroshi', target: 'Group 1', value: 3 },
        { source: 'Otoroshi', target: 'Group 2', value: 3 },

        { source: 'Group 1', target: 'Service 11', value: 1 },
        { source: 'Group 1', target: 'Service 21', value: 1 },

        { source: 'Group 2', target: 'Service 12', value: 1 },
        { source: 'Group 2', target: 'Service 22', value: 1 },
      ],
    })
  );
  return fetch(`/bo/api/services/tree`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function findTemplateById(id) {
  return fetch(`/bo/api/proxy/api/services/${id}/template`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => (r.status === 404 ? null : r.json()));
}

export function deleteTemplate(ak) {
  return fetch(`/bo/api/proxy/api/services/${ak.serviceId}/template`, {
    method: 'DELETE',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function createTemplate(ak) {
  return fetch(`/bo/api/proxy/api/services/${ak.serviceId}/template`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(ak),
  }).then(r => r.json());
}

export function updateTemplate(ak) {
  return fetch(`/bo/api/proxy/api/services/${ak.serviceId}/template`, {
    method: 'PUT',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(ak),
  }).then(r => r.json());
}

export function findAllJwtVerifiers() {
  return fetch('/bo/api/proxy/api/verifiers', {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function findJwtVerifierById(id) {
  return fetch(`/bo/api/proxy/api/verifiers/${id}`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function deleteJwtVerifier(ak) {
  return fetch(`/bo/api/proxy/api/verifiers/${ak.id}`, {
    method: 'DELETE',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function createJwtVerifier(ak) {
  return fetch(`/bo/api/proxy/api/verifiers`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(ak),
  }).then(r => r.json());
}

export function updateJwtVerifier(ak) {
  return fetch(`/bo/api/proxy/api/verifiers/${ak.id}`, {
    method: 'PUT',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(ak),
  }).then(r => r.json());
}

export function findAllAuthConfigs() {
  return fetch('/bo/api/proxy/api/auths', {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function findAuthConfigById(id) {
  return fetch(`/bo/api/proxy/api/auths/${id}`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function deleteAuthConfig(ak) {
  return fetch(`/bo/api/proxy/api/auths/${ak.id}`, {
    method: 'DELETE',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function createAuthConfig(ak) {
  return fetch(`/bo/api/proxy/api/auths`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(ak),
  }).then(r => r.json());
}

export function updateAuthConfig(ak) {
  return fetch(`/bo/api/proxy/api/auths/${ak.id}`, {
    method: 'PUT',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(ak),
  }).then(r => r.json());
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

export function findAllCertificates() {
  return fetch('/bo/api/proxy/api/certificates', {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function findCertificateById(id) {
  return fetch(`/bo/api/proxy/api/certificates/${id}`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function deleteCertificate(ak) {
  return fetch(`/bo/api/proxy/api/certificates/${ak.id}`, {
    method: 'DELETE',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(r => r.json());
}

export function createCertificate(ak) {
  return fetch(`/bo/api/proxy/api/certificates`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(ak),
  }).then(r => r.json());
}

export function updateCertificate(ak) {
  return fetch(`/bo/api/proxy/api/certificates/${ak.id}`, {
    method: 'PUT',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(ak),
  }).then(r => r.json());
}

export function certData(chain) {
  return fetch(`/bo/api/certificates/_data`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'text/plain',
    },
    body: chain,
  }).then(r => r.json());
}

export function certValid(cert) {
  return fetch(`/bo/api/certificates/_valid`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(cert),
  }).then(r => r.json());
}

export function selfSignedCert(host) {
  return fetch(`/bo/api/certificates/_selfSigned`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ host }),
  }).then(r => r.json());
}

export function caSignedCert(id, host) {
  return fetch(`/bo/api/certificates/_caSigned`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ id, host }),
  }).then(r => r.json());
}

export function caCert(cn) {
  return fetch(`/bo/api/certificates/_ca`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ cn }),
  }).then(r => r.json());
}

export function renewCert(id) {
  return fetch(`/bo/api/certificates/${id}/_renew`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: '',
  }).then(r => r.json());
}
