# Otoroshi Architecture Overview

*Last updated: January 2026*

## Introduction

Otoroshi is a modern API gateway and HTTP reverse proxy written in Scala. This document describes its internal architecture to facilitate code understanding and project contribution.

## Design Philosophy

- **Technology agnostic**: Works with any backend
- **HTTP-first**: REST API for all administration
- **Event-driven**: Event-driven architecture for external integrations
- **API-first**: Admin UI is just an API client
- **Dynamic configuration**: Hot changes without restart

## Detailed Tech Stack

### Backend (Scala)

| Component | Technology | Version | Role |
|-----------|------------|---------|------|
| Runtime | Scala | 2.12.16 | Main language |
| Web Framework | Play Framework | 2.8.19 | HTTP, routing |
| Actor System | Akka | 2.6.20 | Concurrency, streaming |
| HTTP Server | Akka HTTP | 10.2.10 | Main HTTP server |
| HTTP Server (alt) | Reactor Netty | 1.1.18 | Experimental server |
| HTTP/3 | Netty QUIC | 0.0.62 | QUIC/HTTP3 support |
| Build | SBT | - | Build tool |

### Frontend (JavaScript)

| Component | Technology | Version |
|-----------|------------|---------|
| Framework | React | 17.0.2 |
| UI Library | Ant Design | 4.21.4 |
| Routing | React Router | 5.2.0 |
| State | React Query | 3.39.3 |
| Build | Webpack | 5.101.0 |
| E2E Tests | Playwright | 1.47.0 |

### Storage (Multi-backend)

Otoroshi supports multiple storage backends, configurable via `APP_STORAGE`:

| Backend | Use Case | Configuration |
|---------|----------|---------------|
| `inmemory` | Development | No persistence |
| `file` | Dev/Test | Local file (LevelDB) |
| `redis` | Production | Lettuce 6.8.1 |
| `postgresql` | Production | Vertx 4.5.22 |
| `cassandra` | Distributed production | DataStax 4.15.0 |
| `s3` | Serverless | Alpakka S3 |
| `http` | Remote state | HTTP client |

## Component Architecture

### HTTP Request Flow (New Engine - `next/`)

```
HTTP Client
    │
    ▼
┌─────────────────┐
│  HttpHandler    │  ← Entry point (Akka HTTP or Netty)
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  ProxyEngine    │  ← New engine core (next/proxy/engine.scala)
│ (RequestHandler)│
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  Tree Router    │  ← O(log n) matching via prefix tree
│                 │     (domains, paths, headers, query params)
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ NgPreRouting    │  ← Pre-routing plugins
│   Plugins       │     (modifications before matching)
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│NgAccessValidator│  ← Access control
│   Plugins       │     (API keys, JWT, auth modules, IP filter)
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  NgRequest      │  ← Request transformation
│  Transformer    │     (headers, body, path rewrite)
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ NgBackendCall   │  ← Backend call
│                 │     (load balancing, circuit breaker, retry)
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  NgResponse     │  ← Response transformation
│  Transformer    │     (headers, body, CORS)
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  Events/Report  │  ← Analytics, audit, metrics
│                 │     (export to Kafka, Elastic, etc.)
└────────┬────────┘
         │
         ▼
    HTTP Response
```

### HTTP Request Flow (Legacy Engine - `gateway/`)

```
HTTP Client
    │
    ▼
┌─────────────────┐
│  HttpHandler    │  ← Akka HTTP entry point
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ GatewayRequest  │  ← Linear route matching
│    Handler      │     (hostname, path, method)
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│RequestTransformer│  ← Transformation plugins
│   Plugins       │     (pre-routing, auth)
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Circuit Breaker │  ← Resilience
│ + Backend Call  │     (retry, timeout, fallback)
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ResponseTransformer│ ← Post-processing
│   Plugins       │     (transform, logging)
└────────┬────────┘
         │
         ▼
    HTTP Response
```

### Source Directory Structure

**Important**: Otoroshi has **two proxy engines**:
- `gateway/` + `models/` = **Legacy** engine
- `next/` = **New engine** (default, more performant)

