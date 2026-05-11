# Directory `app/events/`

## Overview

This directory handles Otoroshi's **event system**: analytics, alerts, audit trails, and their export to different destinations.

## Files

### `analytics.scala` (~600+ lines)
**Role**: Analytics event definition and processing.

**Event types**:
```scala
trait AnalyticEvent {
  def `@type`: String           // Event type
  def `@id`: String             // Unique ID
  def `@timestamp`: DateTime    // Timestamp
  def `@service`: String        // Related service
  def `@serviceId`: String      // Service ID
  def toJson: JsValue           // JSON serialization
}
```

**Main events**:
- `GatewayEvent` - Processed HTTP request event
- `HealthCheckEvent` - Health check result
- `TcpEvent` - TCP connection event
- `GenericEvent` - Generic event

**Processing**:
- Akka Stream queue with buffer (50000 elements)
- Configurable batching (size and time window)
- Asynchronous enrichment (geolocation, user-agent, etc.)

---

### `alerts.scala` (~1000+ lines)
**Role**: Alert system for critical events.

**Base trait**:
```scala
trait AlertEvent extends AnalyticEvent {
  override def `@type`: String = "AlertEvent"
}
```

**Alert types**:

| Alert | Description |
|-------|-------------|
| `MaxConcurrentRequestReachedAlert` | Concurrent request limit reached |
| `CircuitBreakerOpenedAlert` | Circuit breaker opened |
| `CircuitBreakerClosedAlert` | Circuit breaker closed |
| `SessionDiscardedAlert` | User session discarded |
| `SessionsDiscardedAlert` | Multiple sessions discarded |
| `PanicModeAlert` | Panic mode activated |
| `ApiKeySecretWillRotate` | API key secret rotation imminent |
| `ApiKeySecretHasRotated` | Secret rotation completed |
| `QuotasAlmostExceededAlert` | Quotas almost exceeded |
| `CertExpiredAlert` | Certificate expired |
| `CertAlmostExpiredAlert` | Certificate expiring soon |
| `CertRenewalAlert` | Certificate renewed |
| `CertRenewalFailedAlert` | Renewal failed |
| `BlackListedBackOfficeUserAlert` | Blacklisted user attempted connection |
| `SnowMonkeyStartedAlert` | SnowMonkey (chaos) started |
| `SnowMonkeyStoppedAlert` | SnowMonkey stopped |
| `U2FAdminDeletedAlert` | U2F admin deleted |
| `AdminLoggedInAlert` | Admin logged in |
| `AdminFirstLogin` | Admin first login |
| `AdminLoggedOutAlert` | Admin logged out |
| `GlobalConfigModification` | Global configuration modified |

---

### `audit.scala`
**Role**: Audit events for modification traceability.

**Audit types**:
- `AuditEvent` - Base trait
- `AdminApiEvent` - Administration API call
- Configuration modifications
- User actions

---

### `kafka.scala` (~400+ lines)
**Role**: Event export to Apache Kafka.

**Configuration**:
```scala
KafkaConfig(
  servers: Seq[String],         // Kafka brokers
  topic: String,                // Destination topic
  keyPass: Option[String],      // Key password
  keystore: Option[String],     // SSL keystore
  truststore: Option[String],   // SSL truststore
  mtlsConfig: MtlsConfig,       // mTLS config
  securityProtocol: String,     // PLAINTEXT, SSL, SASL_SSL
  saslConfig: Option[SaslConfig], // SASL config
  hostValidation: Boolean       // SSL hostname validation
)
```

**SASL support**:
```scala
SaslConfig(
  username: String,
  password: String,
  mechanism: String,      // PLAIN, SCRAM-SHA-256, etc.
  jaasConfig: Option[String]
)
```

---

### `pulsar.scala`
**Role**: Export to Apache Pulsar.

**Configuration**:
```scala
PulsarConfig(
  uri: String,            // Pulsar URL
  tenant: String,
  namespace: String,
  topic: String,
  mtlsConfig: MtlsConfig  // mTLS for connection
)
```

---

### `statsd.scala`
**Role**: Metrics export to StatsD/Datadog.

**Features**:
- Request counters
- Latencies (histograms)
- Errors
- Datadog tags support

---

### `OtoroshiEventsActor.scala`
**Role**: Central event dispatch actor.

**Responsibilities**:
- Receive events of all types
- Dispatch to configured exporters
- Manage buffering and batching
- Retry on failure

---

### `impl/ElasticAnalytics.scala`
**Role**: Export to Elasticsearch.

**Features**:
- Bulk indexing
- Temporal index patterns (daily, monthly)
- Automatic mapping
- API key and mTLS support

---

### `impl/WebHookAnalytics.scala`
**Role**: Export via HTTP webhooks.

**Configuration**:
```scala
Webhook(
  url: String,
  headers: Map[String, String],
  mtlsConfig: MtlsConfig
)
```

---

## Architecture

```
                    ┌──────────────────┐
                    │  Gateway Event   │
                    │  Health Event    │
                    │  Alert Event     │
                    │  Audit Event     │
                    └────────┬─────────┘
                             │
                             ▼
                    ┌──────────────────┐
                    │ OtoroshiEvents   │
                    │     Actor        │
                    └────────┬─────────┘
                             │
        ┌────────────────────┼────────────────────┐
        ▼                    ▼                    ▼
  ┌──────────┐        ┌──────────┐        ┌──────────┐
  │  Kafka   │        │ Elastic  │        │ Webhook  │
  │ Exporter │        │ Exporter │        │ Exporter │
  └──────────┘        └──────────┘        └──────────┘
        │                    │                    │
        ▼                    ▼                    ▼
  ┌──────────┐        ┌──────────┐        ┌──────────┐
  │  Kafka   │        │Elastic-  │        │  HTTP    │
  │  Cluster │        │  search  │        │ Endpoint │
  └──────────┘        └──────────┘        └──────────┘
```

## Data Exporters

Data Exporters are configurable in the admin UI and support:

| Type | Description |
|------|-------------|
| `elastic` | Elasticsearch |
| `kafka` | Apache Kafka |
| `pulsar` | Apache Pulsar |
| `webhook` | HTTP POST |
| `file` | Local file |
| `s3` | Amazon S3 |
| `mailer` | Email |
| `console` | Console (debug) |
| `custom` | Custom plugin |

## Key Points

1. **Rich events**: Each request generates a detailed event
2. **Multi-export**: An event can be sent to multiple destinations
3. **Asynchronous**: Non-blocking processing via Akka Streams
4. **Enrichment**: Automatic geolocation, user-agent parsing
5. **Filtering**: Ability to filter exported events
