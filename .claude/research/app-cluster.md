# Directory `app/cluster/`

## Overview

This directory contains the implementation of Otoroshi's **distributed cluster mode**, allowing separation of the control plane (leader) from the data plane (workers).

## Single File

### `cluster.scala` (~3000+ lines)
**Role**: Complete Otoroshi clustering implementation.

## Cluster Modes

```scala
sealed trait ClusterMode
object ClusterMode {
  case object Off    extends ClusterMode  // No clustering
  case object Leader extends ClusterMode  // Control plane
  case object Worker extends ClusterMode  // Data plane
}
```

## Architecture

```
┌─────────────────────────────────────────┐
│              LEADER                      │
│  (Configuration, global state)          │
│                                          │
│  - Stores configuration                 │
│  - Exposes administration API           │
│  - Synchronizes state to workers        │
└─────────────┬───────────────────────────┘
              │ HTTP/WebSocket (polling or push)
    ┌─────────┴─────────┐
    │                   │
    ▼                   ▼
┌───────────┐     ┌───────────┐
│  WORKER   │     │  WORKER   │
│           │     │           │
│ - Proxy   │     │ - Proxy   │
│ - Local   │     │ - Local   │
│   cache   │     │   cache   │
└───────────┘     └───────────┘
```

## Configuration Classes

### `LeaderConfig`
```scala
LeaderConfig(
  name: String,              // Leader name
  urls: Seq[String],         // Listen URLs
  host: String,              // API hostname
  clientId: String,          // API Key ID
  clientSecret: String,      // API Key secret
  groupingBy: Int,           // Sync batch size
  cacheStateFor: Long,       // State cache duration
  stateDumpPath: Option[String]  // State dump path
)
```

### `WorkerConfig`
```scala
WorkerConfig(
  name: String,              // Worker name
  retries: Int,              // Number of retries
  timeout: Long,             // Request timeout
  dataStaleAfter: Long,      // Duration before stale data
  dbPath: Option[String],    // Local DB path
  state: WorkerStateConfig,  // State sync config
  quotas: WorkerQuotasConfig,// Quotas sync config
  tenants: Seq[TenantId],    // Managed tenants
  swapStrategy: SwapStrategy,// Update strategy
  useWs: Boolean             // Use WebSocket
)
```

### `WorkerStateConfig`
```scala
WorkerStateConfig(
  timeout: Long,     // State request timeout
  pollEvery: Long,   // Polling interval
  retries: Int       // Number of retries
)
```

### `WorkerQuotasConfig`
```scala
WorkerQuotasConfig(
  timeout: Long,     // Quotas request timeout
  pushEvery: Long,   // Quotas push interval
  retries: Int       // Number of retries
)
```

## Relay Routing

Relay routing enables inter-zone/datacenter routing:

```scala
RelayRouting(
  enabled: Boolean,
  leaderOnly: Boolean,
  location: InstanceLocation,   // Instance location
  exposition: InstanceExposition // How instance is exposed
)

InstanceLocation(
  provider: String,    // "aws", "gcp", "local"
  zone: String,        // Availability zone
  region: String,      // Region
  datacenter: String,  // Datacenter
  rack: String         // Server rack
)
```

## Synchronization

### State (Leader → Workers)
1. Worker periodically polls the leader
2. Leader sends a diff or full state
3. Worker updates its local cache
4. WebSocket support for real-time sync

### Quotas (Workers → Leader)
1. Workers aggregate local counters
2. Periodic push to leader
3. Leader aggregates and redistributes

## Filtered Keys

Some keys are not synchronized between leader and workers:
- Events (audit, alerts)
- Backoffice user sessions
- Local statistics
- Local cache
- Migrations
- Local plugins

```scala
def filteredKey(key: String, env: Env): Boolean = {
  key.startsWith(s"${env.storageRoot}:noclustersync:") ||
  key.startsWith(s"${env.storageRoot}:events:") ||
  key.startsWith(s"${env.storageRoot}:users:backoffice") ||
  // ... etc
}
```

## Swap Strategies

```scala
sealed trait SwapStrategy
object SwapStrategy {
  case object Replace extends SwapStrategy  // Replace everything
  case object Merge   extends SwapStrategy  // Smart merge
}
```

## Key Points

1. **Control/data plane separation**: Leader manages config, workers handle traffic
2. **Resilience**: Workers can operate with stale data
3. **Scalability**: Add workers without reconfiguration
4. **Flexible sync**: HTTP polling or real-time WebSocket
5. **Multi-zone**: Relay routing for distributed deployments
6. **Local cache**: Each worker has a cache for performance

## Test Commands

```bash
# Start a leader
java -Dotoroshi.cluster.mode=leader \
     -Dotoroshi.cluster.autoUpdateState=true \
     -Dapp.storage=file \
     -jar otoroshi.jar

# Start a worker
java -Dotoroshi.cluster.mode=worker \
     -Dotoroshi.cluster.leader.url=http://leader:8080 \
     -Dotoroshi.cluster.worker.dbpath=./worker.db \
     -jar otoroshi.jar
```

## Relationships with Other Components

```
cluster/
    │
    ├── Used by → OtoroshiLoader.scala (bootstrap)
    ├── Used by → env/Env.scala (global state)
    ├── Used by → actions/privateapps.scala (session validation)
    │
    ├── Depends on → storage/ (data sync)
    └── Configures → All datastores in cluster mode
```