```
app/
├── OtoroshiLoader.scala      # Bootstrap, DI, entry point
│
├── actions/                  # Play auth interceptors
│   ├── ApiAction             # Auth for REST API
│   ├── BackOfficeAction      # Auth for admin UI
│   └── PrivateAppsAction     # Auth for private apps
│
├── api/                      # Generic CRUD framework
│   ├── GenericApi            # Automatic endpoint generation
│   └── OpenApiGenerator      # OpenAPI spec generation
│
├── auth/                     # Authentication modules (13)
│   ├── oauth.scala           # OAuth 1.0/2.0
│   ├── oauth2.scala          # OAuth2/OIDC provider
│   ├── ldap.scala            # LDAP/Active Directory
│   ├── saml.scala            # SAML 2.0
│   ├── basic.scala           # Basic Auth
│   └── wasm.scala            # Auth via WASM
│
├── cluster/                  # Distributed mode
│   ├── cluster.scala         # Leader/Worker
│   └── relay.scala           # Cross-zone relay routing
│
├── controllers/              # HTTP Controllers (9 main)
│   ├── BackOfficeController  # Admin UI backend
│   ├── AuthController        # Auth flows
│   └── adminapi/             # REST API (24 controllers)
│       ├── ServicesController
│       ├── ApiKeysController
│       ├── RoutesController   # New engine
│       └── ...
│
├── el/                       # Expression Language
│   └── el.scala              # ${req.path}, ${ctx.field}, etc.
│
├── env/                      # Global environment
│   └── Env.scala             # Injected everywhere, config/storage access
│
├── events/                   # Event system
│   ├── events.scala          # Definitions (Analytics, Alert, Audit)
│   └── impl/                 # Exporters (Kafka, Elastic, Pulsar...)
│
├── gateway/                  # *** LEGACY ENGINE ***
│   ├── generic.scala         # Main routing
│   ├── handlers.scala        # Response handlers
│   ├── circuitbreakers.scala # Circuit breakers
│   ├── snowmonkey.scala      # Chaos engineering
│   └── websockets.scala      # WebSocket proxy
│
├── greenscore/               # Ecological score
│   └── greenscore.scala      # GreenScore calculation for routes
│
├── health/                   # Health checks
│   └── health.scala          # Backend verification
│
├── jobs/                     # Scheduled jobs
│   ├── certs/                # Certificate renewal
│   ├── apikeys/              # API key rotation
│   └── sync/                 # Cluster synchronization
│
├── metrics/                  # Metrics
│   ├── prometheus.scala      # Prometheus
│   └── opentelemetry.scala   # OpenTelemetry
│
├── models/                   # *** LEGACY ENTITIES ***
│   ├── descriptor.scala      # ServiceDescriptor (legacy route)
│   ├── apikey.scala          # ApiKey
│   ├── config.scala          # GlobalConfig
│   ├── JWTVerifier.scala     # JWT validation
│   ├── dataExporter.scala    # DataExporter
│   └── teams.scala           # Team/Tenant
│
├── netty/                    # Alternative Netty server
│   ├── netty.scala           # Reactor Netty server
│   └── http3.scala           # HTTP/3 (QUIC)
│
├── next/                     # *** NEW ENGINE ***
│   ├── models/               # New entities
│   │   ├── route.scala       # NgRoute
│   │   ├── backend.scala     # NgBackend
│   │   ├── frontend.scala    # NgFrontend
│   │   └── plugins.scala     # NgPlugins
│   ├── proxy/                # Proxy engine
│   │   ├── engine.scala      # ProxyEngine (core)
│   │   ├── state.scala       # Global state
│   │   └── report.scala      # Execution reports
│   ├── plugins/              # 70+ plugins
│   ├── extensions/           # Admin extensions
│   ├── tunnel/               # TCP/WS tunnels
│   └── workflow/             # Workflows
│
├── openapi/                  # OpenAPI generation
│   ├── openapi.scala         # OpenAPI 3.0 specs
│   └── crds.scala            # Kubernetes CRDs
│
├── plugins/                  # *** LEGACY PLUGINS (40+) ***
│   ├── core/                 # Core plugins
│   └── jobs/kubernetes/      # Complete K8s integration
│       ├── kubernetes.scala  # K8s controller
│       ├── ingress.scala     # Ingress controller
│       └── crds.scala        # Otoroshi CRDs
│
├── script/                   # Plugin framework
│   └── script.scala          # Types, dynamic compilation
│
├── security/                 # Security
│   ├── claim.scala           # OtoroshiClaim (backend JWT)
│   └── IdGenerator.scala     # Snowflake IDs
│
├── ssl/                      # Complete TLS/PKI
│   ├── ssl.scala             # Cert entity, SSL engine
│   ├── pki.scala             # BouncyCastle PKI
│   ├── ocsp.scala            # OCSP responder
│   └── acme.scala            # Let's Encrypt
│
├── storage/                  # Multi-backend persistence
│   ├── storage.scala         # Abstractions
│   ├── drivers/              # inmemory, lettuce, reactivepg, cassandra
│   └── stores/               # 20+ per-entity datastores
│
├── tcp/                      # TCP proxy
│   └── tcp.scala             # TcpService, SNI, mTLS
│
├── utils/                    # Utilities (44 files)
│   ├── httpclient.scala      # HTTP client
│   ├── syntax.scala          # Scala extensions
│   └── cache/                # Cache implementations
│
└── wasm/                     # WebAssembly
    └── wasm.scala            # WASM plugin support (Extism)
```

