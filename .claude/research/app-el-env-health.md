# Directories `app/el/`, `app/env/`, `app/health/`

## Directory `el/` - Expression Language

### `el.scala` (~800+ lines)
**Role**: Templating engine for dynamic expressions in configurations.

**Main class**: `GlobalExpressionLanguage`

**Syntax**: `${expression}`

**Supported expressions**:

```scala
// Date and time
${date}                          // Current ISO date
${date.format('yyyy-MM-dd')}     // Formatted date
${date.epoch_ms}                 // Timestamp ms
${date.epoch_sec}                // Timestamp sec

// HTTP Request
${req.method}                    // GET, POST, etc.
${req.path}                      // Request path
${req.uri}                       // Full URI
${req.host}                      // Hostname
${req.domain}                    // Domain
${req.protocol}                  // http/https
${req.headers.X-Custom}          // Specific header
${req.query.param}               // Query param
${req.cookies.name}              // Cookie

// Context
${ctx.field}                     // Context variable
${params.field}                  // Route parameter
${item.field}                    // Current item (loops)

// Service/Route
${service.id}                    // Service ID
${service.name}                  // Service name
${service.domain}                // Service domain
${route.id}                      // Route ID

// User
${user.name}                     // User name
${user.email}                    // User email
${user.metadata.field}           // User metadata

// API Key
${apikey.clientId}               // Client ID
${apikey.clientName}             // Client name
${apikey.metadata.field}         // API key metadata
${apikey.tags}                   // Tags

// Client certificate
${clientCert.CN}                 // Common Name
${clientCert.OU}                 // Organizational Unit
${clientCert.O}                  // Organization
${clientCert.serialNumber}       // Serial number

// Environment (if enabled)
${env.MY_VAR}                    // Environment variable
${config.my.key}                 // Configuration key

// Chaining with fallback
${req.headers.X-Custom || ctx.default || 'fallback'}

// Vault secrets
${vault://secret/path}           // Secret from vault
```

**Used for**:
- Dynamic headers
- Backend URLs
- Body transformations
- Routing conditions

---

## Directory `env/` - Environment

### `Env.scala` (~2000+ lines)
**Role**: Central class containing Otoroshi's entire runtime environment.

**Main class**: `Env`

**Responsibilities**:
- Global configuration (Play Configuration)
- ActorSystem and ExecutionContext
- Datastores (data access)
- Scheduler (scheduled tasks)
- Metrics
- Circuit breakers
- HTTP clients
- PKI and certificates
- WASM runtime
- Vaults (secrets)
- Admin extensions

**Key components**:

```scala
class Env {
  // Actor system
  val otoroshiActorSystem: ActorSystem
  val otoroshiExecutionContext: ExecutionContext
  val otoroshiScheduler: Scheduler
  val otoroshiMaterializer: Materializer

  // Configuration
  val configuration: Configuration
  val configurationJson: JsObject

  // Storage
  val datastores: DataStores

  // Metrics
  val metrics: Metrics

  // PKI
  val pki: BouncyCastlePki

  // WASM
  val wasmIntegration: WasmIntegration

  // Vaults
  val vaults: Vaults

  // Proxy state
  val proxyState: NgProxyState

  // Tunnels
  val tunnelManager: TunnelManager

  // Extensions
  val adminExtensions: AdminExtensions

  // Circuit breakers
  val circuitBreakersHolder: CircuitBreakersHolder

  // Scripts
  val scriptManager: ScriptManager
  val scriptCompiler: ScriptCompiler
}
```

**Auxiliary classes**:
- `JavaVersion` - Java version information
- `OS` - Operating system information
- `SidecarConfig` - Sidecar mode configuration
- `ElSettings` - Expression Language settings

---

## Directory `health/` - Health Checks

### `healthchecker.scala` (~300+ lines)
**Role**: Periodic backend health verification.

**Main classes**:
- `HealthCheckerActor` - Akka actor managing health checks
- `HealthCheckLogic` - Verification logic

**Actor messages**:
- `StartHealthCheck` - Start checks
- `ReStartHealthCheck` - Restart checks
- `CheckFirstService` - Check a service

**Features**:

1. **HTTP verification**:
   - GET on configured health check URL
   - Otoroshi headers support (state, claim)
   - Configurable timeout

2. **Status determination**:
   - `GREEN` - Healthy backend (2xx-4xx with logic check OK)
   - `YELLOW` - Partially healthy
   - `RED` - Backend error (5xx or timeout)

3. **Advanced verification**:
   - List of statuses considered healthy
   - List of statuses considered unhealthy
   - Regex on response content

4. **Events**:
   - `HealthCheckEvent` emitted on each check
   - Health state history

**Health check configuration**:
```scala
HealthCheck(
  enabled: Boolean,
  url: String,              // URL to call
  timeout: Long,            // Timeout in ms
  logicCheck: Boolean,      // Check return header
  healthyStatuses: Seq[Int],    // Statuses considered OK
  unhealthyStatuses: Seq[Int],  // Statuses considered KO
  healthyRegexChecks: Seq[String],   // Regex for healthy
  unhealthyRegexChecks: Seq[String]  // Regex for unhealthy
)
```

---

## Relationships Between These Components

```
                    ┌──────────┐
                    │   Env    │
                    │ (global) │
                    └────┬─────┘
          ┌──────────────┼──────────────┐
          ▼              ▼              ▼
    ┌──────────┐   ┌──────────┐   ┌──────────┐
    │    EL    │   │  Health  │   │  Config  │
    │ (templ.) │   │ Checker  │   │  + Data  │
    └──────────┘   └──────────┘   └──────────┘
```

The `Env` is injected everywhere and provides access to all application components.
