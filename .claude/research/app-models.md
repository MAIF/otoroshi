# Directory `app/models/`

## Overview

This directory contains all Otoroshi **data entities** (models). Each file defines an entity type with its JSON serialization and persistence.

**Note**: New entities for the "next" engine are in `app/next/models/`.

## Files

### `apikey.scala` (~1200+ lines)
**Role**: API key management for client authentication.

**Main entity**: `ApiKey`
```scala
case class ApiKey(
  clientId: String,              // Unique identifier
  clientSecret: String,          // Auth secret
  clientName: String,            // Descriptive name
  description: String,           // Description
  authorizedEntities: Seq[EntityIdentifier], // Authorized routes/services
  enabled: Boolean,              // Active or not
  readOnly: Boolean,             // Read only
  allowClientIdOnly: Boolean,    // Auth without secret
  throttlingQuota: Long,         // Per-window quota
  dailyQuota: Long,              // Daily quota
  monthlyQuota: Long,            // Monthly quota
  constrainedServicesOnly: Boolean,
  restrictions: ApiKeyRestrictions,  // IP, path restrictions, etc.
  rotation: ApiKeyRotation,          // Secret rotation config
  validUntil: Option[DateTime],      // Expiration date
  tags: Seq[String],
  metadata: Map[String, String],
  location: EntityLocation           // Tenant/Team
)
```

**Associated classes**:
- `RemainingQuotas` - Remaining quotas
- `ApiKeyRotation` - Automatic rotation configuration
- `ApiKeyRestrictions` - Access restrictions
- `EntityIdentifier` - Reference to service/route/group

---

### `descriptor.scala` (~3000+ lines)
**Role**: Service descriptors (legacy routing entity).

**Main entity**: `ServiceDescriptor`
```scala
case class ServiceDescriptor(
  id: String,
  groups: Seq[String],           // Service groups
  name: String,
  env: String,                   // Environment (prod, preprod, etc.)
  domain: String,                // Root domain
  subdomain: String,             // Subdomain
  targets: Seq[Target],          // Backend targets
  root: String,                  // Root path
  matchingRoot: Option[String],  // Matching path
  stripPath: Boolean,            // Strip matched path
  enabled: Boolean,
  privateApp: Boolean,           // Requires authentication
  authConfigRef: Option[String], // Auth module
  clientConfig: ClientConfig,    // HTTP client config
  healthCheck: HealthCheck,      // Health check config
  api: ApiDescriptor,            // API/OpenAPI exposure
  plugins: NgPlugins,            // Attached plugins
  // ... +50 other fields
)
```

**Associated classes**:
- `Target` - Backend target with TLS config
- `ServiceDescriptorQuery` - Service search query
- `ServiceLocation` - Hostname parsing
- `LoadBalancing` - Load balancing algorithms
  - `RoundRobin`, `Random`, `Sticky`, `IpAddressHash`
  - `BestResponseTime`, `WeightedBestResponseTime`
- `ClientConfig` - HTTP client configuration
- `HealthCheck` - Health check configuration
- `Canary` - Canary deployment configuration

---

### `config.scala` (~1500+ lines)
**Role**: Otoroshi global configuration.

**Main entity**: `GlobalConfig`
```scala
case class GlobalConfig(
  letsEncryptSettings: LetsEncryptSettings,
  lines: Seq[String],             // Environments (prod, preprod...)
  maintenanceMode: Boolean,
  enableEmbeddedMetrics: Boolean,
  streamEntityOnly: Boolean,
  autoLinkToDefaultGroup: Boolean,
  limitConcurrentRequests: Boolean,
  maxConcurrentRequests: Long,
  maxHttp10ResponseSize: Long,
  useCircuitBreakers: Boolean,
  apiReadOnly: Boolean,           // Read-only API
  u2fLoginOnly: Boolean,          // Mandatory WebAuthn auth
  throttlingQuota: Long,          // Global quota
  perIpThrottlingQuota: Long,     // Per-IP quota
  elasticReadsConfig: Option[ElasticAnalyticsConfig],
  elasticWritesConfigs: Seq[ElasticAnalyticsConfig],
  proxies: Proxies,               // Global HTTP proxies
  plugins: NgPlugins,             // Global plugins
  userAgentSettings: UserAgentSettings,
  geolocationSettings: GeolocationSettings,
  tlsSettings: TlsSettings,
  autoCert: AutoCert,             // Auto cert generation config
  // ... +30 other fields
)
```

**Associated classes**:
- `ElasticAnalyticsConfig` - Elasticsearch config
- `IndexSettings` - Indexing parameters (shards, replicas)
- `TlsSettings` - Global TLS parameters
- `AutoCert` - Auto certificate generation
- `Proxies` - HTTP proxy configuration

---

### `JWTVerifier.scala` (~2000+ lines)
**Role**: JWT verifier configuration.

**Main entity**: `GlobalJwtVerifier`
```scala
case class GlobalJwtVerifier(
  id: String,
  name: String,
  desc: String,
  strict: Boolean,
  source: JwtTokenLocation,      // Where to find the token
  algoSettings: AlgoSettings,    // Verification algorithm
  strategy: VerifierStrategy,    // Verification strategy
  // ...
)
```