## Domain Entities

### Entity Hierarchy

```
Global Config (singleton)
    │
    ├── Organizations (multi-tenant)
    │   └── Teams
    │       └── Routes (NgRoute or ServiceDescriptor)
    │           ├── Frontend (matching config)
    │           ├── Backend/Targets (NgBackend)
    │           ├── Plugins (NgPlugins)
    │           └── API Keys
    │
    ├── Certificates (complete PKI)
    ├── Auth Modules (OAuth, OIDC, LDAP, SAML, WASM)
    ├── JWT Verifiers
    ├── Data Exporters (Kafka, Elastic, Pulsar...)
    ├── TCP Services (non-HTTP proxy)
    └── Admin Users
```

### New Engine Entities (`next/`)

| Entity | Description | File | Storage |
|--------|-------------|------|---------|
| **NgRoute** | Route with chained plugins | `next/models/route.scala` | `routes` |
| **NgBackend** | Targets with LB and health check | `next/models/backend.scala` | `backends` |
| **NgFrontend** | Matching config (domains, paths, headers) | `next/models/frontend.scala` | Embedded |
| **NgPlugins** | Configured plugin chain | `next/models/plugins.scala` | Embedded |
| **NgTarget** | Individual backend with weight | `next/models/backend.scala` | Embedded |

### Legacy Entities (`models/`)

| Entity | Description | File | Storage |
|--------|-------------|------|---------|
| **ServiceDescriptor** | Legacy route (less flexible) | `models/descriptor.scala` | `services` |

### Shared Entities

| Entity | Description | File | Storage |
|--------|-------------|------|---------|
| **ApiKey** | Client credentials with quotas | `models/apikey.scala` | `apikeys` |
| **Certificate** | TLS/mTLS certificate | `ssl/ssl.scala` | `certificates` |
| **AuthModuleConfig** | Auth provider (13 types) | `auth/*.scala` | `auth-modules` |
| **GlobalJwtVerifier** | JWT validation rules | `models/JWTVerifier.scala` | `jwt-verifiers` |
| **DataExporter** | Event export (15+ types) | `models/dataExporter.scala` | `data-exporters` |
| **GlobalConfig** | System configuration | `models/config.scala` | `config` |
| **Tenant** | Multi-tenant organization | `models/teams.scala` | `tenants` |
| **Team** | Work group | `models/teams.scala` | `teams` |
| **TcpService** | TCP proxy service | `tcp/tcp.scala` | `tcp-services` |
| **Script** | Dynamically compiled plugin | `script/script.scala` | `scripts` |

## Plugin System

### Plugin Architecture

Otoroshi has **110+ plugins** distributed between:
- `plugins/` - 40+ legacy plugins
- `next/plugins/` - 70+ new engine plugins

### Plugin Types (New Engine)

| Type | Phase | Description |
|------|-------|-------------|
| `NgPreRouting` | Before routing | Modifications before matching |
| `NgAccessValidator` | Validation | Access control (auth, IP, quotas) |
| `NgRequestTransformer` | Transformation | Request/response modification |
| `NgBackendCall` | Backend call | Custom call logic |
| `NgRouteMatcher` | Matching | Custom route matching |
| `NgRequestSink` | Sink | Special processing (errors) |

### Plugin Types (Legacy)

| Type | Description |
|------|-------------|
| `RequestTransformer` | Request/response transformation |
| `AccessValidator` | Access control |
| `PreRouting` | Before routing |
| `RequestSink` | Request sink |
| `EventListener` | Event listener |
| `Job` | Scheduled job |

### Main Plugins by Category

**Authentication:**
- `NgLegacyApikeyCall` / `ApiKeyCallPlugin` - API key validation
- `NgAuthModuleExpectedUser` - Auth module validation
- `NgJwtUserExtractor` - User extraction from JWT
- `JwtVerificationOnly` - JWT verification

