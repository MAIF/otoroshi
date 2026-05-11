# Directories `app/plugins/`, `app/script/`, `app/tcp/`, `app/utils/`, `app/wasm/`

## Directory `plugins/` - Legacy Plugins

### Overview
Plugins for the legacy proxy engine. New plugins are in `next/plugins/`.

### Structure

```
plugins/
├── core/                    # Core plugins
│   ├── apikeys.scala        # API key validation
│   └── default.scala        # Default plugin
├── jobs/
│   └── kubernetes/          # Kubernetes integration
│       ├── kubernetes.scala # Main controller
│       ├── ingress.scala    # Ingress controller
│       ├── crds.scala       # Otoroshi CRDs
│       ├── certs.scala      # Certificate sync
│       └── webhooks.scala   # Validation webhooks
└── [40+ plugin files]
```

### Available Plugins (selection)

| File | Plugin | Description |
|------|--------|-------------|
| `accesslog.scala` | AccessLog | Access logs |
| `apikeys.scala` | ApiKeyCallPlugin | API key validation |
| `biscuit.scala` | BiscuitValidator | Biscuit tokens |
| `body.scala` | BodyTransformer | Body transformation |
| `cache.scala` | CachePlugin | Response caching |
| `clientcert.scala` | ClientCertValidator | Client cert validation |
| `discovery.scala` | DiscoveryPlugin | Service discovery |
| `geoloc.scala` | GeolocPlugin | Geolocation |
| `hmac.scala` | HmacValidator | HMAC validation |
| `izanami.scala` | IzanamiPlugin | Izanami feature flags |
| `jq.scala` | JqPlugin | JQ transformation |
| `jsoup.scala` | JsoupPlugin | HTML/XML parsing |
| `jwt.scala` | JwtPlugin | JWT handling |
| `metrics.scala` | MetricsPlugin | Custom metrics |
| `mirror.scala` | MirrorPlugin | Traffic mirroring |
| `oauth1.scala` | OAuth1Plugin | OAuth 1.0a |
| `oidc.scala` | OIDCPlugin | OpenID Connect |
| `quotas.scala` | QuotasPlugin | Quota management |
| `security.scala` | SecurityPlugin | Security headers |
| `useragent.scala` | UserAgentPlugin | User-Agent parsing |

### Kubernetes Integration (`jobs/kubernetes/`)

**Complete K8s controller**:
- Standard Ingress controller
- Custom Otoroshi CRDs
- Certificate sync from Secrets
- Validation webhooks
- Service discovery

---

## Directory `script/` - Script/Plugin System

### Overview
Plugin and runtime-executable script framework.

### `script.scala` (~2000+ lines)

**Plugin types**:
```scala
sealed trait PluginType
object PluginType {
  object TransformerType     // Request/response transformation
  object AccessValidatorType // Access validation
  object PreRoutingType      // Before routing
  object RequestSinkType     // Request sink
  object EventListenerType   // Event listener
  object JobType             // Scheduled job
  object DataExporterType    // Data export
  object RequestHandlerType  // Complete handler
  object CompositeType       // Composite type
}
```

**Base traits**:
```scala
trait NamedPlugin {
  def name: String
  def description: Option[String]
  def defaultConfig: Option[JsObject]
  def pluginType: PluginType
}

trait StartableAndStoppable {
  def start(env: Env): Future[Unit]
  def stop(env: Env): Future[Unit]
}

trait RequestTransformer extends NamedPlugin {
  def transformRequest(context: TransformerRequestContext): Future[Either[Result, HttpRequest]]
  def transformResponse(context: TransformerResponseContext): Future[Either[Result, HttpResponse]]
}

trait AccessValidator extends NamedPlugin {
  def access(context: AccessContext): Future[Boolean]
}

trait PreRouting extends NamedPlugin {
  def preRoute(context: PreRoutingContext): Future[Unit]
}

trait Job extends NamedPlugin {
  def jobRun(ctx: JobContext): Future[Unit]
}
```

**ScriptManager**:
- Dynamic plugin loading
- Runtime Scala compilation (experimental)
- Compiled plugin cache

---

## Directory `tcp/` - TCP Proxy

### Overview
Proxy for TCP services (non-HTTP).

### `tcp.scala` (~1000 lines)