**Associated classes**:
- `AlgoSettings` - Algorithm configuration (HSAlgoSettings, RSAlgoSettings, ESAlgoSettings, etc.)
- `JwtTokenLocation` - Token source (header, query, cookie)
- `VerifierStrategy` - Strategy (PassThrough, Sign, Transform)
- `TransformSettings` - Token transformation

---

### `dataExporter.scala` (~500+ lines)
**Role**: Data exporter configuration.

**Main entity**: `DataExporterConfig`
```scala
case class DataExporterConfig(
  id: String,
  typ: DataExporterConfigType,    // Exporter type
  enabled: Boolean,
  name: String,
  desc: String,
  filtering: DataExporterConfigFiltering,  // Filters
  projection: JsObject,           // JSON projection
  config: Exporter,               // Type-specific config
  // ...
)
```

**Exporter types** (`DataExporterConfigType`):
- `Elastic` - Elasticsearch
- `Kafka` - Apache Kafka
- `Pulsar` - Apache Pulsar
- `Webhook` - HTTP webhook
- `File` - Local file
- `S3` - Amazon S3
- `Mailer` - Email
- `Console` - Console/logs
- `Custom` - Custom plugin

---

### `backofficeuser.scala` (~300 lines)
**Role**: Administration interface users.

**Main entity**: `BackOfficeUser`
```scala
case class BackOfficeUser(
  randomId: String,
  name: String,
  email: String,
  profile: JsValue,
  token: JsValue,              // OAuth token
  authConfigId: String,        // Used auth module
  simpleLogin: Boolean,
  metadata: Map[String, String],
  rights: UserRights,          // Access rights
  adminEntityValidators: Map[String, Seq[JsonValidator]],
  createdAt: DateTime,
  expiredAt: DateTime
)
```

---

### `privateappsuser.scala` (~250 lines)
**Role**: Protected private application users.

**Main entity**: `PrivateAppsUser`
```scala
case class PrivateAppsUser(
  randomId: String,
  name: String,
  email: String,
  profile: JsValue,
  token: JsValue,
  realm: String,              // Auth module
  authConfigId: String,
  otoroshiData: Option[JsValue],
  createdAt: DateTime,
  expiredAt: DateTime,
  metadata: Map[String, String],
  tags: Seq[String],
  location: EntityLocation
)
```

---

### `admins.scala` (~400 lines)
**Role**: Otoroshi administrators (internal users).

**Entities**:
- `SimpleOtoroshiAdmin` - Admin with username/password
- `WebAuthnOtoroshiAdmin` - Admin with WebAuthn/FIDO2

---

### `teams.scala` (~200 lines)
**Role**: Teams for multi-tenant organization.

**Main entity**: `Team`
```scala
case class Team(
  id: TenantId,
  tenant: TenantId,
  name: String,
  description: String,
  metadata: Map[String, String],
  tags: Seq[String]
)
```

---

### `group.scala` (~150 lines)
**Role**: Service groups for organization.

**Main entity**: `ServiceGroup`
```scala
case class ServiceGroup(
  id: String,
  name: String,
  description: String,
  tags: Seq[String],
  metadata: Map[String, String],
  location: EntityLocation
)
```

---

### `chaos.scala` (~300 lines)
**Role**: Chaos engineering configuration (SnowMonkey).

**Entities**:
- `ChaosConfig` - Main config
- `LatencyInjectionFaultConfig` - Latency injection
- `LargeRequestFaultConfig` - Large requests
- `LargeResponseFaultConfig` - Large responses
- `BadResponsesFaultConfig` - Error responses

---

### `cors.scala` (~100 lines)
**Role**: CORS configuration.

**Entity**: `CorsSettings`
```scala
case class CorsSettings(
  enabled: Boolean,
  allowOrigin: String,
  exposeHeaders: Seq[String],
  allowHeaders: Seq[String],
  allowMethods: Seq[String],
  excludedPatterns: Seq[String],
  maxAge: Option[Long],
  allowCredentials: Boolean
)
```

---

### Other Files

| File | Description |
|------|-------------|
| `api.scala` | REST API resource definitions |
| `canary.scala` | Canary deployment configuration |
| `key.scala` | Key and identifier utilities |
| `template.scala` | Configuration templates |
| `wasm.scala` | WASM plugin configuration |
| `draft.scala` | Configuration drafts |

---

## Common Traits

### `EntityLocationSupport`
All entities support multi-tenant location:
```scala
trait EntityLocationSupport {
  def location: EntityLocation
  def internalId: String
  def json: JsValue
  def theName: String
  def theDescription: String
  def theTags: Seq[String]
  def theMetadata: Map[String, String]
}

case class EntityLocation(
  tenant: TenantId,
  teams: Seq[TeamId]
)
```

### `BasicStore[T]`
Trait for entity persistence (see `storage/`).

---

## Relationships

```
models/
    │
    ├── Used by → controllers/ (REST API)
    ├── Used by → gateway/ (routing)
    ├── Used by → next/models/ (new models)
    │
    ├── Persisted in → storage/ (datastores)
    └── Serialized → JSON (Play JSON)
```
