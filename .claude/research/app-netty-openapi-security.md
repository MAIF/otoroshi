# Directories `app/netty/`, `app/openapi/`, `app/security/`

## Directory `netty/` - Alternative Netty HTTP Server

### Overview
Alternative HTTP server implementation using **Reactor Netty** instead of Akka HTTP. Supports HTTP/3 (QUIC).

### Files

#### `netty.scala` (~800+ lines)
**Role**: Reactor Netty HTTP server.

**Main class**: `ReactorNettyServer`

```scala
case class ReactorNettyServerConfig(
  enabled: Boolean,
  newEngineOnly: Boolean,
  httpPort: Int,
  httpsPort: Int,
  wiretap: Boolean,           // Network debug
  accessLog: Boolean,         // Access logs
  cipherSuites: Seq[String],  // Crypto suites
  protocols: Seq[String],     // TLS 1.2, 1.3
  idleTimeout: Duration,
  parser: ParserConfig,
  nThread: Option[Int],       // Event loop threads
  native: NativeConfig        // Native transport (epoll, kqueue)
)
```

**Features**:
- HTTP/1.1, HTTP/2
- WebSocket
- TLS with dynamic SNI
- Native transport Linux (epoll) / macOS (kqueue)
- Reactive streaming

#### `http3.scala`
**Role**: HTTP/3 (QUIC) support via Netty Incubator.

**Features**:
- QUIC protocol
- 0-RTT handshake
- Multiplexing without head-of-line blocking

#### `h3client.scala`
**Role**: HTTP/3 client for calls to QUIC backends.

#### `handlers.scala`, `websockets.scala`
**Role**: Handlers for HTTP requests and WebSocket.

---

## Directory `openapi/` - OpenAPI Generation

### Overview
Automatic generation of OpenAPI 3.0 specifications and Kubernetes CRDs.

### Files

#### `openapi.scala` (~1500+ lines)
**Role**: OpenAPI spec generation by introspection.

**Main class**: `OpenApiGenerator`

**Configuration**:
```scala
case class OpenApiGeneratorConfig(
  filePath: String,
  banned: Seq[String],           // Classes to exclude
  descriptions: Map[String, String],
  bulkControllerMethods: Seq[String],
  crudControllerMethods: Seq[String],
  add_schemas: JsObject,         // Additional schemas
  merge_schemas: JsObject,       // Schemas to merge
  fields_rename: JsObject,       // Field renaming
  add_fields: JsObject           // Additional fields
)
```

**Features**:
- Introspection via ClassGraph
- JSON schema generation from Scala case classes
- `@Api`, `@ApiOperation` annotation detection
- Scala generics support
- Generation from Play routes

#### `crds.scala`
**Role**: Kubernetes Custom Resource Definitions generation.

**Features**:
- CRD generation from Otoroshi entities
- Validation webhooks
- Multi-version support

---

## Directory `security/` - Security Utilities

### Overview
Cryptographic utilities and Otoroshi JWT claims management.

### Files

#### `claim.scala` (~100 lines)
**Role**: Otoroshi JWT claims management (`Otoroshi-Claim` header).

**Main class**: `OtoroshiClaim`

```scala
case class OtoroshiClaim(
  iss: String,          // Issuer ("Otoroshi")
  sub: String,          // Subject (API key clientId)
  aud: String,          // Audience (target service)
  exp: Long,            // Expiration timestamp
  iat: Long,            // Issued at timestamp
  jti: String,          // Unique JWT ID
  metadata: JsObject    // Additional claims
)
```

**Methods**:
```scala
def serialize(jwtSettings: AlgoSettings): String  // Generate signed JWT
def withClaim(name: String, value: String): OtoroshiClaim
def withClaims(claims: JsValue): OtoroshiClaim
```

**Usage**: This claim is injected into requests to backends to prove the request has been validated by Otoroshi.

#### `IdGenerator.scala`
**Role**: Unique identifier generation.

**Methods**:
```scala
object IdGenerator {
  def uuid: String              // UUID v4
  def token: String             // Alphanumeric token
  def token(length: Int): String
  def nextId(): Long            // Snowflake ID
  def nextIdStr(): String
}
```

**Snowflake algorithm**:
- 41 bits timestamp
- 10 bits worker ID
- 12 bits sequence
- Generates ~4096 IDs/ms/worker

#### `Auth0.scala`
**Role**: Auth0 integration utilities.

#### `ClaimCrypto.scala`
**Role**: Claims encryption/decryption.

---

## Relationships

```
netty/
    │
    ├── Alternative to → Akka HTTP (default server)
    ├── Uses → next/proxy/engine.scala (ProxyEngine)
    └── Configures → DynamicSSLEngineProvider (TLS)

openapi/
    │
    ├── Generates → /api/openapi.json
    ├── Generates → kubernetes/crds/
    └── Reads → models/, next/models/ (entities)

security/
    │
    ├── Used by → gateway/ (claims injection)
    ├── Used by → actions/api.scala (validation)
    └── Used by → plugins/ (auth)
```

## Key Points

**Netty**:
- High-performance alternative to Akka HTTP
- HTTP/3 support (experimental)
- Native transports (epoll/kqueue)

**OpenAPI**:
- Automatic case class introspection
- Kubernetes CRD generation
- Auto-generated API documentation

**Security**:
- JWT claims for secure communication
- Snowflake IDs for distributed uniqueness
- Centralized crypto utilities
