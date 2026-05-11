# Directory `app/next/` - New Proxy Engine

## Overview

This directory contains Otoroshi's **new proxy engine**, more performant and reactive than the old one. It includes new entities (`NgRoute`, `NgBackend`) and a redesigned plugin architecture.

## Structure

```
next/
├── controllers/     # Controllers for new entities
├── doc/             # Internal documentation
├── events/          # Specific events
├── extensions/      # Admin extension system
├── models/          # New data models
├── plugins/         # 70+ new engine plugins
├── proxy/           # Proxy engine itself
├── tunnel/          # Tunnel support
├── utils/           # Utilities
└── workflow/        # Request workflows
```

## Subdirectory `models/` - New Entities

### `route.scala` (~1000+ lines)
**Main entity**: `NgRoute`

```scala
case class NgRoute(
  location: EntityLocation,
  id: String,
  name: String,
  description: String,
  tags: Seq[String],
  metadata: Map[String, String],
  enabled: Boolean,
  debugFlow: Boolean,           // Plugin flow debug
  capture: Boolean,             // Request/response capture
  exportReporting: Boolean,     // Report export
  groups: Seq[String],
  boundListeners: Seq[String],  // Bound HTTP listeners
  frontend: NgFrontend,         // Frontend configuration
  backend: NgBackend,           // Backend configuration
  backendRef: Option[String],   // Reference to shared backend
  plugins: NgPlugins            // Plugin chain
)
```

**Route matching**:
- Domains (exact, wildcard, regex)
- Paths (exact, prefix, regex, params)
- HTTP methods
- Headers
- Query params
- Cookies
- Custom matching plugins

### `frontend.scala`
**Entity**: `NgFrontend`

```scala
case class NgFrontend(
  domains: Seq[NgDomainAndPath],  // Domains and paths
  headers: Map[String, String],   // Required headers
  query: Map[String, String],     // Required query params
  methods: Seq[String],           // Allowed HTTP methods
  stripPath: Boolean,             // Strip matched path
  exact: Boolean                  // Exact path matching
)

case class NgDomainAndPath(
  domain: String,
  path: String
)
```

### `backend.scala`
**Entity**: `NgBackend`

```scala
case class NgBackend(
  targets: Seq[NgTarget],           // Backend list
  root: String,                     // Backend root path
  rewrite: Boolean,                 // URL rewrite
  loadBalancing: LoadBalancing,     // LB algorithm
  healthCheck: Option[HealthCheck],
  client: NgClientConfig            // HTTP client config
)

case class NgTarget(
  id: String,
  hostname: String,
  port: Int,
  tls: Boolean,
  weight: Int,                      // LB weight
  predicate: NgTargetPredicate,     // Selection condition
  protocol: HttpProtocol,           // HTTP/1.1, HTTP/2, HTTP/3
  ipAddress: Option[String],        // Fixed IP
  tlsConfig: NgTlsConfig            // TLS config
)
```

### `service.scala`
**Entity**: `NgRouteComposition` (route composition)

### `Api.scala`
**Entity**: `Api` (API consumer management)

### `plugins.scala`
**Plugin configuration for a route**:

```scala
case class NgPlugins(
  slots: Seq[NgPluginInstance]  // Plugin instances
)

case class NgPluginInstance(
  plugin: String,               // Plugin ID
  enabled: Boolean,
  debug: Boolean,
  include: Seq[String],         // Included paths
  exclude: Seq[String],         // Excluded paths
  config: NgPluginInstanceConfig
)
```

### `treerouter.scala`
**Role**: Prefix tree-based router for optimal performance.

---

## Subdirectory `proxy/` - Proxy Engine

### `engine.scala` (~2000+ lines)
**Role**: New proxy engine core.

**Main class**: `ProxyEngine extends RequestHandler`

**Configuration**:
```scala
case class ProxyEngineConfig(
  enabled: Boolean,
  domains: Seq[String],        // Managed domains
  denyDomains: Seq[String],    // Excluded domains
  reporting: Boolean,          // Reports enabled
  pluginMerge: Boolean,        // Sync plugin merge
  exportReporting: Boolean,    // Report export
  debug: Boolean,              // Debug mode
  debugHeaders: Boolean,       // Debug headers
  applyLegacyChecks: Boolean,  // Legacy checks
  capture: Boolean,            // Traffic capture
  captureMaxEntitySize: Long,  // Max capture size
  routingStrategy: RoutingStrategy  // Tree or Linear
)
```

