# Kubernetes Gateway API support

Starting from version 17.13.0, Otoroshi supports the [Kubernetes Gateway API](https://gateway-api.sigs.k8s.io/) specification (v1.4, `gateway.networking.k8s.io/v1`). This feature enables you to define routing rules using standard Gateway API resources (`GatewayClass`, `Gateway`, `HTTPRoute`) and have Otoroshi automatically convert them into native `NgRoute` entities.

@@@ warning
This feature is currently in **experimental** stage. It covers the core HTTPRoute use cases but does not yet implement the full specification. See the [current limitations](#current-limitations) section for details.
@@@

## How it works

Otoroshi implements the Gateway API using a **proxy-existing** approach: Otoroshi does not dynamically provision new listeners or ports based on Gateway resources. Instead, it validates that the `Gateway` listeners match Otoroshi's actual listening ports and uses hostnames, paths, and headers from `HTTPRoute` resources to generate `NgRoute` entities.

The reconciliation loop runs as a background job and works as follows:

1. **Fetch** all `GatewayClass`, `Gateway`, `HTTPRoute`, and `ReferenceGrant` resources from the Kubernetes API
2. **Reconcile GatewayClasses** — accept classes whose `controllerName` matches Otoroshi's configured controller name
3. **Reconcile Gateways** — validate that listener ports and protocols are compatible with Otoroshi's actual ports
4. **Convert HTTPRoutes** — for each rule in each HTTPRoute, generate one `NgRoute` with the appropriate frontend (domains, paths, headers), backend (targets resolved from Kubernetes Services), and plugins (from HTTPRoute filters)
5. **Save routes** — upsert generated routes and delete orphaned ones that are no longer defined

All generated routes are tagged with `otoroshi-provider: kubernetes-gateway-api` metadata, making them easy to identify and ensuring clean garbage collection.

## Prerequisites

* Otoroshi 17.13.0 or later, deployed on Kubernetes
* Gateway API CRDs installed (v1.4+ standard channel)
* RBAC permissions for the Otoroshi ServiceAccount to read Gateway API resources

### Installing Gateway API CRDs

Install the standard channel CRDs:

```sh
kubectl apply -f https://github.com/kubernetes-sigs/gateway-api/releases/download/v1.4.0/standard-install.yaml
```

### RBAC

The Otoroshi ServiceAccount needs the following additional ClusterRole rules for Gateway API resources:

```yaml
# Gateway API — read resources
- apiGroups: [gateway.networking.k8s.io]
  resources: [gatewayclasses, gateways, httproutes, referencegrants]
  verbs: [get, list, watch]
# Gateway API — update status subresources
- apiGroups: [gateway.networking.k8s.io]
  resources: [gatewayclasses/status, gateways/status, httproutes/status]
  verbs: [get, update, patch]
```

These rules must be added to the existing `otoroshi-admin-user` ClusterRole alongside the existing rules for core resources, ingresses, and Otoroshi CRDs.

## Enabling Gateway API support

Gateway API support is controlled through the `KubernetesConfig` configuration block. You need to:

1. Register the Gateway API controller job
2. Enable the `gatewayApi` flag in the Kubernetes configuration

### Configuration reference

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `gatewayApi` | boolean | `false` | Enable/disable the Gateway API controller |
| `gatewayApiControllerName` | string | `otoroshi.io/gateway-controller` | The controller name to match in GatewayClass resources |
| `gatewayApiHttpListenerPort` | int | `8080` | The actual HTTP port Otoroshi listens on |
| `gatewayApiHttpsListenerPort` | int | `8443` | The actual HTTPS port Otoroshi listens on |
| `gatewayApiSyncIntervalSeconds` | long | `60` | How often (in seconds) the controller reconciles |

### Using environment variable configuration

When deploying with `OTOROSHI_INITIAL_CUSTOMIZATION`, add the job reference and configuration:

```json
{
  "config": {
    "scripts": {
      "enabled": true,
      "jobRefs": [
        "cp:otoroshi.plugins.jobs.kubernetes.KubernetesGatewayApiControllerJob"
      ],
      "jobConfig": {
        "KubernetesConfig": {
          "trust": false,
          "namespaces": ["*"],
          "labels": {},
          "namespacesLabels": {},
          "defaultGroup": "default",
          "ingresses": false,
          "crds": false,
          "kubeLeader": false,
          "syncIntervalSeconds": 60,
          "otoroshiServiceName": "otoroshi-service",
          "otoroshiNamespace": "otoroshi",
          "clusterDomain": "cluster.local",
          "gatewayApi": true,
          "gatewayApiControllerName": "otoroshi.io/gateway-controller",
          "gatewayApiHttpListenerPort": 8080,
          "gatewayApiHttpsListenerPort": 8443,
          "gatewayApiSyncIntervalSeconds": 30
        }
      }
    }
  }
}
```

@@@ note
The Gateway API controller job can run alongside the existing CRDs controller job (`KubernetesOtoroshiCRDsControllerJob`). Both share the same `KubernetesConfig` block.
@@@

## Usage

### Step 1: Create a GatewayClass

The `GatewayClass` is a cluster-scoped resource that tells Kubernetes which controller handles Gateways of this class. The `controllerName` must match the value configured in `gatewayApiControllerName`.

```yaml
apiVersion: gateway.networking.k8s.io/v1
kind: GatewayClass
metadata:
  name: otoroshi
spec:
  controllerName: otoroshi.io/gateway-controller
```

Once Otoroshi detects this GatewayClass, it will set its status to `Accepted: True`.

### Step 2: Create a Gateway

The `Gateway` declares which listeners (port + protocol + hostname) should accept traffic. Since Otoroshi uses a proxy-existing approach, the `port` values must match the actual ports Otoroshi listens on (`gatewayApiHttpListenerPort` and `gatewayApiHttpsListenerPort`).

```yaml
apiVersion: gateway.networking.k8s.io/v1
kind: Gateway
metadata:
  name: my-gateway
  namespace: default
spec:
  gatewayClassName: otoroshi
  listeners:
  - name: http
    protocol: HTTP
    port: 8080
    hostname: "*.example.com"
    allowedRoutes:
      namespaces:
        from: Same
  - name: https
    protocol: HTTPS
    port: 8443
    hostname: "*.example.com"
    tls:
      mode: Terminate
      certificateRefs:
      - name: my-tls-secret
    allowedRoutes:
      namespaces:
        from: All
```

**Supported protocols**: `HTTP` and `HTTPS`. `TLS`, `TCP`, and `UDP` listeners are accepted in the manifest but will generate a `Detached` status condition as they are not yet implemented.

**Listener hostname**: acts as a filter. Only HTTPRoutes with matching hostnames will be attached to this listener. Wildcard hostnames (e.g. `*.example.com`) are supported.

**allowedRoutes.namespaces.from**: controls which namespaces can attach routes to this listener.

| Value | Behavior |
|-------|----------|
| `Same` (default) | Only routes in the same namespace as the Gateway |
| `All` | Routes from any namespace |
| `Selector` | Routes from namespaces matching a label selector (not yet implemented) |

### Step 3: Create an HTTPRoute

The `HTTPRoute` defines routing rules: which requests to match and where to send them.

```yaml
apiVersion: gateway.networking.k8s.io/v1
kind: HTTPRoute
metadata:
  name: my-route
  namespace: default
spec:
  parentRefs:
  - name: my-gateway
    sectionName: http
  hostnames:
  - "api.example.com"
  rules:
  - matches:
    - path:
        type: PathPrefix
        value: /v1
    backendRefs:
    - name: my-service
      port: 80
      weight: 1
```

#### Parent references

Each HTTPRoute declares one or more `parentRefs` pointing to a Gateway (and optionally a specific listener via `sectionName`). The effective hostnames for the generated route are the **intersection** of the listener's hostname and the route's hostnames. For example:

- Listener hostname: `*.example.com`
- Route hostnames: `api.example.com`, `web.example.com`
- Effective: `api.example.com`, `web.example.com` (both match the wildcard)

#### Path matching

| Type | Behavior | Example |
|------|----------|---------|
| `PathPrefix` (default) | Matches paths starting with the value | `/api` matches `/api`, `/api/users`, `/api/v2` |
| `Exact` | Matches the path exactly | `/api` matches only `/api` |

#### Backend references

Backend references point to Kubernetes `Service` resources. Otoroshi resolves each service to its `clusterIP` and uses the specified port. Multiple backends with different `weight` values enable traffic splitting.

```yaml
backendRefs:
- name: service-v1
  port: 80
  weight: 80
- name: service-v2
  port: 80
  weight: 20
```

This configuration sends 80% of traffic to `service-v1` and 20% to `service-v2`.

## Supported HTTPRoute filters

Filters allow modifying requests and responses as they pass through a route rule. The following filters are currently supported:

### RequestHeaderModifier

Add, set, or remove request headers:

```yaml
filters:
- type: RequestHeaderModifier
  requestHeaderModifier:
    set:
    - name: X-Custom-Header
      value: my-value
    add:
    - name: X-Additional
      value: extra-value
    remove:
    - X-Unwanted
```

### ResponseHeaderModifier

Add, set, or remove response headers:

```yaml
filters:
- type: ResponseHeaderModifier
  responseHeaderModifier:
    set:
    - name: X-Response-Header
      value: my-value
    remove:
    - X-Internal
```

### RequestRedirect

Redirect the client to a different URL:

```yaml
filters:
- type: RequestRedirect
  requestRedirect:
    scheme: https
    hostname: new.example.com
    port: 443
    statusCode: 301
```

All fields are optional. When omitted, the original request values are preserved using Otoroshi's expression language (`${req.host}`, `${req.uri}`, etc.).

### URLRewrite

Rewrite the request URL before forwarding to the backend:

```yaml
filters:
- type: URLRewrite
  urlRewrite:
    hostname: backend.internal.svc
    path:
      type: ReplacePrefixMatch
      replacePrefixMatch: /v2
```

- **hostname**: changes the `Host` header sent to the backend
- **path.type**: only `ReplacePrefixMatch` is currently supported. It strips the matched prefix and replaces it with the new value.

## Generated NgRoute structure

Each HTTPRoute rule generates one `NgRoute` in Otoroshi with a deterministic ID:

```
kubernetes-gateway-api-{namespace}-{routeName}-rule-{ruleIndex}
```

The generated route includes:

- **Frontend**: domains built from effective hostnames + path, with method matching if specified
- **Backend**: targets resolved from backendRefs using Kubernetes Service clusterIPs, with weighted load balancing
- **Plugins**: converted from HTTPRoute filters (header modifiers, redirections, etc.)
- **Metadata**: `otoroshi-provider: kubernetes-gateway-api`, plus `gateway-namespace`, `gateway-name`, `httproute-namespace`, `httproute-name` for traceability

## Status updates

The controller updates the `status` subresource on each Gateway API object:

- **GatewayClass**: `Accepted: True` when the `controllerName` matches, `Accepted: False` otherwise
- **Gateway**: `Accepted: True/False` based on gatewayClassName, `Programmed: True/False` per-listener based on port/protocol validation
- **HTTPRoute**: per-parent conditions `Accepted: True/False` and `ResolvedRefs: True` when the route is successfully converted

## Current limitations

The following features are **not yet implemented** in the current experiments:

| Feature | Status | Notes |
|---------|--------|-------|
| GRPCRoute | Not implemented | Planned for a future release |
| TLSRoute | Not implemented | Planned for a future release |
| TCPRoute / UDPRoute | Not implemented | Experimental in Gateway API spec |
| ReferenceGrant enforcement | Not enforced | Cross-namespace backend refs are allowed without validation. ReferenceGrant resources are fetched but not checked. This is a critical security feature planned for the next iteration. |
| Namespace label selector | Not implemented | `allowedRoutes.namespaces.from: Selector` is not yet supported |
| RequestMirror filter | Not implemented | Traffic mirroring is not yet available |
| ExtensionRef filter | Not implemented | Custom filter extensions |
| Header / query param matching | Not implemented | HTTPRoute `matches.headers` and `matches.queryParams` are parsed but not converted |
| Gateway addresses | Not implemented | The `spec.addresses` field is ignored |
| Listener TLS certificate binding | Not implemented | `tls.certificateRefs` are parsed but not bound to Otoroshi certificates |
| Dynamic listener provisioning | Not planned | Otoroshi uses a proxy-existing approach; ports must be pre-configured |

@@@ warning
**ReferenceGrant enforcement** is the most critical missing feature for production use. Without it, any HTTPRoute can reference backend Services in any namespace, bypassing Kubernetes namespace isolation. The implementation is designed to support this easily in a future release — the `ReferenceGrant` resources are already fetched and passed through the conversion pipeline.
@@@

## Troubleshooting

### Check resource status

The first thing to verify is the status of your Gateway API resources:

```sh
# GatewayClass should show Accepted: True
kubectl get gatewayclasses otoroshi -o yaml

# Gateway should show Accepted: True and listeners Programmed: True
kubectl get gateway my-gateway -n default -o yaml

# HTTPRoute should show Accepted: True for each parent
kubectl get httproute my-route -n default -o yaml
```

### Check generated routes

You can list all routes generated by the Gateway API controller using the Otoroshi admin API:

```sh
curl -s http://otoroshi-api.oto.tools:8080/api/routes \
  -u admin-api-apikey-id:admin-api-apikey-secret | \
  jq '.[] | select(.metadata["otoroshi-provider"] == "kubernetes-gateway-api") | {id, name}'
```

### Common issues

**Gateway shows `Accepted: False`**: the `gatewayClassName` does not reference an accepted GatewayClass, or the GatewayClass `controllerName` does not match the configured `gatewayApiControllerName`.

**Listener shows `Programmed: False`**: the listener `port` does not match `gatewayApiHttpListenerPort` (for HTTP) or `gatewayApiHttpsListenerPort` (for HTTPS). Remember that Otoroshi does not dynamically open new ports.

**HTTPRoute shows `Accepted: False`**: the parentRef does not match any Gateway/listener, or the listener's `allowedRoutes` does not permit routes from the HTTPRoute's namespace.

**Route is created but traffic returns 404**: verify that the backend Service exists and has a valid `clusterIP`. Check that the service port matches the `backendRef.port`. Also verify that the hostname used in the request matches the effective hostnames of the route.

### Check controller logs

Look for Gateway API related log entries in the Otoroshi pod:

```sh
kubectl -n otoroshi logs deploy/otoroshi | grep -i gateway
```
