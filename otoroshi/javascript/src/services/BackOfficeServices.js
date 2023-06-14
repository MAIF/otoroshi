////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Should stay in BackOffice controller
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

export function syncWithLeader(config) {
  return fetch(`/bo/api/redis/sync`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(config),
  }).then((r) => r.json());
}

export function resetDB() {
  return fetch(`/bo/api/resetdb`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function fetchLine(lineId) {
  throw new Error('Deprecated API. Should not be used anymore !');
}

export function fetchBodiesFor(serviceId, requestId) {
  return fetch(`/bo/api/bodies/${serviceId}/${requestId}`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// should use api proxy
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

window.__otoroshi__env__latest = {
  currentTenant: 'default',
  userAdmin: false,
  bypassUserRightsCheck: false,
};

export function anonymousReporting(enabled) {
  return fetch('/bo/api/_anonymous_reporting', {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(enabled),
  });
}

export function env() {
  return fetch('/bo/api/env', {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  })
    .then((r) => r.json())
    .then((env) => {
      window.__otoroshi__env__latest = env;
      window.__currentTenant = env.currentTenant;
      window.__user.superAdmin = env.superAdmin;
      window.__user.tenantAdmin = env.tenantAdmin;
      return env;
    });
}

export function version() {
  return fetch('/bo/api/version', {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function fetchTunnels() {
  return fetch(`/bo/api/proxy/api/tunnels`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function fetchClusterMembers() {
  return fetch(`/bo/api/proxy/api/cluster/members`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function clearClusterMembers() {
  return fetch(`/bo/api/proxy/api/cluster/members`, {
    method: 'DELETE',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function fetchSnowMonkeyOutages() {
  return fetch(`/bo/api/proxy/api/snowmonkey/outages`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function fetchSnowMonkeyConfig() {
  return fetch(`/bo/api/proxy/api/snowmonkey/config`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
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
  }).then((r) => r.json());
}

export function startSnowMonkey() {
  return fetch(`/bo/api/proxy/api/snowmonkey/_start`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function stopSnowMonkey() {
  return fetch(`/bo/api/proxy/api/snowmonkey/_stop`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function fetchCanaryCampaign(serviceId) {
  return fetch(`/bo/api/proxy/api/services/${serviceId}/canary`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function resetCanaryCampaign(serviceId) {
  return fetch(`/bo/api/proxy/api/services/${serviceId}/canary`, {
    method: 'DELETE',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function allServices(env, group, paginationState) {
  const url = env
    ? `/bo/api/proxy/api/services?filter.env=${env}`
    : group
    ? `/bo/api/proxy/api/services?filter.groups=${group}`
    : `/bo/api/proxy/api/services`;
  return findAllWithPagination(url, paginationState);
}

export function fetchRemainingQuotas(groupId, clientId) {
  return fetch(`/bo/api/proxy/api/groups/${groupId}/apikeys/${clientId}/quotas`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function resetRemainingQuotas(groupId, clientId) {
  return fetch(`/bo/api/proxy/api/groups/${groupId}/apikeys/${clientId}/quotas`, {
    method: 'DELETE',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function fetchServiceEvents(serviceId, from, to, limit = 500, order = 'asc') {
  return fetch(
    `/bo/api/proxy/api/services/${serviceId}/events?from=${from.valueOf()}&to=${to.valueOf()}&pageSize=${limit}&order=${order}`,
    {
      method: 'GET',
      credentials: 'include',
      headers: {
        Accept: 'application/json',
      },
    }
  ).then((r) => r.json());
}

export function fetchGlobalEvents(from, to, limit = 500, order = 'asc') {
  return fetch(
    `/bo/api/proxy/api/events?from=${from.valueOf()}&to=${to.valueOf()}&pageSize=${limit}&order=${order}`,
    {
      method: 'GET',
      credentials: 'include',
      headers: {
        Accept: 'application/json',
      },
    }
  ).then((r) => r.json());
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
    (r) => {
      if (r.status === 200) {
        return r.json();
      }
      console.log('error while fetching global stats');
      return {};
    },
    (e) => {
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
    (r) => {
      if (r.status === 200) {
        return r.json();
      }
      console.log('error while fetching global stats');
      return {};
    },
    (e) => {
      console.log('error while fetching global stats');
      return {};
    }
  );
}

export function fetchGlobalStatus(page, limit) {
  return fetch(`/bo/api/proxy/api/status/global?&pageSize=${limit}&page=${page}`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then(
    (r) => {
      if (r.status === 200) {
        const count = r.headers.get('X-Count') || 0;
        return r.json().then((status) => ({ status, count }));
      } else {
        return r.text().then((e) => {
          console.log('bad status while fetching global stats', e);
          return { status: [], count: 0, error: 'bad status while fetching global stats' };
        });
      }
    },
    (e) => {
      console.log('error while fetching global stats', e);
      return { status: [], count: 0, error: 'error while fetching global stats' };
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
  })
    .then((r) => {
      if (r.status !== 200) {
        return [];
      } else {
        return r.json();
      }
    })
    .catch((e) => {
      console.log('error while fetching service status');
      return [];
    });
}

export function fetchServiceStatus(serviceId) {
  return fetch(`/bo/api/proxy/api/services/${serviceId}/status`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
  })
    .then((r) => {
      if (r.status !== 200) {
        return [];
      } else {
        return r.json();
      }
    })
    .catch((e) => {
      console.log('error while fetching service status');
      return [];
    });
}

export function fetchServicesStatus(servicesIds = []) {
  return fetch(`/bo/api/proxy/api/status`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(servicesIds),
  }).then((r) => r.json());
}

export function fetchServiceResponseTime(serviceId) {
  return fetch(`/bo/api/proxy/api/services/${serviceId}/response`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  })
    .then((r) => {
      if (r.status !== 200) {
        return [];
      } else {
        return r.json();
      }
    })
    .catch((e) => {
      console.log('error while fetching service response time');
      return [];
    });
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
      (r) => r.json(),
      (e) => {
        console.log(e);
        return ['prod'];
      }
    )
    .then(
      (r) => r,
      (e) => {
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
  }).then((r) => r.json());
}

export function fetchApiKeysForPage(serviceId) {
  return fetch(`/bo/api/apikeys-for/${serviceId}`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function fetchAllApikeys(paginationState) {
  return findAllWithPagination('/bo/api/proxy/api/apikeys', paginationState);
}

export function fetchApiKeyById(serviceId, apkid) {
  return fetch(`/bo/api/proxy/api/services/${serviceId}/apikeys/${apkid}`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function deleteApiKey(serviceId, routeId, ak) {
  const url = serviceId
    ? `/bo/api/proxy/api/services/${serviceId}/apikeys/${ak.clientId}`
    : `/bo/api/proxy/api/routes/${routeId}/apikeys/${ak.clientId}`;
  return fetch(url, {
    method: 'DELETE',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function createApiKey(serviceId, routeId, ak) {
  const url = serviceId
    ? `/bo/api/proxy/api/services/${serviceId}/apikeys`
    : `/bo/api/proxy/api/routes/${routeId}/apikeys`;
  return fetch(url, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(ak),
  }).then((r) => r.json());
}

export function updateApiKey(serviceId, routeId, ak) {
  const url = serviceId
    ? `/bo/api/proxy/api/services/${serviceId}/apikeys/${ak.clientId}`
    : `/bo/api/proxy/api/routes/${routeId}/apikeys/${ak.clientId}`;
  return fetch(url, {
    method: 'PUT',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(ak),
  }).then((r) => r.json());
}

export function updateRawApiKey(ak) {
  return fetch(`/bo/api/proxy/api/apikeys/${ak.clientId}`, {
    method: 'PUT',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(ak),
  }).then((r) => r.json());
}

export function fetchStandaloneApiKey(clientId) {
  return fetch(`/bo/api/proxy/api/apikeys/${clientId}`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function deleteStandaloneApiKey(ak) {
  return fetch(`/bo/api/proxy/api/apikeys/${ak.clientId}`, {
    method: 'DELETE',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function createStandaloneApiKey(ak) {
  return fetch(`/bo/api/proxy/api/apikeys`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(ak),
  }).then((r) => r.json());
}

export function updateStandaloneApiKey(ak) {
  return fetch(`/bo/api/proxy/api/apikeys/${ak.clientId}`, {
    method: 'PUT',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(ak),
  }).then((r) => r.json());
}

export function getGlobalConfig(fields) {
  let url = `/bo/api/proxy/api/globalconfig`;

  if (fields) url = `${url}?fields=${fields.join(',')}`;

  return fetch(url, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
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
  }).then((r) => r.json());
}

export function fetchService(lineId, serviceId) {
  return fetch(`/bo/api/proxy/api/services/${serviceId}`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function findServicesForGroup(group) {
  return fetch(`/bo/api/proxy/api/groups/${group.id}/services`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function findAllGroups(paginationState) {
  return findAllWithPagination('/bo/api/proxy/api/groups', paginationState);
  // return fetch('/bo/api/proxy/api/groups', {
  //   method: 'GET',
  //   credentials: 'include',
  //   headers: {
  //     Accept: 'application/json',
  //   },
  // }).then((r) => r.json());
}

export function findGroupById(id) {
  return fetch(`/bo/api/proxy/api/groups/${id}`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function deleteGroup(ak) {
  return fetch(`/bo/api/proxy/api/groups/${ak.id}`, {
    method: 'DELETE',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
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
  }).then((r) => r.json());
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
  }).then((r) => r.json());
}

export function deleteService(service) {
  return fetch(`/bo/api/proxy/api/services/${service.id}`, {
    method: 'DELETE',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function createNewService() {
  return fetch(`/bo/api/proxy/api/new/service`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
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
  }).then((r) => r.json());
}

export function updateService(serviceId, service) {
  delete service.groupId;
  return fetch(`/bo/api/proxy/api/services/${serviceId}`, {
    method: 'PUT',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(service),
  }).then((r) => r.json());
}

export function updateRawService(service) {
  delete service.groupId;
  return fetch(`/bo/api/proxy/api/services/${service.id}`, {
    method: 'PUT',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(service),
  }).then((r) => r.json());
}

export function findAllApps() {
  return fetch(`/bo/api/apps`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function findAllEurekaServers() {
  return fetch('/bo/api/eureka-servers', {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function getEurekaApps(eurekaServerId) {
  return fetch(`/bo/api/eureka-servers/${eurekaServerId}/apps`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function getExternalEurekaServers(url) {
  return fetch(`/bo/api/external-eureka-servers?url=${url}`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function discardAllSessions() {
  // return fetch(`/bo/api/sessions`, {
  return fetch(`/bo/api/proxy/api/admin-sessions`, {
    method: 'DELETE',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function discardSession(id) {
  // return fetch(`/bo/api/sessions/${id}`, {
  return fetch(`/bo/api/proxy/api/admin-sessions/${id}`, {
    method: 'DELETE',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function fetchSessions(paginateState) {
  // return fetch(`/bo/api/sessions`, {
  return findAllWithPagination(`/bo/api/proxy/api/admin-sessions`, paginateState);
}

export function discardAllPrivateAppsSessions() {
  // return fetch(`/bo/api/papps/sessions`, {
  return fetch(`/bo/api/proxy/api/apps-sessions`, {
    method: 'DELETE',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function discardPrivateAppsSession(id) {
  // return fetch(`/bo/api/papps/sessions/${id}`, {
  return fetch(`/bo/api/proxy/api/apps-sessions/${id}`, {
    method: 'DELETE',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function fetchPrivateAppsSessions(paginationState) {
  // return fetch(`/bo/api/papps/sessions`, {
  return findAllWithPagination(`/bo/api/proxy/api/apps-sessions`, paginationState);
}

export function panicMode() {
  return fetch(`/bo/api/panic`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
    body: '{}',
  }).then((r) => r.json());
}

export function fetchAdmins(paginationState) {
  // return fetch(`/bo/simple/admins`, {
  return findAllWithPagination(`/bo/api/proxy/api/admins/simple`, paginationState).then(
    (_admins) => {
      // return fetch(`/bo/webauthn/admins`, {
      return fetch(`/bo/api/proxy/api/admins/webauthn`, {
        method: 'GET',
        credentials: 'include',
        headers: {
          Accept: 'application/json',
        },
      })
        .then((r) => r.json())
        .then((_webauthnadmins) => {
          const admins = _admins.data.map((admin) => ({ ...admin, type: 'SIMPLE' }));
          const webauthnadmins = _webauthnadmins.map((admin) => ({ ...admin, type: 'WEBAUTHN' }));
          return [...webauthnadmins, ...admins];
        });
    }
  );
}

export function discardAdmin(username, id, type) {
  if (type === 'SIMPLE') {
    // return fetch(`/bo/simple/admins/${username}`, {
    return fetch(`/bo/api/proxy/api/admins/simple/${username}`, {
      method: 'DELETE',
      credentials: 'include',
      headers: {
        Accept: 'application/json',
      },
    }).then((r) => r.json());
  } else if (type === 'WEBAUTHN') {
    //return fetch(`/bo/webauthn/admins/${username}/${id}`, {
    return fetch(`/bo/api/proxy/api/admins/webauthn/${username}/${id}`, {
      method: 'DELETE',
      credentials: 'include',
      headers: {
        Accept: 'application/json',
      },
    }).then((r) => r.json());
  } else {
    // nothing
    return;
  }
}

export function fetchOtoroshi(ctype) {
  return fetch(`/bo/api/proxy/api/otoroshi.json`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: ctype || 'application/json',
    },
  }).then((r) => (ctype === 'application/json' ? r.json() : r.text()));
}

export function fetchAuditEvents() {
  return fetch(`/bo/api/events/audit`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function fetchAlertEvents() {
  return fetch(`/bo/api/events/alert`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function fetchLoggers() {
  return fetch(`/bo/api/loggers`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function changeLogLevel(name, level) {
  return fetch(`/bo/api/loggers/${name}/level?newLevel=${level}`, {
    method: 'PUT',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
    body: '{}',
  }).then((r) => r.json());
}

export function fetchTop10() {
  return fetch(`/bo/api/services/top10`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function fetchServicesMap() {
  return fetch(`/bo/api/services/map`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function fetchServicesTree() {
  return new Promise((s) =>
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
  }).then((r) => r.json());
}

export function findTemplateById(id) {
  return fetch(`/bo/api/proxy/api/services/${id}/template`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => (r.status === 404 ? null : r.json()));
}

export function deleteTemplate(ak) {
  return fetch(`/bo/api/proxy/api/services/${ak.serviceId}/template`, {
    method: 'DELETE',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
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
  }).then((r) => r.json());
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
  }).then((r) => r.json());
}

export function findAllJwtVerifiers(paginationState) {
  return findAllWithPagination('/bo/api/proxy/api/verifiers', paginationState);
}

export function findAllEvents(paginationState, serviceId, from, to, limit = 500, order = 'asc') {
  function getValueAtPath(path, obj) {
    return path.split('.').reduce((acc, key) => {
      if (acc[key]) return acc[key];
      return {};
    }, obj);
  }

  return findAllWithPagination(
    `/bo/api/proxy/api/services/${serviceId}/events?from=${from.valueOf()}&to=${to.valueOf()}&pageSize=${limit}&order=${order}`,
    paginationState
  ).then((events) => {
    const filters = (paginationState.filtered || []).map((field) => [field.id, field.value]);
    const hasFilters = filters.length > 0;

    if (events.data && hasFilters) {
      return {
        ...events,
        data: events.data.filter((event) => {
          return filters.every(([filterKey, filterValue]) => {
            return ('' + getValueAtPath(filterKey, event)).includes(filterValue + '');
          });
        }),
      };
    } else return events;
  });
}

export function findJwtVerifierById(id) {
  return fetch(`/bo/api/proxy/api/verifiers/${id}`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function deleteJwtVerifier(ak) {
  return fetch(`/bo/api/proxy/api/verifiers/${ak.id}`, {
    method: 'DELETE',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
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
  }).then((r) => r.json());
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
  }).then((r) => r.json());
}

export function findAllAuthConfigs(paginationState) {
  return findAllWithPagination('/bo/api/proxy/api/auths', paginationState);
}

export function findAuthConfigById(id) {
  return fetch(`/bo/api/proxy/api/auths/${id}`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function deleteAuthConfig(ak) {
  return fetch(`/bo/api/proxy/api/auths/${ak.id}`, {
    method: 'DELETE',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
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
  }).then((r) => r.json());
}

export function getAuthModuleTypes() {
  return fetch('/bo/api/proxy/api/auths/templates', {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function createNewAuthConfig(kind) {
  return fetch(`/bo/api/proxy/api/auths/_template?mod-type=${kind}`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
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
  }).then((r) => r.json());
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

export function findAllCertificates(paginationState) {
  return findAllWithPagination('/bo/api/proxy/api/certificates', paginationState);
}

export function findCertificateById(id) {
  return fetch(`/bo/api/proxy/api/certificates/${id}`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function deleteCertificate(ak) {
  return fetch(`/bo/api/proxy/api/certificates/${ak.id}`, {
    method: 'DELETE',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
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
  }).then((r) => r.json());
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
  }).then((r) => r.json());
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
  }).then((r) => r.json());
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
  }).then((r) => r.json());
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
  }).then((r) => r.json());
}

export function selfSignedClientCert(dn) {
  return fetch(`/bo/api/certificates/_selfSignedClient`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ dn }),
  }).then((r) => r.json());
}

export function importP12(password, content, client) {
  return fetch(`/bo/api/certificates/_importP12?password=${password}&client=${client}&many=false`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/octet-stream',
    },
    body: content,
  }).then((r) => r.json());
}

export function letsEncryptCert(host) {
  return fetch(`/bo/api/certificates/_letsencrypt`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ host }),
  }).then((r) => r.json());
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
  }).then((r) => r.json());
}

export function caSignedClientCert(id, dn) {
  return fetch(`/bo/api/certificates/_caSignedClient`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ id, dn }),
  }).then((r) => r.json());
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
  }).then((r) => r.json());
}

export function createCertificateFromForm(form) {
  return fetch(`/bo/api/certificates/_createCertificate`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(form),
  }).then((r) => r.json());
}

export function createCSR(form) {
  return fetch(`/bo/api/certificates/_createCSR`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(form),
  }).then((r) => r.json());
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
  }).then((r) => r.json());
}

export function findAllClientValidators() {
  return fetch('/bo/api/proxy/api/client-validators', {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function findClientValidatorById(id) {
  return fetch(`/bo/api/proxy/api/client-validators/${id}`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function deleteClientValidator(ak) {
  return fetch(`/bo/api/proxy/api/client-validators/${ak.id}`, {
    method: 'DELETE',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function createClientValidator(ak) {
  return fetch(`/bo/api/proxy/api/client-validators`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(ak),
  }).then((r) => r.json());
}

export function updateClientValidator(ak) {
  return fetch(`/bo/api/proxy/api/client-validators/${ak.id}`, {
    method: 'PUT',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(ak),
  }).then((r) => r.json());
}

export function findAllScripts() {
  return fetch('/bo/api/proxy/api/scripts', {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function findScriptById(id) {
  return fetch(`/bo/api/proxy/api/scripts/${id}`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function deleteScript(ak) {
  return fetch(`/bo/api/proxy/api/scripts/${ak.id}`, {
    method: 'DELETE',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function createScript(ak) {
  return fetch(`/bo/api/proxy/api/scripts`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(ak),
  }).then((r) => r.json());
}

export function compileScript(ak) {
  return fetch(`/bo/api/proxy/api/scripts/_compile`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(ak),
  }).then((r) => r.json());
}

export function updateScript(ak) {
  return fetch(`/bo/api/proxy/api/scripts/${ak.id}`, {
    method: 'PUT',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(ak),
  }).then((r) => r.json());
}

export function fetchStats(by, id, from, to, limit = 500) {
  return fetch(
    `/bo/api/proxy/api/stats?${by}=${id}&from=${from.valueOf()}&to=${to.valueOf()}&pageSize=${limit}`,
    {
      method: 'GET',
      credentials: 'include',
      headers: {
        Accept: 'application/json',
      },
    }
  ).then((r) => r.json());
}

export function findAllTcpServices(paginationState) {
  return findAllWithPagination('/bo/api/proxy/api/tcp/services', paginationState);
}

export function findTcpServiceById(id) {
  return fetch(`/bo/api/proxy/api/tcp/services/${id}`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function deleteTcpService(ak) {
  return fetch(`/bo/api/proxy/api/tcp/services/${ak.id}`, {
    method: 'DELETE',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function createTcpService(ak) {
  return fetch(`/bo/api/proxy/api/tcp/services`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(ak),
  }).then((r) => r.json());
}

export function createNewTcpService() {
  return fetch(`/bo/api/proxy/api/new/tcp/service `, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function updateTcpService(ak) {
  return fetch(`/bo/api/proxy/api/tcp/services/${ak.id}`, {
    method: 'PUT',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(ak),
  }).then((r) => r.json());
}

export function findAllRoutesWithPagination(ps) {
  return findAllWithPagination('/bo/api/proxy/api/routes', ps);
}

export function findAllRouteCompositionsWithPagination(ps) {
  return findAllWithPagination('/bo/api/proxy/api/route-compositions', ps);
}

export function findAllServicesWithPagination(ps) {
  return findAllWithPagination('/bo/api/proxy/api/services', ps);
}

///////////////////////////////
// Error Teampltes
///////////////////////////////

export function findAllErrorTemplatesWithPagination(ps) {
  return findAllWithPagination('/bo/api/proxy/api/error-templates', ps);
}

export function findAllErrorTemplates() {
  return fetch('/bo/api/proxy/api/error-templates', {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function findErrorTemplateById(id) {
  return fetch(`/bo/api/proxy/api/error-templates/${id}`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function deleteErrorTemplate(ak) {
  return fetch(`/bo/api/proxy/api/error-templates/${ak.serviceId}`, {
    method: 'DELETE',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function createErrorTemplate(ak) {
  return fetch(`/bo/api/proxy/api/error-templates`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(ak),
  }).then((r) => r.json());
}

export function updateErrorTemplate(ak) {
  return fetch(`/bo/api/proxy/api/error-templates/${ak.id}`, {
    method: 'PUT',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(ak),
  }).then((r) => r.json());
}

///////////////////////////////
// Teams
///////////////////////////////

export function findAllTeamsWithPagination(ps) {
  return findAllWithPagination('/bo/api/proxy/api/teams', ps);
}

export function findAllTeams() {
  return fetch('/bo/api/proxy/api/teams', {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function findTeamById(id) {
  return fetch(`/bo/api/proxy/api/teams/${id}`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function deleteTeam(ak) {
  return fetch(`/bo/api/proxy/api/teams/${ak.id}`, {
    method: 'DELETE',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function createTeam(ak) {
  return fetch(`/bo/api/proxy/api/teams`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(ak),
  }).then((r) => r.json());
}

export function createNewTeam() {
  return fetch(`/bo/api/proxy/api/teams/_template`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function updateTeam(ak) {
  return fetch(`/bo/api/proxy/api/teams/${ak.id}`, {
    method: 'PUT',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(ak),
  }).then((r) => r.json());
}

///////////////////////////////
// Wasm Plugins
///////////////////////////////

export function findAllWasmPluginsWithPagination(ps) {
  return findAllWithPagination('/bo/api/proxy/apis/plugins.otoroshi.io/v1/wasm-plugins', ps);
}

export function findAllWasmPlugins() {
  return fetch('/bo/api/proxy/apis/plugins.otoroshi.io/v1/wasm-plugins', {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function findWasmPluginById(id) {
  return fetch(`/bo/api/proxy/apis/plugins.otoroshi.io/v1/wasm-plugins/${id}`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function deleteWasmPlugin(ak) {
  return fetch(`/bo/api/proxy/apis/plugins.otoroshi.io/v1/wasm-plugins/${ak.id}`, {
    method: 'DELETE',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function createWasmPlugin(ak) {
  return fetch(`/bo/api/proxy/apis/plugins.otoroshi.io/v1/wasm-plugins`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(ak),
  }).then((r) => r.json());
}

export function createNewWasmPlugin() {
  return fetch(`/bo/api/proxy/apis/plugins.otoroshi.io/v1/wasm-plugins/_template`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function updateWasmPlugin(ak) {
  return fetch(`/bo/api/proxy/apis/plugins.otoroshi.io/v1/wasm-plugins/${ak.id}`, {
    method: 'PUT',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(ak),
  }).then((r) => r.json());
}

///////////////////////////////
// Tenants
///////////////////////////////

export function findAllTenantsWithPagination(ps) {
  return findAllWithPagination('/bo/api/proxy/api/tenants', ps);
}

export function findAllTenants() {
  return fetch('/bo/api/proxy/api/tenants', {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function findTenantById(id) {
  return fetch(`/bo/api/proxy/api/tenants/${id}`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function deleteTenant(ak) {
  return fetch(`/bo/api/proxy/api/tenants/${ak.id}`, {
    method: 'DELETE',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function createTenant(ak) {
  return fetch(`/bo/api/proxy/api/tenants`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(ak),
  }).then((r) => r.json());
}

export function createNewTenant() {
  return fetch(`/bo/api/proxy/api/tenants/_template`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function updateTenant(ak) {
  return fetch(`/bo/api/proxy/api/tenants/${ak.id}`, {
    method: 'PUT',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(ak),
  }).then((r) => r.json());
}

export function updateSimpleAdmin(user) {
  return fetch(`/bo/api/proxy/api/admins/simple/${user.username}`, {
    method: 'PUT',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(user),
  }).then((r) => r.json());
}

export function updateWebAuthnAdmin(user) {
  return fetch(`/bo/api/proxy/api/admins/webauthn/${user.username}`, {
    method: 'PUT',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(user),
  }).then((r) => r.json());
}

///////////////////////////////
// DATA EXPORTERS
///////////////////////////////

export function createNewDataExporterConfig(type) {
  return fetch(`/bo/api/proxy/api/data-exporter-configs/_template?type=${type}`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function findAllDataExporterConfigs(paginationState) {
  return findAllWithPagination('/bo/api/proxy/api/data-exporter-configs', paginationState);
}

export function findDataExporterConfigById(id) {
  return fetch(`/bo/api/proxy/api/data-exporter-configs/${id}`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function deleteDataExporterConfig(ak) {
  return fetch(`/bo/api/proxy/api/data-exporter-configs/${ak.id}`, {
    method: 'DELETE',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function createDataExporterConfig(ak) {
  return fetch(`/bo/api/proxy/api/data-exporter-configs`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(ak),
  }).then((r) => r.json());
}

export function updateDataExporterConfig(ak) {
  return fetch(`/bo/api/proxy/api/data-exporter-configs/${ak.id}`, {
    method: 'PUT',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(ak),
  }).then((r) => r.json());
}

/////// Templates

export function createNewJwtVerifier() {
  return fetch(`/bo/api/proxy/api/verifiers/_template`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function createNewCertificate() {
  return fetch(`/bo/api/proxy/api/certificates/_template`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function createNewGroup() {
  return fetch(`/bo/api/proxy/api/groups/_template`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function createNewScript() {
  return fetch(`/bo/api/proxy/api/scripts/_template`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function createNewApikey() {
  return fetch(`/bo/api/proxy/api/apikeys/_template`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());
}

export function createResources(resources) {
  return fetch(`/bo/api/proxy/api/new/resources`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      content: ['[', '{'].includes(resources.substring(0, 1)) ? JSON.parse(resources) : resources,
    }),
  }).then((r) => r.json());
}

export function tryIt(content, entity) {
  return fetch(`/bo/api/tryit?entity=${entity}`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(content),
  });
}

export function dataExportertryIt(content) {
  return fetch('/bo/api/data-exporter/tryit', {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(content),
  });
}

export function routeEntries(routeId) {
  return fetch(`/bo/api/routes/${routeId}/entries`).then((r) => r.json());
}

export function graphQLTryIt(url) {
  return fetch(`/bo/api/graphqlproxy?url=${url}`).then((r) => r.json());
}

export function graphqlSchemaToJson(schema) {
  return fetch(`/bo/api/graphql_to_json`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      schema,
    }),
  }).then((r) => r.json());
}

export function jsonToGraphqlSchema(schema, types) {
  return fetch(`/bo/api/json_to_graphql_schema`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      schema,
      types,
    }),
  }).then((r) => r.json());
}
// NgRoutes

const fetchWrapper = (url, method = 'GET', body) =>
  fetch(`/bo/api/proxy/api${url}`, {
    method,
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: body ? JSON.stringify(body) : undefined,
  }).then((r) => r.json());

export const findAllWithPagination = (
  route,
  { page, pageSize, fields, filtered, sorted, ...props } = { page: 1 },
  prefix = ''
) => {
  let url = route;

  // console.log(props)

  if (page) {
    url = `${url}?page=${page}`;

    if (pageSize) url = `${url}&pageSize=${pageSize}`;

    if (fields && fields.length > 0) url = `${url}&fields=${fields.join(',')}`;

    if (filtered && filtered.length > 0)
      url = `${url}&filtered=${filtered.map((field) => `${field.id}:${field.value}`).join(',')}`;

    if (sorted && sorted.length > 0)
      url = `${url}&sorted=${sorted.map((field) => `${field.id}:${field.desc}`).join(',')}`;
  }

  return fetch(`${prefix}${url}`, {
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((res) => {
    const { headers } = res;
    const xOffset = ~~headers.get('X-Offset');
    const xCount = ~~headers.get('X-Count');
    const xPageSize = ~~headers.get('X-Page-Size');
    return res.json().then((rows) => ({
      data: rows,
      pages: Math.ceil(xCount / xPageSize),
      offset: xOffset,
    }));
  });
};

export const nextClient = {
  ENTITIES: {
    ROUTES: 'routes',
    BACKENDS: 'backends',
    FRONTENDS: 'frontends',
    SERVICES: 'route-compositions',
  },
  find: (entity) => fetchWrapper(`/${entity}`),
  findAll: (entity, { page, pageSize, sorted, filtered } = { page: 1 }) => {
    let url = `/${entity}?page=${page}`;
    if (pageSize) url = `${url}&pageSize=${pageSize}`;

    return fetchWrapper(url);
  },
  findAllWithPagination: (entity, props) =>
    findAllWithPagination(`/${entity}`, props, '/bo/api/proxy/api/'),
  create: (entity, content) => fetchWrapper(`/${entity}`, 'POST', content),
  update: (entity, content) => fetchWrapper(`/${entity}/${content.id}`, 'PUT', content),
  fetch: (entity, entityId) => fetchWrapper(`/${entity}/${entityId}`),
  findById: (entity, entityId) => fetchWrapper(`/${entity}/${entityId}`),
  remove: (entity, content) => fetchWrapper(`/${entity}/${content.id}`, 'DELETE'),
  deleteById: (entity, id) => fetchWrapper(`/${entity}/${id}`, 'DELETE'),
  template: (entity) => fetchWrapper(`/${entity}/_template`),
  form: (entity) => fetchWrapper(`/${entity}/_form`),
  forEntity: (entity) => {
    return {
      findAll: () => fetchWrapper(`/${entity}`),
      findAllWithPagination: (paginationState) =>
        findAllWithPagination(entity, paginationState, '/bo/api/proxy/api/'),
      create: (content) => fetchWrapper(`/${entity}`, 'POST', content),
      update: (content) => fetchWrapper(`/${entity}/${content.id}`, 'PUT', content),
      findById: (entityId) => fetchWrapper(`/${entity}/${entityId}`),
      delete: (content) => fetchWrapper(`/${entity}/${content.id}`, 'DELETE'),
      deleteById: (id) => fetchWrapper(`/${entity}/${id}`, 'DELETE'),
      template: () => fetchWrapper(`/${entity}/_template`),
      form: () => fetchWrapper(`/${entity}/_form`),
    };
  },
};

export const getPlugins = () => fetchWrapper('/plugins/all');

export const getOldPlugins = () =>
  fetch('/bo/api/proxy/api/scripts/_list', {
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
  }).then((r) => r.json());

export const getCategories = () => fetchWrapper('/plugins/categories');

export const convertAsRoute = (id) =>
  fetch(`/bo/api/proxy/api/services/${id}/route`, {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'application/json',
    },
  }).then((r) => r.json());

export const getEntityGraph = (entity, id) => fetchWrapper(`/entities/${entity}/${id}`);

export function apisClient(group, version, pluralName) {
  return {
    findAll: () => fetchWrapper(`s/${group}/${version}/${pluralName}`),
    findAllWithPagination: (paginationState) =>
      findAllWithPagination(pluralName, paginationState, `/bo/api/proxy/apis/${group}/${version}/`),
    create: (content) => fetchWrapper(`s/${group}/${version}/${pluralName}`, 'POST', content),
    update: (content) =>
      fetchWrapper(`s/${group}/${version}/${pluralName}/${content.id}`, 'PUT', content),
    findById: (entityId) => fetchWrapper(`s/${group}/${version}/${pluralName}/${entityId}`),
    delete: (content) =>
      fetchWrapper(`s/${group}/${version}/${pluralName}/${content.id}`, 'DELETE'),
    deleteById: (id) => fetchWrapper(`s/${group}/${version}/${pluralName}/${id}`, 'DELETE'),
    template: () => fetchWrapper(`s/${group}/${version}/${pluralName}/_template`),
    form: () => fetchWrapper(`s/${group}/${version}/${pluralName}/_form`),
  };
}
