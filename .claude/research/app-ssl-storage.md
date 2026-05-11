# Directories `app/ssl/` and `app/storage/`

## Directory `ssl/` - TLS/PKI Management

### Overview
Complete TLS certificate management, internal PKI, ACME/Let's Encrypt, and OCSP.

### Files

#### `ssl.scala` (~3000+ lines)
**Role**: `Cert` entity and TLS certificate management.

**Main entity**: `Cert`
```scala
case class Cert(
  id: String,
  name: String,
  description: String,
  chain: String,              // PEM certificate chain
  privateKey: String,         // PEM private key
  caRef: Option[String],      // Reference to parent CA
  domain: String,             // Certificate domain
  selfSigned: Boolean,
  ca: Boolean,                // Is it a CA
  valid: Boolean,             // Valid certificate
  exposed: Boolean,           // Exposed via /.well-known
  revoked: Boolean,
  autoRenew: Boolean,         // Automatic renewal
  letsEncrypt: Boolean,       // Managed by Let's Encrypt
  client: Boolean,            // Client certificate
  keypair: Boolean,           // Key pair only
  subject: String,
  sans: Seq[String],          // Subject Alternative Names
  from: DateTime,             // Validity start
  to: DateTime,               // Validity end
  entityMetadata: Map[String, String],
  tags: Seq[String],
  location: EntityLocation
)
```

**Associated classes**:
- `ClientAuth` - Client authentication modes (None, Want, Need, Dynamic)
- `DynamicSSLEngineProvider` - Dynamic SSL provider
- `FakeKeyStore` - Self-signed certificate generation

**Client auth modes**:
```scala
sealed trait ClientAuth
case object ClientAuthNone    // No client auth
case object ClientAuthWant    // Optional client auth
case object ClientAuthNeed    // Required client auth
case object ClientAuthDynamic // Route-based
```

#### `pki.scala` (~1200+ lines)
**Role**: Internal PKI (Public Key Infrastructure).

**Main class**: `BouncyCastlePki`

**Features**:
```scala
trait Pki {
  def genSelfSignedCA(query: GenCsrQuery): Future[Either[String, GenCertResponse]]
  def genSubCA(query: GenCsrQuery, ca: X509Certificate, ...): Future[...]
  def genCert(query: GenCsrQuery, ca: X509Certificate, ...): Future[...]
  def genKeyPair(query: GenKeyPairQuery): Future[Either[String, GenKeyPairResponse]]
  def genCsr(query: GenCsrQuery, keyPair: KeyPair): Future[Either[String, GenCsrResponse]]
  def signCsr(csr: PKCS10CertificationRequest, ca: X509Certificate, ...): Future[...]
}
```

**Supported algorithms**:
- RSA (2048, 4096, 8192 bits)
- ECDSA (P-256, P-384, P-521)
- EdDSA (Ed25519)

#### `ocsp.scala` (~400 lines)
**Role**: OCSP responder (Online Certificate Status Protocol).

**Features**:
- Revocation status verification
- OCSP response cache
- `/certificates/ocsp` endpoint

#### `dynkeymanager.scala`
**Role**: Dynamic KeyManager for SNI.

**Features**:
- SNI-based certificate selection
- Hot certificate reload
- Default certificate fallback

#### `dyn.scala`
**Role**: Dynamic SSL configuration.

#### `pem.scala`, `p12.scala`
**Role**: PEM and PKCS#12 format parsing.

#### `pkiModels.scala`
**Role**: Models for PKI operations.

```scala
case class GenCsrQuery(
  hosts: Seq[String],           // SANs
  subject: Option[String],      // Subject DN
  ca: Boolean,
  client: Boolean,
  duration: FiniteDuration,
  signatureAlg: String,         // SHA256WithRSA, etc.
  digestAlg: String,            // SHA-256, etc.
  keyPairType: KeyPairType,     // RSA, ECDSA, EdDSA
  keySize: Int                  // 2048, 4096, etc.
)
```

---

## Directory `storage/` - Data Persistence

### Overview
Multi-backend abstraction for data persistence. Supports Redis, PostgreSQL, Cassandra, In-memory, etc.

### Structure

```
storage/
├── storage.scala           # Base abstractions
├── drivers/                # Backend implementations
│   ├── inmemory/           # In-memory + file persistence
│   ├── lettuce/            # Redis (via Lettuce)
│   ├── rediscala/          # Redis (via Rediscala)
│   ├── cassandra/          # Apache Cassandra
│   ├── reactivepg/         # PostgreSQL (via Vertx)
│   └── generic/            # Generic driver
└── stores/                 # Per-entity datastores
    ├── KvApiKeyDataStore.scala
    ├── KvCertificateDataStore.scala
    ├── KvGlobalConfigDataStore.scala
    └── ... (20+ stores)
```