**Security:**
- `NgCors` - CORS configuration
- `NgIpAddressBlockList` / `AllowedList` - IP filtering
- `NgBiscuitValidator` - Biscuit tokens
- `ClientCertValidator` - mTLS client certificates

**Transformation:**
- `NgAdditionalHeadersIn/Out` - Add headers
- `NgRemoveHeadersIn/Out` - Remove headers
- `NgOverrideHost` - Override Host header
- `NgXForwardedHeaders` - X-Forwarded-* headers
- `JqPlugin` - JSON transformation with jq

**Traffic:**
- `NgSnowMonkey` - Chaos engineering
- `NgCanary` - Canary deployments
- `NgMirroringPlugin` - Traffic mirroring
- `CircuitBreaker` - Circuit breaker

**Observability:**
- `NgAccessLog` - Access logs
- `NgMetrics` - Custom metrics

**Advanced:**
- WASM plugins (Rust, Go, AssemblyScript, Zig)
- `OpaPlugin` - Open Policy Agent
- `GraphQLPlugin` - GraphQL gateway
- `IzanamiPlugin` - Feature flags

## Cluster Mode

Otoroshi supports a distributed mode:

```
┌─────────────────┐
│  Control Plane  │  ← Configuration, global state
│    (Leader)     │
└────────┬────────┘
         │ Sync
    ┌────┴────┐
    ▼         ▼
┌────────┐ ┌────────┐
│  Data  │ │  Data  │  ← Traffic proxying
│ Plane  │ │ Plane  │
└────────┘ └────────┘
```

## Security

### Supported Authentication
- API Keys (custom)
- JWT (RS256, HS256, ES256, etc.)
- OAuth 2.0 / 2.1
- OpenID Connect
- LDAP / Active Directory
- SAML 2.0
- Client Certificates (mTLS)
- Biscuit tokens

### TLS/PKI
- TLS 1.2, 1.3
- Dynamic SNI
- Dynamic certificates
- Internal PKI
- ACME/Let's Encrypt
- OCSP
- mTLS

## Observability

### Metrics
- Prometheus (native)
- Datadog
- StatsD

### Tracing
- OpenTelemetry
- W3C Trace Context
- Zipkin, Jaeger

### Events
- Elasticsearch
- Kafka
- Pulsar
- Webhooks
- File
- S3

## Two Engines Comparison

| Aspect | Legacy (`gateway/`) | New (`next/`) |
|--------|---------------------|---------------|
| **Route entity** | ServiceDescriptor | NgRoute |
| **Routing** | Linear O(n) | Tree router O(log n) |
| **Plugins** | 40+ | 70+ |
| **Configuration** | Less flexible | Granular (frontend/backend separated) |
| **Matching** | Domain + path | Domain + path + headers + query + cookies |
| **Debugging** | Basic | Detailed execution reports |
| **Recommendation** | Compatibility | **Use by default** |

## Extension Points

1. **Scala plugins** - Native code, maximum performance
2. **WASM plugins** - Multi-language (Rust, Go, AssemblyScript, Zig, C/C++)
3. **Admin extensions** - UI extensions via `next/extensions/`
4. **Custom Data Exporters** - Event export to custom destinations
5. **Custom Auth modules** - Custom authentication providers
6. **Webhooks** - External integration

## Kubernetes Integration

Otoroshi natively integrates with Kubernetes via `plugins/jobs/kubernetes/`:

- **Ingress Controller** - Supports standard K8s Ingress
- **Custom CRDs** - Otoroshi entities as CRDs (Route, Backend, ApiKey...)
- **Validation Webhooks** - Resource validation
- **Certificate sync** - Import from K8s Secrets
- **Service Discovery** - Automatic service discovery

## Detailed Documentation

For more details on each component, see the notes in this folder:

| Note | Content |
|------|---------|
| [app-next.md](./app-next.md) | **New engine** (NgRoute, plugins, proxy) |
| [app-gateway.md](./app-gateway.md) | Legacy engine |
| [app-auth.md](./app-auth.md) | Authentication modules |
| [app-plugins-script-tcp-utils-wasm.md](./app-plugins-script-tcp-utils-wasm.md) | Plugins, WASM, TCP proxy |
| [app-ssl-storage.md](./app-ssl-storage.md) | PKI/TLS and storage |
| [app-events.md](./app-events.md) | Events and exporters |
| [app-cluster.md](./app-cluster.md) | Distributed mode |

---

*Last updated: January 2026*
