# Kubernetes Gateway API support

Starting from version 17.13.0, Otoroshi supports the [Kubernetes Gateway API](https://gateway-api.sigs.k8s.io/) specification (v1.4, `gateway.networking.k8s.io/v1`). This feature enables you to define routing rules using standard Gateway API resources (`GatewayClass`, `Gateway`, `HTTPRoute`, `GRPCRoute`) and have Otoroshi automatically convert them into native `NgRoute` entities.

@@@ warning
This feature is currently in **experimental** stage. It covers the core HTTPRoute use cases but does not yet implement the full specification. See the [current limitations](#current-limitations) section for details.
@@@

## How it works

Otoroshi implements the Gateway API using a **proxy-existing** approach: Otoroshi does not dynamically provision new listeners or ports based on Gateway resources. Instead, it validates that the `Gateway` listeners match Otoroshi's actual listening ports and uses hostnames, paths, and headers from `HTTPRoute` and `GRPCRoute` resources to generate `NgRoute` entities.

The reconciliation loop runs as a background job and works as follows:

1. **Fetch** all `GatewayClass`, `Gateway`, `HTTPRoute`, `GRPCRoute`, `ReferenceGrant`, and `BackendTLSPolicy` resources from the Kubernetes API
2. **Reconcile GatewayClasses** — accept classes whose `controllerName` matches Otoroshi's configured controller name
3. **Resolve TLS certificates** — for HTTPS listeners with `certificateRefs`, check if the referenced TLS certificates are already in Otoroshi's cert store and import them from Kubernetes Secrets if needed
4. **Reconcile Gateways** — validate that listener ports and protocols are compatible with Otoroshi's actual ports, and verify that TLS certificate references are resolved
5. **Resolve BackendTLS CA certificates** — for each `BackendTLSPolicy` with `caCertificateRefs`, import the referenced CA certificates into Otoroshi's certificate store
6. **Convert HTTPRoutes** — for each rule in each HTTPRoute, generate one `NgRoute` with the appropriate frontend (domains, paths, headers), backend (targets resolved from Kubernetes Services with ReferenceGrant enforcement and BackendTLSPolicy-based TLS configuration), and plugins (from HTTPRoute filters)
7. **Convert GRPCRoutes** — same as HTTPRoute but with gRPC method matching mapped to HTTP/2 paths (`/{service}/{method}`) and backend targets using HTTP/2 protocol
8. **Save routes** — upsert generated routes and delete orphaned ones that are no longer defined

All generated routes are tagged with `otoroshi-provider: kubernetes-gateway-api` metadata, making them easy to identify and ensuring clean garbage collection.

### Watch mode

When `watch` is enabled in the Kubernetes configuration, the Gateway API controller uses Kubernetes watch events to trigger synchronization in near-real-time instead of waiting for the next polling interval. This covers all Gateway API resources (`GatewayClass`, `Gateway`, `HTTPRoute`, `GRPCRoute`, `ReferenceGrant`, `BackendTLSPolicy`) as well as related Kubernetes resources (`Secret`, `Service`, `Endpoints`). The `watchGracePeriodSeconds` setting prevents excessive syncs by enforcing a minimum delay between consecutive event-driven reconciliations.

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
  resources: [gatewayclasses, gateways, httproutes, grpcroutes, referencegrants, backendtlspolicies]
  verbs: [get, list, watch]
# Gateway API — update status subresources
- apiGroups: [gateway.networking.k8s.io]
  resources: [gatewayclasses/status, gateways/status, httproutes/status, grpcroutes/status]
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
| `gatewayApiGatewayServiceName` | string | *(empty)* | Kubernetes Service name to resolve for Gateway status addresses. If empty, falls back to `otoroshiServiceName` |
| `gatewayApiAddresses` | array | `[]` | Static addresses for Gateway status. Overrides dynamic service resolution. Array of `{"type":"IPAddress","value":"x.x.x.x"}` or `{"type":"Hostname","value":"gw.example.com"}` objects |

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
          "gatewayApiSyncIntervalSeconds": 30,
          "gatewayApiGatewayServiceName": "",
          "gatewayApiAddresses": []
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

**Listener hostname**: acts as a filter. Only HTTPRoutes and GRPCRoutes with matching hostnames will be attached to this listener. Wildcard hostnames (e.g. `*.example.com`) are supported.

**allowedRoutes.namespaces.from**: controls which namespaces can attach routes to this listener.

| Value | Behavior |
|-------|----------|
| `Same` (default) | Only routes in the same namespace as the Gateway |
| `All` | Routes from any namespace |
| `Selector` | Routes from namespaces matching a label selector |

When using `Selector`, you provide a standard Kubernetes label selector under `allowedRoutes.namespaces.selector`. Both `matchLabels` and `matchExpressions` are supported:

```yaml
listeners:
- name: http
  protocol: HTTP
  port: 8080
  allowedRoutes:
    namespaces:
      from: Selector
      selector:
        matchLabels:
          shared-gateway-access: "true"
        matchExpressions:
        - key: environment
          operator: In
          values: ["staging", "production"]
```

This listener only accepts routes from namespaces that have the label `shared-gateway-access: "true"` **and** an `environment` label with value `staging` or `production`.

Supported `matchExpressions` operators: `In`, `NotIn`, `Exists`, `DoesNotExist`.

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

### ExtensionRef

Reference a custom Otoroshi `Plugin` resource to inject an arbitrary Otoroshi plugin into the route's plugin chain. This uses the `proxy.otoroshi.io/v1` CRD.

First, create a `Plugin` resource whose `spec` maps to an Otoroshi `NgPluginInstance`:

```yaml
apiVersion: proxy.otoroshi.io/v1
kind: Plugin
metadata:
  name: add-custom-headers
  namespace: default
spec:
  plugin: "cp:otoroshi.next.plugins.AdditionalHeadersIn"
  enabled: true
  config:
    headers:
      X-Custom-From-Plugin: "hello-from-k8s"
```

Then reference it from an HTTPRoute or GRPCRoute filter:

```yaml
filters:
- type: ExtensionRef
  extensionRef:
    group: proxy.otoroshi.io
    kind: Plugin
    name: add-custom-headers
```

The Plugin `spec` supports all `NgPluginInstance` fields: `plugin`, `enabled`, `debug`, `include`, `exclude`, `config`, `bound_listeners`, and `plugin_index`.

@@@ note
ExtensionRef only resolves Plugin resources in the **same namespace** as the route (per Gateway API specification). Only the `proxy.otoroshi.io/Plugin` group/kind is supported; other group/kind combinations will log a warning and be ignored.
@@@

## Otoroshi-specific annotations

While the Gateway API specification covers common routing patterns, you may need Otoroshi-specific settings that are not part of the standard. Otoroshi supports a set of annotations on `HTTPRoute` and `GRPCRoute` resources that let you customize the generated `NgRoute` without leaving the Kubernetes-native workflow.

All annotations use the prefix `proxy.otoroshi.io/` and expect JSON-encoded values.

### Supported annotations

| Annotation | Type | Description |
|------------|------|-------------|
| `proxy.otoroshi.io/route-plugins` | JSON array | Additional `NgPluginInstance` objects to append to the route's plugin chain |
| `proxy.otoroshi.io/route-flags` | JSON object | Override route boolean flags: `enabled`, `debugFlow`, `capture`, `exportReporting` |
| `proxy.otoroshi.io/route-groups` | JSON array | Override the Otoroshi group IDs for the route |
| `proxy.otoroshi.io/route-bound-listeners` | JSON array | Override the Otoroshi bound listener IDs for the route |
| `proxy.otoroshi.io/route-metadata` | JSON object | Additional key/value metadata merged into the route's metadata |

### Adding plugins via annotations

The `route-plugins` annotation lets you inject Otoroshi plugins into a route without using ExtensionRef filters. This is useful when you want to add plugins that apply to all rules of a route, or when you prefer annotations over CRD-based Plugin resources.

```yaml
apiVersion: gateway.networking.k8s.io/v1
kind: HTTPRoute
metadata:
  name: my-route
  namespace: default
  annotations:
    proxy.otoroshi.io/route-plugins: |
      [
        {
          "plugin": "cp:otoroshi.next.plugins.AdditionalHeadersIn",
          "enabled": true,
          "config": {
            "headers": {
              "X-Injected-By": "annotation"
            }
          }
        },
        {
          "plugin": "cp:otoroshi.next.plugins.ApikeyCalls",
          "enabled": true,
          "config": {}
        }
      ]
spec:
  parentRefs:
  - name: my-gateway
  hostnames:
  - "api.example.com"
  rules:
  - backendRefs:
    - name: my-service
      port: 80
```

Plugins added via annotations are **appended** after any plugins generated from route filters (RequestHeaderModifier, ResponseHeaderModifier, ExtensionRef, etc.).

### Controlling route flags

The `route-flags` annotation lets you override boolean flags on the generated route:

```yaml
metadata:
  annotations:
    proxy.otoroshi.io/route-flags: |
      {
        "enabled": true,
        "debugFlow": true,
        "capture": false,
        "exportReporting": true
      }
```

All fields are optional — only the specified flags are overridden, others keep their default values.

### Setting groups and bound listeners

Override which Otoroshi groups or listeners the route belongs to:

```yaml
metadata:
  annotations:
    proxy.otoroshi.io/route-groups: '["my-group-1", "my-group-2"]'
    proxy.otoroshi.io/route-bound-listeners: '["listener_0"]'
```

### Adding metadata

Merge additional key/value pairs into the route's metadata:

```yaml
metadata:
  annotations:
    proxy.otoroshi.io/route-metadata: |
      {
        "team": "platform",
        "cost-center": "engineering",
        "sla": "99.9"
      }
```

These metadata entries are merged with the default metadata that Otoroshi sets on generated routes (`otoroshi-provider`, `gateway-api-kind`, etc.).

@@@ warning
Annotation values must be valid JSON. If a JSON parse error occurs, the annotation is silently ignored and the route is generated with default values. Check the Otoroshi logs for any warnings.
@@@

## TLS certificate resolution

HTTPS listeners can reference Kubernetes TLS Secrets via `tls.certificateRefs`. Otoroshi automatically resolves these references and imports the certificates into its certificate store so that they are available for SNI-based TLS termination.

### How it works

During each reconciliation cycle, for every HTTPS listener with `certificateRefs`:

1. Otoroshi computes the expected certificate ID using the pattern `kubernetes-certs-import-{namespace}-{name}`
2. If the certificate already exists in Otoroshi's store, no action is needed
3. If the certificate is missing, Otoroshi fetches the Kubernetes Secret and imports it (the Secret must be of type `kubernetes.io/tls`)
4. The listener status condition `ResolvedRefs` reflects whether all referenced certificates were successfully resolved

### Example

```yaml
apiVersion: gateway.networking.k8s.io/v1
kind: Gateway
metadata:
  name: tls-gateway
  namespace: default
spec:
  gatewayClassName: otoroshi
  listeners:
  - name: https
    protocol: HTTPS
    port: 8443
    hostname: "api.example.com"
    tls:
      mode: Terminate
      certificateRefs:
      - name: api-tls-cert
    allowedRoutes:
      namespaces:
        from: Same
```

The referenced Secret must exist in the same namespace (or the namespace specified in the ref) and contain valid TLS data:

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: api-tls-cert
  namespace: default
type: kubernetes.io/tls
data:
  tls.crt: <base64-encoded certificate>
  tls.key: <base64-encoded private key>
```

Once imported, Otoroshi uses its standard SNI matching to select the right certificate for incoming TLS connections — no additional configuration is needed on the route.

If a referenced Secret does not exist or is not of type `kubernetes.io/tls`, the listener status will report `ResolvedRefs: False` with reason `InvalidCertificateRef`.

## GRPCRoute support

Otoroshi also supports `GRPCRoute` resources for routing gRPC traffic. GRPCRoute works similarly to HTTPRoute with the following differences:

- gRPC method matching is mapped to HTTP/2 paths: `/{service}/{method}`
- Backend targets automatically use the **HTTP/2** protocol
- Generated routes are restricted to the `POST` HTTP method (as required by the gRPC protocol)

@@@ note
gRPC requires HTTP/2 support. Make sure Otoroshi is running with the Netty server backend, which supports HTTP/2 natively. You can enable it with the `OTOROSHI_NEXT_EXPERIMENTAL_NETTY_SERVER_ENABLED=true` environment variable.
@@@

### GRPCRoute example

```yaml
apiVersion: gateway.networking.k8s.io/v1
kind: GRPCRoute
metadata:
  name: grpc-route
  namespace: default
spec:
  parentRefs:
  - name: my-gateway
    sectionName: http
  hostnames:
  - "grpc.example.com"
  rules:
  - matches:
    - method:
        service: com.example.UserService
        method: GetUser
    backendRefs:
    - name: grpc-user-service
      port: 50051
  - matches:
    - method:
        service: com.example.OrderService
    backendRefs:
    - name: grpc-order-service
      port: 50051
```

In this example:

- The first rule matches requests to `com.example.UserService/GetUser` exactly (path `/com.example.UserService/GetUser`)
- The second rule matches all methods on `com.example.OrderService` (path prefix `/com.example.OrderService/`)
- Both backends are called using HTTP/2

### gRPC method matching

| Match | Generated path | Behavior |
|-------|---------------|----------|
| `service: com.example.Foo, method: Bar` | `/com.example.Foo/Bar` | Exact match on service and method |
| `service: com.example.Foo` (no method) | `/com.example.Foo/` | Prefix match on all methods of the service |
| No method match specified | `/` | Match all gRPC requests |

### GRPCRoute filters

GRPCRoute supports the same filter types as HTTPRoute: `RequestHeaderModifier`, `ResponseHeaderModifier`, `RequestRedirect`, `URLRewrite`, and `ExtensionRef`.

## BackendTLSPolicy (TLS to backend)

Otoroshi supports `BackendTLSPolicy` (v1alpha3) for configuring TLS connections from the gateway to backend services. When a BackendTLSPolicy targets a Service, Otoroshi will use TLS when connecting to that service's backends, with proper SNI hostname and CA certificate validation.

### How it works

During each reconciliation cycle, Otoroshi:

1. Fetches all `BackendTLSPolicy` resources from the cluster
2. For each policy's `caCertificateRefs`, resolves the referenced Kubernetes Secrets and imports them as CA certificates into Otoroshi's certificate store (using the ID pattern `kubernetes-certs-import-{namespace}-{name}`)
3. When building backend targets for HTTPRoute or GRPCRoute rules, checks if a BackendTLSPolicy targets the backend Service
4. If a policy matches, configures the target with:
    - `tls: true` to enable HTTPS connections
    - The policy's `validation.hostname` as the SNI hostname for the TLS handshake
    - The Service's `clusterIP` as the actual TCP connection target
    - CA certificates from `caCertificateRefs` for server certificate validation

### Prerequisites

BackendTLSPolicy requires the experimental channel Gateway API CRDs:

```sh
kubectl apply -f https://github.com/kubernetes-sigs/gateway-api/releases/download/v1.4.0/experimental-install.yaml
```

Additional RBAC rules are needed:

```yaml
- apiGroups: [gateway.networking.k8s.io]
  resources: [backendtlspolicies]
  verbs: [get, list, watch]
```

### Example

```yaml
# Secret containing the CA certificate for the backend service
apiVersion: v1
kind: Secret
metadata:
  name: backend-ca
  namespace: default
type: Opaque
data:
  ca.crt: <base64-encoded CA certificate>
---
# BackendTLSPolicy targeting the backend service
apiVersion: gateway.networking.k8s.io/v1alpha3
kind: BackendTLSPolicy
metadata:
  name: backend-tls
  namespace: default
spec:
  targetRefs:
  - group: ""
    kind: Service
    name: my-backend-service
  validation:
    hostname: my-backend-service.default.svc.cluster.local
    caCertificateRefs:
    - group: ""
      kind: Secret
      name: backend-ca
```

With this configuration, when an HTTPRoute or GRPCRoute references `my-backend-service`, Otoroshi will:

- Connect to the service using HTTPS
- Send `my-backend-service.default.svc.cluster.local` as the SNI hostname
- Validate the server certificate against the CA in the `backend-ca` Secret

### wellKnownCACertificates

Instead of providing explicit CA certificates, you can use the JVM's default trust store:

```yaml
spec:
  targetRefs:
  - group: ""
    kind: Service
    name: my-service
  validation:
    hostname: my-service.example.com
    wellKnownCACertificates: "System"
```

When `wellKnownCACertificates` is set to `"System"`, no custom CA certificates are configured and the JVM's built-in trust store is used for server certificate validation.

### Limitations

- `subjectAltNames` validation is not supported (Otoroshi's TLS config has no SAN validation field). If specified, a debug warning is logged.
- BackendTLSPolicy is a v1alpha3 API and may change in future Gateway API releases.

## ReferenceGrant (cross-namespace references)

By default, a route can only reference backend Services in **its own namespace**. To reference a Service in a different namespace, a `ReferenceGrant` resource must exist in the **target namespace** (where the Service lives) that explicitly allows the reference.

This is a critical security feature that prevents routes in one namespace from accessing Services in another namespace without explicit permission from the target namespace owner.

### How it works

When converting an HTTPRoute or GRPCRoute, Otoroshi checks each `backendRef`:

1. If the backend Service is in the **same namespace** as the route, the reference is always allowed
2. If the backend Service is in a **different namespace**, Otoroshi looks for a `ReferenceGrant` in the target namespace that:
    - Allows the route's kind (`HTTPRoute` or `GRPCRoute`) from the route's namespace (in the `from` list)
    - Allows referencing `Service` resources, optionally restricted to a specific name (in the `to` list)
3. If no matching `ReferenceGrant` is found, the backend reference is **denied** and excluded from the generated route's targets

The route's status condition `ResolvedRefs` is set to `False` with reason `RefNotPermitted` when any backend reference is denied.

### Example

Consider an HTTPRoute in namespace `frontend` that needs to route traffic to a Service `api-svc` in namespace `backend`:

```yaml
# HTTPRoute in namespace "frontend"
apiVersion: gateway.networking.k8s.io/v1
kind: HTTPRoute
metadata:
  name: frontend-to-backend
  namespace: frontend
spec:
  parentRefs:
  - name: my-gateway
    namespace: default
  hostnames:
  - "app.example.com"
  rules:
  - backendRefs:
    - name: api-svc
      namespace: backend
      port: 80
```

Without a ReferenceGrant, this cross-namespace reference would be **denied**. To allow it, create a ReferenceGrant in the `backend` namespace:

```yaml
# ReferenceGrant in namespace "backend" — allows HTTPRoutes from "frontend" to reference Services
apiVersion: gateway.networking.k8s.io/v1beta1
kind: ReferenceGrant
metadata:
  name: allow-frontend
  namespace: backend
spec:
  from:
  - group: gateway.networking.k8s.io
    kind: HTTPRoute
    namespace: frontend
  to:
  - group: ""
    kind: Service
    name: api-svc    # optional: omit to allow all Services in this namespace
```

### Wildcard grants

If the `name` field is omitted in the `to` entry, the grant allows referencing **all** Services in that namespace:

```yaml
spec:
  from:
  - group: gateway.networking.k8s.io
    kind: HTTPRoute
    namespace: frontend
  to:
  - group: ""
    kind: Service
    # no name = wildcard, allows all Services
```

### Multiple sources

A single ReferenceGrant can allow references from multiple namespaces and route kinds:

```yaml
spec:
  from:
  - group: gateway.networking.k8s.io
    kind: HTTPRoute
    namespace: frontend
  - group: gateway.networking.k8s.io
    kind: GRPCRoute
    namespace: frontend
  - group: gateway.networking.k8s.io
    kind: HTTPRoute
    namespace: monitoring
  to:
  - group: ""
    kind: Service
```

## Generated NgRoute structure

Each HTTPRoute or GRPCRoute rule generates one `NgRoute` in Otoroshi with a deterministic ID:

```
kubernetes-gateway-api-{namespace}-{routeName}-rule-{ruleIndex}         # HTTPRoute
kubernetes-gateway-api-{namespace}-{routeName}-grpc-rule-{ruleIndex}    # GRPCRoute
```

The generated route includes:

- **Frontend**: domains built from effective hostnames + path, with method matching if specified (gRPC routes are locked to POST)
- **Backend**: targets resolved from backendRefs using Kubernetes Service clusterIPs, with weighted load balancing. GRPCRoute targets use HTTP/2. When a BackendTLSPolicy targets the service, TLS is enabled with SNI and CA validation.
- **Plugins**: converted from route filters (header modifiers, redirections, etc.)
- **Metadata**: `otoroshi-provider: kubernetes-gateway-api`, `gateway-api-kind: HTTPRoute` or `GRPCRoute`, plus `kubernetes-name`, `kubernetes-namespace` for traceability

## Status updates

The controller updates the `status` subresource on each Gateway API object:

- **GatewayClass**: `Accepted: True` when the `controllerName` matches, `Accepted: False` otherwise
- **Gateway**: `Accepted: True/False` based on gatewayClassName, `Programmed: True/False` per-listener based on port/protocol validation
- **HTTPRoute**: per-parent conditions `Accepted: True/False` and `ResolvedRefs: True/False`. When `ResolvedRefs` is `False`, the reason indicates the cause: `RefNotPermitted` (missing ReferenceGrant for cross-namespace reference) or `BackendNotFound` (Service does not exist)
- **GRPCRoute**: same status conditions as HTTPRoute

### Gateway addresses

The controller reports the network addresses where the Gateway is reachable in the `status.addresses` field.
This allows other tools and users to discover how to reach the gateway programmatically.

**Resolution priority:**

1. **Static addresses** (`gatewayApiAddresses`): if configured, these are used directly without any Kubernetes Service lookup.
   Useful for bare-metal, NodePort setups, or when an external load balancer IP is known in advance.

2. **Dynamic service resolution**: the controller looks up a Kubernetes Service to extract its addresses:
   - The service is identified by `gatewayApiGatewayServiceName` (if set), otherwise `otoroshiServiceName`
   - The namespace is always `otoroshiNamespace`
   - For `LoadBalancer` services: IPs and hostnames from `status.loadBalancer.ingress` are reported
   - For `ClusterIP` services (or when no LoadBalancer ingress is available): the `spec.clusterIP` is reported

**Examples:**

Static override (bare-metal with known external IP):

```json
{
  "gatewayApiAddresses": [
    {"type": "IPAddress", "value": "203.0.113.10"},
    {"type": "Hostname", "value": "gateway.example.com"}
  ]
}
```

Dedicated LoadBalancer service for gateway traffic:

```json
{
  "gatewayApiGatewayServiceName": "otoroshi-gateway-lb"
}
```

Default behavior (uses `otoroshiServiceName`): no additional configuration needed.

## Current limitations

The following features are **not yet implemented** in the current experiments:

| Feature | Status | Notes |
|---------|--------|-------|
| TLSRoute | Not implemented | Experimental in Gateway API spec |
| TCPRoute | Not implemented | Experimental in Gateway API spec |
| UDPRoute | Not implemented | Experimental in Gateway API spec |
| Dynamic listener provisioning | Not planned | Otoroshi uses a proxy-existing approach; ports must be pre-configured |

@@@ note
**ReferenceGrant enforcement** is active. Cross-namespace backend references require a `ReferenceGrant` in the target namespace. See the @ref:[ReferenceGrant section](#referencegrant-cross-namespace-references) for details and examples.
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

# GRPCRoute should show Accepted: True for each parent
kubectl get grpcroute my-grpc-route -n default -o yaml
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
