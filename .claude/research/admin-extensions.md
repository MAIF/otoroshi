# Admin Extensions Reference

This document details the `AdminExtension` system in Otoroshi: the base framework, the lifecycle, and every concrete implementation found in the codebase.

## Table of Contents

- [Framework Overview](#framework-overview)
  - [AdminExtension Trait](#adminextension-trait)
  - [Extension Points](#extension-points)
  - [Route Types](#route-types)
  - [Loading and Lifecycle](#loading-and-lifecycle)
  - [Common Patterns](#common-patterns)
- [Concrete Implementations](#concrete-implementations)
  - [WorkflowAdminExtension](#workflowadminextension)
  - [CorazaWafAdminExtension](#corazawafadminextension)
  - [GreenScoreExtension](#greenscoreextension)
  - [HttpListenerAdminExtension](#httplisteneradminextension)
- [Summary Table](#summary-table)

---

## Framework Overview

### AdminExtension Trait

**File**: `app/next/extensions/extension.scala:150`

The `AdminExtension` trait is the base contract for all admin extensions. Every extension must implement:

```
trait AdminExtension {
  def env: Env                        // injected environment
  def id: AdminExtensionId            // unique identifier (e.g. "otoroshi.extensions.Foo")
  def enabled: Boolean                // whether the extension is active
  def name: String                    // display name
  def description: Option[String]     // optional description
}
```

### Extension Points

Each extension can override the following hooks:

| Hook | Return Type | Purpose |
|------|-------------|---------|
| `start()` | `Unit` | Called once at bootstrap. Subscribe to event streams, start static resources. |
| `stop()` | `Unit` | Called at shutdown. Unsubscribe, stop resources. |
| `syncStates()` | `Future[Unit]` | Called periodically to sync datastore -> in-memory state cache. |
| `entities()` | `Seq[AdminExtensionEntity[...]]` | Registers CRUD resources into the generic admin API. |
| `datastoreBuilders()` | `Map[String, DataStoresBuilder]` | Custom datastore backends. |
| `frontendExtensions()` | `Seq[AdminExtensionFrontendExtension]` | JS assets injected in the backoffice UI. |
| `globalConfigExtensions()` | `Seq[AdminExtensionGlobalConfigExtension]` | Global config contributions. |
| `vaults()` | `Seq[AdminExtensionVault]` | Secret vault providers. |
| `publicKeys()` | `Future[Seq[PublicKeyJwk]]` | Public JWK keys exposed by the extension. |
| `configuration` | `Configuration` | Reads config from `otoroshi.admin-extensions.configurations.<id_cleanup>`. |

### Route Types

Extensions can register routes at several levels. Each level has a standard variant and an **overrides** variant (which takes precedence over core Otoroshi routes):

| Method | Auth | Purpose |
|--------|------|---------|
| `wellKnownRoutes()` | None | Routes under `/.well-known/...` |
| `backofficePublicRoutes()` | None | Public backoffice routes |
| `backofficeAuthRoutes()` | `BackOfficeUser` | Backoffice routes requiring admin login |
| `adminApiRoutes()` | `ApiKey` | Admin API routes requiring an API key |
| `privateAppAuthRoutes()` | `PrivateAppsUser` | Private app routes requiring user login |
| `privateAppPublicRoutes()` | None | Public private app routes |
| `assets()` | None | Static asset serving |

Each of these also has an `*OverridesRoutes()` variant that can override core routes.

Route handler signatures vary by type but generally receive:
- `AdminExtensionRouterContext` with path parameters (named, splat)
- `RequestHeader`
- Authentication context (user/apikey depending on type)
- Optional `Source[ByteString, _]` body (when `wantsBody = true`)

### Loading and Lifecycle

**File**: `app/next/extensions/extension.scala:190`

Extensions are loaded via reflection by the `AdminExtensions` companion object:

1. `env.scriptManager.adminExtensionNames` provides a list of fully qualified class names
2. Each class is loaded and instantiated with `new ExtClass(env: Env)`
3. Only extensions where `enabled == true` are kept
4. All routers are built eagerly from the registered routes
5. Entities and datastores are aggregated across all extensions
6. An `extCache` (by `Class[_]`) allows fast lookup via `extension[T]`

Extensions are exposed as a single `AdminExtensions` manager that dispatches to the appropriate router depending on the request path and auth level.

### Common Patterns

All implementations follow the same internal architecture:

```
class MyExtension(val env: Env) extends AdminExtension {

  // 1. Datastores: Redis-backed persistence
  private lazy val datastores = new MyDatastores(env, id)

  // 2. State: In-memory cache (UnboundedTrieMap)
  private lazy val states = new MyState(env)

  // 3. syncStates bridges datastore -> state
  override def syncStates(): Future[Unit] = {
    for { items <- datastores.myStore.findAll() } yield {
      states.updateItems(items)
    }
  }

  // 4. entities() registers CRUD via GenericResourceAccessApiWithState
  override def entities() = Seq(AdminExtensionEntity(Resource(...)))
}
```

**State classes** use `UnboundedTrieMap[String, T]` with atomic `addAll`/`remAll` to update the cache:
```
private[ext] def updateItems(values: Seq[T]): Unit = {
  items.addAll(values.map(v => (v.id, v)))
       .remAll(items.keySet.toSeq.diff(values.map(_.id)))
}
```

**Entity registration** uses `GenericResourceAccessApiWithState` (or `WithStateAndWriteValidation`) which automatically provides:
- Full CRUD REST API (`GET`, `POST`, `PUT`, `DELETE` on the entity)
- Bulk operations
- Template endpoint
- State-backed reads (fast, in-memory)
- Optional write/delete validation hooks

---

## Concrete Implementations

### WorkflowAdminExtension

| | |
|-|-|
| **ID** | `otoroshi.extensions.Workflows` |
| **File** | `app/next/workflow/extension.scala:256` |
| **Enabled** | Always (`true`) |
| **Purpose** | Visual workflow engine for composing complex request/response processing pipelines |

**Entities**:

| Entity | Singular | Plural | Location |
|--------|----------|--------|----------|
| `Workflow` | `workflow` | `workflows` | `plugins.otoroshi.io` |

**Datastores**:
- `WorkflowConfigDataStore` - Workflow definitions (with `findAllAndFillSecrets` for vault integration)
- `KvPausedWorkflowSessionDatastore` - Paused workflow sessions (separate namespace)

**Routes**:

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/extensions/workflows/_test` | Backoffice | Test workflow execution (supports live SSE mode) |
| `POST` | `/apis/extensions/.../sessions/:wfId/:id/_resume` | ApiKey | Resume a paused workflow session |
| `GET` | `/apis/extensions/.../sessions/:wfId/:id` | ApiKey | Get a paused session |
| `DELETE` | `/apis/extensions/.../sessions/:wfId/:id` | ApiKey | Delete a paused session |
| `GET` | `/apis/extensions/.../sessions/:wfId` | ApiKey | List sessions for a workflow |
| `POST` | `/apis/extensions/.../sessions` | ApiKey | Create a new session |
| `GET` | `/apis/extensions/.../sessions` | ApiKey | List all sessions |

**Jobs**:
- `WorkflowJob` - Scheduled jobs defined inside workflow configs. Registered/unregistered dynamically on `syncStates()` via `env.jobManager`.

**Special Features**:
- WebSocket-based step-by-step debugger (`handleWorkflowDebug()`)
- Live SSE updates during test execution
- Vault secrets resolution (`${vault://...}`) in workflow payloads
- Initializes all workflow functions, operators, nodes, and categories on `start()`

---

### CorazaWafAdminExtension

| | |
|-|-|
| **ID** | `otoroshi.extensions.CorazaWAF` |
| **File** | `app/wasm/coraza.scala:381` |
| **Enabled** | Always (`true`) |
| **Purpose** | Web Application Firewall (WAF) via Coraza (ModSecurity-compatible) running in WebAssembly |

**Entities**:

| Entity | Singular | Plural | Location |
|--------|----------|--------|----------|
| `CorazaWafConfig` | `coraza-config` | `coraza-configs` | `coraza-waf.extensions.otoroshi.io` |

**Datastores**:
- `CorazaWafConfigDataStore` - WAF configuration definitions

**Routes**: None specific. Operates through route plugins `NgCorazaWAF` and `NgIncomingRequestValidatorCorazaWAF` which reference configs by ID.

**No start/stop logic**: Stateless extension. WASM modules are instantiated lazily by the plugins.

**Associated Plugins** (in `app/next/plugins/` scope but defined in `app/wasm/coraza.scala`):
- `NgCorazaWAF` (NgRequestTransformer) - Full WAF on request/response
- `NgIncomingRequestValidatorCorazaWAF` (NgIncomingRequestValidator) - WAF on incoming requests only

---

### GreenScoreExtension

| | |
|-|-|
| **ID** | `otoroshi.extensions.GreenScore` |
| **File** | `app/greenscore/extension.scala:163` |
| **Enabled** | Dev mode or `configuration.enabled == true` |
| **Purpose** | Ecological scoring of API routes based on resource efficiency rules |

**Entities**:

| Entity | Singular | Plural | Location |
|--------|----------|--------|----------|
| `GreenScoreEntity` | `green-score` | `green-scores` | `green-score.extensions.otoroshi.io` |

**Datastores**:
- `GreenScoreDataStore` - Score group definitions

**Routes**:

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/api/extensions/green-score` | ApiKey | Get all green scores with global score |
| `POST` | `/api/extensions/green-score` | ApiKey | Calculate scores for specific groups (by IDs in body) |
| `GET` | `/api/extensions/green-score/template` | ApiKey | Get scoring rules template (all 23 rules) |
| `GET` | `/api/extensions/green-score/efficiency/:group/:route` | ApiKey | Fetch route efficiency data with time range |

**Lifecycle**:
- `start()`: Subscribes `OtoroshiEventListener` actor to the analytics event stream
- `stop()`: Unsubscribes the listener

**Event-Driven Metrics**:
- `OtoroshiEventListener` (Akka Actor) listens to `GatewayEvent` messages
- Updates `EcoMetrics` in real-time (calls, overhead, duration, data in/out, headers)
- `updateFromQuotas(routeCallIncr)` called from the quota system

**23 Ecological Rules** (in `greenrules.scala`):
- Architecture: AR01-AR04
- Design: DE01-DE11
- Usage: US01-US07
- Logging: LO01

---

### HttpListenerAdminExtension

| | |
|-|-|
| **ID** | `otoroshi.extensions.HttpListeners` |
| **File** | `app/next/extensions/listeners.scala:217` |
| **Enabled** | Dev mode or `configuration.enabled == true` |
| **Purpose** | Dynamic HTTP listener management with multi-protocol and TLS support |

**Entities**:

| Entity | Singular | Plural | Location |
|--------|----------|--------|----------|
| `HttpListener` | `http-listener` | `http-listeners` | `http-listeners.proxy.otoroshi.io` |

**Datastores**:
- `HttpListenerDataStore` - Listener definitions

**Routes**:

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/extensions/cloud-apim/extensions/http-listeners/all` | Backoffice | List all listeners (static + dynamic) |

**Lifecycle**:
- `start()`: Reads static listener configs from `otoroshi.admin-extensions.configurations.otoroshi_extensions_httplisteners` (supports both `listeners` array and `listeners_json` string). Starts enabled listeners as Netty servers.
- `stop()`: Stops all static and dynamic servers.
- `syncStates()`: Syncs datastore to state, then calls `syncServerStates()` which:
  - Starts new listeners
  - Restarts changed listeners (config diff detection)
  - Stops listeners removed from datastore
  - Respects `config.enabled` flag

**Two categories of listeners**:
- **Static**: Loaded from config at startup, stored in `staticListeners` map
- **Dynamic**: Loaded from datastore on each sync, stored in `dynamicListeners` map

**Protocol support**: HTTP/1.1, HTTP/2, HTTP/3 (QUIC), H2C (HTTP/2 cleartext), TLS with client auth options.

---

## Summary Table

| Extension | ID | File | Enabled | Entities | Routes | Event-Driven | Jobs |
|-----------|----|------|---------|----------|--------|-------------|------|
| **Workflow** | `otoroshi.extensions.Workflows` | `app/next/workflow/extension.scala:256` | Always | `Workflow`, `PausedWorkflowSession` | 7 admin API + 1 backoffice | No | Yes (dynamic) |
| **Coraza WAF** | `otoroshi.extensions.CorazaWAF` | `app/wasm/coraza.scala:381` | Always | `CorazaWafConfig` | None (plugin-based) | No | No |
| **GreenScore** | `otoroshi.extensions.GreenScore` | `app/greenscore/extension.scala:163` | Config | `GreenScoreEntity` | 4 admin API | Yes (Akka actor) | No |
| **HTTP Listeners** | `otoroshi.extensions.HttpListeners` | `app/next/extensions/listeners.scala:217` | Config | `HttpListener` | 1 backoffice | No | No (sync-based) |

**Configuration-based enabling** uses the pattern:
```
env.isDev || configuration.getOptional[Boolean]("enabled").getOrElse(false)
```
Where `configuration` reads from `otoroshi.admin-extensions.configurations.<id_cleanup>`.

---

*All source references are relative to `otoroshi/` root.*
