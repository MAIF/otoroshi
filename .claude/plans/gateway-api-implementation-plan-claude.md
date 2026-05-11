# Implementation Plan: Kubernetes Gateway API in Otoroshi

> Reference: [User's plan](./gateway-api-implementation-plan.md)
> Status: **IN PROGRESS**

---

## Decisions (confirmed with user)

| # | Question | Decision |
|---|----------|----------|
| Q1 | URLRewrite hostname | Use `AdditionalHeadersIn` with `{"headers": {"Host": "new-hostname"}}` |
| Q2 | URLRewrite path | `stripPath=true` + `backend.root` for simple prefix; warning for complex cases |
| Q3 | NgRoute construction | Direct Scala case class construction (not JSON + parse) |
| Q4 | GRPCRoute | Skip for MVP. Add TODO list above Job class with all remaining work |
| Q5 | ReferenceGrant | Skip enforcement for MVP. **CRITICAL TODO**. Fetch entities, pass them through converter signatures, but don't enforce. Design for easy addition later |

---

## Pre-implementation findings

### Plugin config formats (verified from source code)

| Gateway API Filter | Otoroshi Plugin | Plugin ref | Config format |
|---|---|---|---|
| RequestHeaderModifier (set/add) | `AdditionalHeadersIn` | `cp:otoroshi.next.plugins.AdditionalHeadersIn` | `{"headers": {"Key": "Value"}}` |
| RequestHeaderModifier (remove) | `RemoveHeadersIn` | `cp:otoroshi.next.plugins.RemoveHeadersIn` | `{"header_names": ["name1"]}` |
| ResponseHeaderModifier (set/add) | `AdditionalHeadersOut` | `cp:otoroshi.next.plugins.AdditionalHeadersOut` | `{"headers": {"Key": "Value"}}` |
| ResponseHeaderModifier (remove) | `RemoveHeadersOut` | `cp:otoroshi.next.plugins.RemoveHeadersOut` | `{"header_names": ["name1"]}` |
| RequestRedirect | `Redirection` | `cp:otoroshi.next.plugins.Redirection` | `{"code": 302, "to": "https://..."}` |
| URLRewrite (hostname) | `AdditionalHeadersIn` | `cp:otoroshi.next.plugins.AdditionalHeadersIn` | `{"headers": {"Host": "new-hostname"}}` |

### Key model structures (verified)

- **NgRoute**: `location, id, name, description, tags, metadata, enabled, debugFlow, capture, exportReporting, groups, boundListeners, frontend, backend, backendRef, plugins`
- **NgFrontend**: `domains: Seq[NgDomainAndPath], headers, query, cookies, methods, stripPath, exact`
- **NgDomainAndPath**: wraps raw string like `"app.example.com/api"` — splits on `/` to get domain + path
- **NgTarget**: `id, hostname, port, tls, weight, protocol, predicate, ipAddress, tlsConfig, backup`
- **NgPluginInstance**: `plugin (string ref), enabled, debug, include, exclude, config: NgPluginInstanceConfig, boundListeners`
- **NgPlugins**: `slots: Seq[NgPluginInstance]`
- **NgRoute.save()**: `env.datastores.routeDataStore.set(this)`

---

## Step-by-step implementation plan

### Step 1: `gateway_entities.scala` — Gateway API entity case classes
- [ ] Create file `otoroshi/app/plugins/jobs/kubernetes/gateway_entities.scala`
- [ ] Implement `KubernetesGatewayClass(raw: JsValue) extends KubernetesEntity`
- [ ] Implement `GatewayListener(raw: JsValue)` (not a KubernetesEntity, sub-object)
- [ ] Implement `KubernetesGateway(raw: JsValue) extends KubernetesEntity`
- [ ] Implement `HTTPRouteParentRef(raw: JsValue)` (sub-object)
- [ ] Implement `HTTPRouteMatch(raw: JsValue)` (sub-object)
- [ ] Implement `HTTPRouteBackendRef(raw: JsValue)` (sub-object)
- [ ] Implement `HTTPRouteFilter(raw: JsValue)` (sub-object)
- [ ] Implement `HTTPRouteRule(raw: JsValue)` (sub-object)
- [ ] Implement `KubernetesHTTPRoute(raw: JsValue) extends KubernetesEntity`
- [ ] Implement `KubernetesReferenceGrant(raw: JsValue) extends KubernetesEntity`
- **Reference**: User's plan "Fichier 1", existing `entities.scala` for pattern
- **Validation**: Compiles, follows same lazy val extraction pattern as existing entities

### Step 2: `config.scala` — Add Gateway API config fields
- [ ] Add 5 fields to `KubernetesConfig` case class: `gatewayApi`, `gatewayApiControllerName`, `gatewayApiHttpListenerPort`, `gatewayApiHttpsListenerPort`, `gatewayApiSyncIntervalSeconds`
- [ ] Add parsing in `theConfig(conf: JsValue)` method
- [ ] Add to `defaultConfig` JSON
- [ ] Add to `configFlow` sequence (new section)
- [ ] Add to `configSchema` (field types and labels)
- [ ] Add to serialization (`json` method / `writes`)
- **Reference**: User's plan "Fichier 2"
- **Validation**: Compiles, default values work, existing tests pass

### Step 3: `client.scala` — Add fetch and status update methods
- [ ] Add `fetchGatewayClasses(): Future[Seq[KubernetesGatewayClass]]` — cluster-scoped (no namespace, `client(path, false)`)
- [ ] Add `fetchGateways(): Future[Seq[KubernetesGateway]]` — namespace-scoped, same pattern as `fetchIngresses`
- [ ] Add `fetchHTTPRoutes(): Future[Seq[KubernetesHTTPRoute]]` — namespace-scoped
- [ ] Add `fetchReferenceGrants(): Future[Seq[KubernetesReferenceGrant]]` — namespace-scoped
- [ ] Add `updateGatewayClassStatus(name, status)` — cluster-scoped, PATCH on `/status` sub-resource
- [ ] Add `updateGatewayStatus(namespace, name, status)` — namespace-scoped
- [ ] Add `updateHTTPRouteStatus(namespace, name, status)` — namespace-scoped
- **Reference**: User's plan "Fichier 3"
- **Key details**:
  - API group: `gateway.networking.k8s.io/v1`
  - GatewayClass path: `/apis/gateway.networking.k8s.io/v1/gatewayclasses` (NO namespace)
  - Gateway path: `/apis/gateway.networking.k8s.io/v1/namespaces/$ns/gateways`
  - HTTPRoute path: `/apis/gateway.networking.k8s.io/v1/namespaces/$ns/httproutes`
  - ReferenceGrant path: `/apis/gateway.networking.k8s.io/v1beta1/namespaces/$ns/referencegrants`
  - Status PATCH uses `application/merge-patch+json` content type
  - Follow same 200/403/404/else error handling pattern
- **Validation**: Compiles, follows exact same pattern as existing fetch methods

### Step 4: `gateway_converter.scala` — HTTPRoute to NgRoute conversion
- [ ] Create file `otoroshi/app/plugins/jobs/kubernetes/gateway_converter.scala`
- [ ] Implement `GatewayApiConverter` object with:
  - [ ] `httpRouteToNgRoutes(httpRoute, gateways, services, endpoints, referenceGrants, conf)(env, ec): Seq[NgRoute]`
    - **Note**: `referenceGrants` parameter is passed through but NOT enforced yet (MVP). Prepared for phase 2.
  - [ ] `resolveParentRefs(httpRoute, gateways, conf): Seq[(KubernetesGateway, GatewayListener)]`
  - [ ] `isListenerAcceptingRoute(listener, route, gateway): Boolean` — allowedRoutes namespace check
  - [ ] `resolveEffectiveHostnames(httpRoute, matchingGateways): Seq[String]` — hostname intersection
  - [ ] `hostnameMatches(listenerHostname, routeHostname): Boolean` — wildcard matching
  - [ ] `ruleToNgRoute(httpRoute, rule, ruleIdx, hostnames, services, endpoints, referenceGrants, conf)(env): Seq[NgRoute]`
  - [ ] `buildDomains(hostnames, rule): Seq[NgDomainAndPath]` — compose domain+path strings
  - [ ] `buildTargets(httpRoute, rule, services, endpoints, referenceGrants): Seq[NgTarget]`
    - **Note**: `referenceGrants` passed for future cross-namespace validation. For now, log warning on cross-namespace refs.
  - [ ] `isBackendRefAllowed(backendRef, httpRoute, referenceGrants): Boolean`
    - **MVP**: always returns `true` + logs warning if cross-namespace without grant. Placeholder for real enforcement.
  - [ ] `buildPlugins(rule): NgPlugins` — convert filters to plugin instances
- **Key design decisions**:
  - Construct NgRoute case class directly in Scala (not JSON + parse)
  - Plugin configs use verified formats (Map for headers, Seq for header_names)
  - For `RequestHeaderModifier`: generate TWO plugins if both set/add AND remove are present
  - For `ResponseHeaderModifier`: same pattern
  - For `URLRewrite` hostname: `AdditionalHeadersIn` with `{"headers": {"Host": "..."}}`
  - For `URLRewrite` path: `stripPath=true` + `backend.root` for prefix, warning for complex cases
  - Route ID pattern: `kubernetes-gateway-api-{namespace}-{name}-rule-{idx}`
  - Metadata: `otoroshi-provider -> "kubernetes-gateway-api"`, `kubernetes-name`, `kubernetes-namespace`, `kubernetes-path`, `kubernetes-uid`, `gateway-api-kind -> "HTTPRoute"`
- **Reference**: User's plan "Fichier 4"
- **Validation**: Unit testable with mock JsValues

### Step 5: `gateway.scala` — Job class and sync logic
- [ ] Create file `otoroshi/app/plugins/jobs/kubernetes/gateway.scala`
- [ ] Add **TODO list comment block** above the Job class listing all remaining work:
  - GRPCRoute support
  - TLSRoute support
  - TCPRoute / UDPRoute support
  - ReferenceGrant enforcement (CRITICAL — cross-namespace security)
  - Label selector matching for `allowedRoutes.namespaces.from: Selector`
  - BackendTLSPolicy support
  - Watch mode (event-driven sync instead of periodic polling)
  - Gateway addresses status (report Otoroshi's external IP/hostname)
  - Listener `attachedRoutes` count in Gateway status
  - `observedGeneration` tracking on all status conditions
  - TLS certificate resolution (certificateRefs → Otoroshi Cert)
  - URLRewrite `replaceFullPath` and complex `replacePrefixMatch`
  - RequestMirror filter support
  - ExtensionRef filter support
  - Conformance test suite
- [ ] Implement `KubernetesGatewayApiControllerJob extends Job`:
  - [ ] Standard job boilerplate (uniqueId, kind, starting, predicate, interval, etc.)
  - [ ] `predicate` checks `conf.gatewayApi == true`
  - [ ] `interval` returns `conf.gatewayApiSyncIntervalSeconds.seconds`
  - [ ] `jobRun` calls `KubernetesGatewayApiJob.syncGatewayApi(...)`
  - [ ] Same `instantiation` logic as `KubernetesOtoroshiCRDsControllerJob`
- [ ] Implement `object KubernetesGatewayApiJob`:
  - [ ] `val PROVIDER = "kubernetes-gateway-api"`
  - [ ] `syncGatewayApi(conf, attrs, jobRunning)(env, ec): Future[Unit]` — main sync flow:
    1. Fetch GatewayClasses, Gateways, HTTPRoutes, ReferenceGrants, Services, Endpoints, Secrets
    2. Fetch existing managed NgRoutes (filter by metadata `otoroshi-provider == PROVIDER`)
    3. `reconcileGatewayClasses` — accept ours, update status
    4. `reconcileGateways` — validate listeners, update status
    5. `reconcileHTTPRoutes` — convert + update status (pass `referenceGrants` through)
    6. `saveGeneratedRoutes` — save new/changed only
    7. `deleteOrphanedRoutes` — delete routes no longer in k8s
  - [ ] `conditionJson` helper — build status condition JSON object
- **Reference**: User's plan "Fichier 5"
- **Validation**: Compiles, job registers correctly, sync logic follows same patterns as crds.scala

---

## Implementation order

| Phase | Step | Files | Dependencies |
|---|---|---|---|
| 1 | Step 1 | `gateway_entities.scala` (new) | None |
| 1 | Step 2 | `config.scala` (modify) | None |
| 1 | Step 3 | `client.scala` (modify) | Step 1 (entity types) |
| 2 | Step 4 | `gateway_converter.scala` (new) | Steps 1-3 |
| 3 | Step 5 | `gateway.scala` (new) | Steps 1-4 |

Steps 1 and 2 can be done in parallel. Step 3 depends on Step 1. Step 4 depends on 1-3. Step 5 depends on all.

---

## ReferenceGrant — Future enforcement design

The implementation is designed so that adding ReferenceGrant enforcement later requires minimal changes:

1. **Entities**: `KubernetesReferenceGrant` already fetched and parsed (Step 1+3)
2. **Converter signatures**: `referenceGrants: Seq[KubernetesReferenceGrant]` is passed through all converter methods (Step 4)
3. **Placeholder method**: `isBackendRefAllowed(backendRef, httpRoute, referenceGrants)` exists and always returns `true` for MVP
4. **To enforce later**: Replace `isBackendRefAllowed` body with real logic:
   - Check if `backendRef.namespace != httpRoute.namespace` (cross-namespace)
   - If cross-namespace, look for a ReferenceGrant in `backendRef.namespace` that allows `from: [{group: gateway.networking.k8s.io, kind: HTTPRoute, namespace: httpRoute.namespace}]` → `to: [{group: "", kind: Service}]`
   - If no matching grant found, reject the backendRef and set status `ResolvedRefs=False, reason=RefNotPermitted`
5. **Same pattern applies to**: Gateway TLS certificateRefs (Secret cross-namespace references)

---

## Progress tracking

| Step | Status | Notes |
|---|---|---|
| Step 1: gateway_entities.scala | NOT STARTED | |
| Step 2: config.scala changes | NOT STARTED | |
| Step 3: client.scala changes | NOT STARTED | |
| Step 4: gateway_converter.scala | NOT STARTED | |
| Step 5: gateway.scala | NOT STARTED | |