### Main Files

#### `storage.scala` (~400 lines)
**Role**: Base abstractions and interfaces.

**Main traits**:

```scala
trait DataStores {
  def redis: RedisLike
  def health(): Future[DataStoreHealth]

  // Per-entity datastores
  def apiKeyDataStore: ApiKeyDataStore
  def certificatesDataStore: CertificateDataStore
  def globalConfigDataStore: GlobalConfigDataStore
  def routeDataStore: NgRouteDataStore
  def serviceDescriptorDataStore: ServiceDescriptorDataStore
  // ... 30+ datastores

  // Import/Export
  def fullNdJsonImport(source: Source[JsValue, _]): Future[Unit]
  def fullNdJsonExport(group: Int, ...): Future[Source[JsValue, _]]
}

trait BasicStore[T] {
  def key(id: String): String
  def findAll(): Future[Seq[T]]
  def findById(id: String): Future[Option[T]]
  def set(value: T): Future[Boolean]
  def delete(id: String): Future[Boolean]
  def exists(id: String): Future[Boolean]
  def streamedFind(predicate: T => Boolean): Source[T, NotUsed]
}

trait RawDataStore {
  def get(key: String): Future[Option[ByteString]]
  def set(key: String, value: ByteString, ttl: Option[Long]): Future[Boolean]
  def del(keys: Seq[String]): Future[Long]
  def keys(pattern: String): Future[Seq[String]]
  def incr(key: String): Future[Long]
  // Redis-like operations
}
```

**Health states**:
```scala
sealed trait DataStoreHealth
case object Healthy     extends DataStoreHealth
case object Unhealthy   extends DataStoreHealth
case object Unreachable extends DataStoreHealth
```

### Drivers

#### `drivers/inmemory/`
- `SwappableInMemoryRedis` - In-memory Redis with swap
- `persistence.scala` - Disk persistence (JSON file)
- Swap strategies: `Replace`, `Merge`

#### `drivers/lettuce/`
- `LettuceRedis` - Redis client via Lettuce (recommended)
- Cluster, sentinel support
- Connection pooling

#### `drivers/rediscala/`
- `RediscalaRedis` - Redis client via Rediscala
- Legacy version

#### `drivers/cassandra/`
- `CassandraDataStores` - Apache Cassandra
- Horizontal scalability

#### `drivers/reactivepg/`
- `ReactivePostgresRedis` - PostgreSQL via Vertx
- Non-blocking reactive queries

### Stores

Each entity has its datastore with business logic:

| Store | Entity | Key |
|-------|--------|-----|
| `KvApiKeyDataStore` | ApiKey | `{root}:apikey:{id}` |
| `KvCertificateDataStore` | Cert | `{root}:cert:{id}` |
| `KvGlobalConfigDataStore` | GlobalConfig | `{root}:config` |
| `KvServiceDescriptorDataStore` | ServiceDescriptor | `{root}:services:{id}` |
| `KvRouteDataStore` | NgRoute | `{root}:routes:{id}` |
| `KvAuthConfigsDataStore` | AuthModuleConfig | `{root}:auth:{id}` |
| `KvGlobalJwtVerifierDataStore` | JwtVerifier | `{root}:jwt-verifiers:{id}` |
| ... | ... | ... |

---

## Storage Configuration

```bash
# In-memory (development)
APP_STORAGE=inmemory

# Redis (recommended for production)
APP_STORAGE=lettuce
REDIS_URL=redis://localhost:6379

# PostgreSQL
APP_STORAGE=postgresql
PG_URL=postgresql://localhost:5432/otoroshi

# Cassandra
APP_STORAGE=cassandra
CASSANDRA_HOSTS=localhost

# File (embedded)
APP_STORAGE=file
```

## Relationships

```
ssl/
    │
    ├── Stored in → storage/stores/KvCertificateDataStore
    ├── Used by → gateway/ (TLS termination)
    ├── Used by → netty/ (Netty server)
    └── Configured via → jobs/certs.scala (auto-renewal)

storage/
    │
    ├── Used by → All entities
    ├── Configured by → env/Env.scala
    └── Synchronized by → cluster/ (cluster mode)
```

## Key Points

**SSL**:
- Complete internal PKI
- Dynamic SNI without restart
- ACME/Let's Encrypt support
- Integrated OCSP responder
- Multiple algorithms (RSA, ECDSA, EdDSA)

**Storage**:
- Transparent multi-backend abstraction
- Unified Redis-like API
- Streaming for large collections
- ndjson import/export
- Automatic cluster sync