**Main entity**: `TcpService`
```scala
case class TcpService(
  id: String,
  name: String,
  enabled: Boolean,
  tls: TlsMode,              // Passthrough, Enabled, Disabled
  sni: SniSettings,          // SNI matching
  clientAuth: ClientAuth,    // mTLS
  port: Int,                 // Listen port
  interface: String,         // Network interface
  rules: Seq[TcpRule],       // Routing rules
  location: EntityLocation
)

case class TcpRule(
  domain: String,            // SNI domain
  targets: Seq[TcpTarget]    // TCP backends
)

case class TcpTarget(
  host: String,
  port: Int,
  tls: Boolean,
  hostname: Option[String]   // Custom DNS resolution
)
```

**TLS modes**:
- `Passthrough` - TLS passed as-is to backend
- `Enabled` - TLS termination by Otoroshi
- `Disabled` - No TLS

**Features**:
- SNI matching for multi-service on one port
- Configurable mTLS
- Load balancing
- Analytics events (`TcpEvent`)

---

## Directory `utils/` - Utilities

### Overview
Shared utilities throughout the application.

### Main Files

| File | Description |
|------|-------------|
| `httpclient.scala` | HTTP client with retry, circuit breaker |
| `headers.scala` | HTTP headers manipulation |
| `gzip.scala` | Compression/decompression |
| `controllers.scala` | Controller helpers |
| `syntax.scala` | Scala syntax extensions |
| `cache/` | Cache implementations |
| `http/` | HTTP utilities (DN, cookies) |
| `letsencrypt/` | Let's Encrypt integration |
| `mailer/` | Email sending |
| `prometheus/` | Custom Prometheus collector |

**Useful patterns**:
```scala
// Syntax extensions
import otoroshi.utils.syntax.implicits._

// Future helpers
value.future       // => Future.successful(value)
value.vfuture      // => FastFuture.successful(value)
opt.some           // => Some(opt)

// JSON helpers
json.select("path").asOpt[String]
json.at("nested.path").asOpt[JsValue]

// Option/Either helpers
value.applyOn(f)   // value.map(f)
value.applyOnIf(cond)(f)
```

---

## Directory `wasm/` - WebAssembly

### Overview
WebAssembly plugin support via Extism/WASM4S.

### Features

```scala
// WASM plugin configuration
case class WasmConfig(
  source: WasmSource,        // URL, file, inline
  functionName: String,      // Function to call
  config: Map[String, String],
  allowedHosts: Seq[String], // Allowed hosts
  memoryPages: Int,          // Memory pages
  lifetime: WasmVmLifetime   // Request, Route, Forever
)
```

**WASM sources**:
- `Http` - Download from URL
- `File` - Local file
- `Wasmo` - Wasmo plugin server

**Supported languages**:
- Rust (recommended)
- Go (TinyGo)
- AssemblyScript
- Zig
- C/C++

---

## Root `app/` Files

### `OtoroshiLoader.scala`
**Role**: Entry point and dependency injection.

- Initializes `Env` (global environment)
- Configures Play routes
- Starts jobs
- Configures storage
- Initializes cluster

### `api.scala`
**Role**: API definition and hooks.

---

## Relationships

```
plugins/ + next/plugins/
    │
    ├── Loaded by → script/ScriptManager
    ├── Configured on → models/ServiceDescriptor or next/models/NgRoute
    └── Executed by → gateway/ or next/proxy/

script/
    │
    ├── Framework for → plugins/, next/plugins/
    ├── Manages → Dynamic compilation, cache, lifecycle
    └── Used by → Entire application

tcp/
    │
    ├── TCP servers → Akka Streams TCP
    ├── Uses → ssl/ (TLS/mTLS)
    └── Generates → events/TcpEvent

utils/
    │
    └── Used by → All code

wasm/
    │
    ├── Runtime → Extism / WASM4S
    └── Used by → Plugins with WASM support
```

## Key Points

**Plugins**:
- 40+ legacy plugins in `plugins/`
- 70+ plugins in `next/plugins/`
- Complete Kubernetes integration

**Scripts**:
- Extensible plugin framework
- Dynamic Scala compilation support
- Managed lifecycle (start/stop)

**TCP**:
- Complete TCP proxy with SNI
- mTLS support
- Integrated analytics

**WASM**:
- Multi-language plugins
- Secure isolation
- Near-native performance
