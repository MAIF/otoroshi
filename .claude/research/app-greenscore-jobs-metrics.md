# Directories `app/greenscore/`, `app/jobs/`, `app/metrics/`

## Directory `greenscore/` - Ecological Score

### Overview
System for calculating an **ecological score (GreenScore)** to evaluate the environmental impact of routes and APIs.

### Files

#### `ecometrics.scala` (~400+ lines)
**Role**: Ecological metrics calculation.

**Main classes**:
```scala
// Thresholds registry per route
class ThresholdsRegistry {
  def updateRoute(routeCallIncr: RouteCallIncr)
  def route(routeId: String): Option[RouteReservoirs]
}

// Data reservoirs per route
case class RouteReservoirs(
  overhead: UniformReservoir,
  duration: UniformReservoir,
  backendDuration: UniformReservoir,
  calls: UniformReservoir,
  dataIn: UniformReservoir,
  dataOut: UniformReservoir,
  headersOut: UniformReservoir,
  headersIn: UniformReservoir
)

// Scoring bounds
case class DynamicTripleBounds(...)
case class ScalingRouteReservoirs(...)
```

**Evaluated metrics**:
- Overhead (Otoroshi processing time)
- Duration (total duration)
- Backend duration (backend time)
- Calls (number of calls)
- Data In/Out (data volume)
- Headers In/Out (headers size)

#### `greenrules.scala`
**Role**: Evaluation rules for scoring.

#### `extension.scala`
**Role**: Admin extension for GreenScore.

**Final score**: Grade from A to G with color (green → red).

---

## Directory `jobs/` - Scheduled Tasks

### Overview
Background scheduled jobs for maintenance and synchronization.

### Files

#### `certs.scala` (~250 lines)
**Role**: Automatic certificate generation and renewal.

**Jobs**:
- `InitialCertsJob` - Generates initial certificates at startup
  - Otoroshi root CA
  - Wildcard certificate for domain
  - Interval: 24h

#### `apikeysRotation.scala`
**Role**: Automatic API Key secret rotation.

**Features**:
- Scheduled secret rotation
- Grace period for migration
- Pre-expiration alerts

#### `updates.scala`
**Role**: Otoroshi update checks.

**Job**: `SoftwareUpdatesJobs`
- Periodically checks for new versions
- Compares with current version
- Notifies via admin UI

#### `eventstore.scala`
**Role**: Event store cleanup.

**Features**:
- Old events purge
- Configurable retention limit

#### `stateexporter.scala`
**Role**: Periodic state export (backup).

**Features**:
- Automatic export to S3/File
- Complete configuration backup

#### `servicedescriptor.scala`
**Role**: Service descriptor maintenance (legacy).

#### `newengine.scala`
**Role**: Job for the new proxy engine.

```scala
object NewEngine {
  // Feature flag to enable new engine
  def enabled(implicit env: Env): Boolean
}
```

#### `reporting.scala` (~300 lines)
**Role**: Anonymous reporting (opt-in) to Otoroshi.

**Configuration**:
```scala
AnonymousReportingJobConfig(
  enabled: Boolean,         // Enable reporting
  url: String,              // Ingestion URL
  timeout: Duration,        // Request timeout
  proxy: Option[WSProxyServer],
  tlsConfig: NgTlsConfig,
  additionalData: JsObject  // Additional data
)
```

**Collected data** (anonymized):
- Otoroshi version
- Cluster mode
- Aggregated usage statistics
- Number of routes, API keys, etc.

---

## Directory `metrics/` - Metrics and Monitoring

### Overview
Metrics system for monitoring and observability.

### Files

#### `metrics.scala` (~800+ lines)
**Role**: Metrics collection and exposure.

**Main class**: `Metrics`

**Registries**:
```scala
class Metrics {
  // Dropwizard Metrics (Codahale)
  private val metricRegistry: SemanticMetricRegistry
  private val jmxRegistry: MetricRegistry

  // OpenTelemetry (optional)
  private val openTelemetryRegistry: Option[OpenTelemetryMeter]
}
```

**Collected JVM metrics**:
- `jvm.memory` - Memory usage
- `jvm.thread` - Thread states
- `jvm.gc` - Garbage collection
- `jvm.cpu.usage` - CPU usage
- `jvm.heap.used/size` - Java heap

**Otoroshi metrics**:
- `calls` - Call count
- `dataIn/dataOut` - Data volume
- `rate` - Request rate
- `duration` - Request duration
- `overhead` - Otoroshi overhead
- `concurrentHandledRequests` - Concurrent requests

**Methods**:
```scala
trait TimerMetrics {
  def withTimer[T](name: String)(f: => T): T
  def withTimerAsync[T](name: String)(f: => Future[T]): Future[T]
}
```

**Exposure**:
- `/metrics` - Prometheus format
- JMX Reporter
- StatsD/Datadog
- OpenTelemetry OTLP

#### `opentelemetry/` (subdirectory)
**Role**: OpenTelemetry integration.

**Components**:
- `OpenTelemetryMeter` - OTEL metrics
- `OtlpSettings` - OTLP configuration
- Exporters to Jaeger, Zipkin, etc.

---

## Relationships Between Components

```
jobs/
    │
    ├── certs.scala ─────► ssl/pki (certificate generation)
    ├── apikeysRotation ─► models/apikey (secret rotation)
    ├── updates.scala ───► WebFetch (version check)
    ├── stateexporter ───► storage/ (state export)
    └── reporting ───────► External HTTP (telemetry)

metrics/
    │
    ├── Exposed via ─► controllers/HealthController (/metrics)
    ├── Used by ─► gateway/ (latency measurement)
    └── Exported to ─► Prometheus, Datadog, OTEL

greenscore/
    │
    ├── Fed by ─► events/ (request metrics)
    └── Displayed in ─► Admin UI
```

## Job Configuration

Jobs use the `script/Job` framework:
```scala
trait Job {
  def uniqueId: JobId
  def kind: JobKind          // ScheduledOnce, ScheduledEvery
  def starting: JobStarting  // Automatically, FromConfiguration
  def instantiation: JobInstantiation  // OnePerCluster, OnePerInstance
  def initialDelay: Option[FiniteDuration]
  def interval: Option[FiniteDuration]
  def jobRun(ctx: JobContext)(implicit env: Env): Future[Unit]
}
```