**Routing strategies**:
- `Tree` - Tree router (performant)
- `Linear` - Linear router (compatible)

### `state.scala`
**Role**: Proxy global state (route cache, backends, etc.).

```scala
class NgProxyState {
  // In-memory route cache
  // Backend cache
  // Certificate cache
  // API key cache
  // JWT verifier cache
}
```

### `request.scala`
**Role**: Request representation in the new engine.

### `report.scala`
**Role**: Execution report generation.

```scala
case class NgExecutionReport(
  id: String,
  timestamp: DateTime,
  route: NgRoute,
  request: RequestHeader,
  plugins: Seq[NgReportPluginSequenceItem],
  duration: Long,
  error: Option[NgProxyEngineError]
)
```

### `errors.scala`
**Role**: Engine error types.

### `zones.scala`
**Role**: Geographic zone management for relay routing.

---

## Subdirectory `plugins/` - 70+ Plugins

Plugins are organized by category:

### Plugin Types

| Type | Phase | Description |
|------|-------|-------------|
| `NgPreRouting` | Before routing | Modifications before matching |
| `NgAccessValidator` | Validation | Access control |
| `NgRequestTransformer` | Transformation | Request/response modification |
| `NgBackendCall` | Backend call | Custom call logic |
| `NgRouteMatcher` | Matching | Custom route matching |
| `NgRequestSink` | Sink | Special processing (errors, etc.) |

### Main Plugins

**Authentication**:
- `NgLegacyApikeyCall` - API key validation
- `NgAuthModuleExpectedUser` - Auth module validation
- `NgAuthModuleUserExtractor` - User extraction

**JWT**:
- `JwtVerificationOnly` - JWT verification
- `NgJwtUserExtractor` - User extraction from JWT

**Transformation**:
- `NgAdditionalHeadersIn/Out` - Add headers
- `NgRemoveHeadersIn/Out` - Remove headers
- `NgOverrideHost` - Override Host header
- `NgXForwardedHeaders` - X-Forwarded-* headers

**Security**:
- `NgCors` - CORS configuration
- `NgIpAddressBlockList` - IP blocklist
- `NgIpAddressAllowedList` - IP allowlist
- `NgBiscuitValidator` - Biscuit token validation

**Traffic**:
- `NgSnowMonkey` - Chaos engineering
- `NgCanary` - Canary deployments
- `NgMirroringPlugin` - Traffic mirroring

**Observability**:
- `NgAccessLog` - Access logs
- `NgMetrics` - Custom metrics

---

## Subdirectory `extensions/` - Admin Extensions

Extension system for the administration interface:

```scala
trait AdminExtension {
  def id: AdminExtensionId
  def name: String
  def description: String
  def enabled: Boolean
  def start(): Future[Unit]
  def stop(): Future[Unit]
  // Routes, entities, assets...
}
```

Available extensions:
- GreenScore
- API Consumer (consumer management)
- Workflows

---

## Subdirectory `workflow/` - Request Workflows

Workflow system for complex request orchestration:

```scala
case class NgWorkflow(
  id: String,
  name: String,
  steps: Seq[NgWorkflowStep],
  // ...
)
```

Allows:
- Chaining HTTP calls
- Data transformation between steps
- Conditions and loops management

---

## Subdirectory `tunnel/` - Tunnels

TCP/WebSocket tunnel support for:
- TCP over HTTP tunneling
- Access to non-HTTP services
- Persistent connections

---

## New Engine Architecture

```
HTTP Request
    │
    ▼
┌─────────────────┐
│  ProxyEngine    │
│  (RequestHandler)│
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  Tree Router    │ ← Fast matching via tree
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ NgPreRouting    │ ← Pre-routing plugins
│    Plugins      │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│NgAccessValidator│ ← Access control
│    Plugins      │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ NgRequest       │ ← Request transformation
│  Transformer    │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ NgBackendCall   │ ← Backend call
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ NgResponse      │ ← Response transformation
│  Transformer    │
└────────┬────────┘
         │
         ▼
    HTTP Response
```

## Key Points

1. **Performance**: Tree router for O(log n) matching
2. **Modularity**: Everything is a plugin
3. **Observability**: Detailed reporting by default
4. **Backward compatibility**: Legacy entity support via conversion
5. **Extensibility**: Admin extension system
