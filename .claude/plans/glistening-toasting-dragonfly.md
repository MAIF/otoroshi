# Plan: Remote Catalogs Extension for Otoroshi

## Context

Otoroshi needs a way to synchronize entities (routes, backends, apikeys, certificates, etc.) from remote sources in read-only mode. This is infrastructure-as-code where Otoroshi pulls entity definitions from remote locations (GitHub, GitLab, S3, HTTP, file) and deploys them locally, with reconciliation to keep things in sync.

The extension follows the same patterns as the existing Workflow extension (`app/next/workflow/`).

---

## File Structure

```
NEW FILES:
  otoroshi/app/next/catalogs/
    extension.scala    -- Extension class, RemoteCatalog entity, datastore, state, routes, scheduling
    api.scala          -- CatalogSource trait, CatalogSources registry, RemoteCatalogEngine (reconciliation), report types
    sources.scala      -- Source implementations: file, http, github, gitlab, s3
    plugins.scala      -- 3 plugins: deploy single, deploy many, deploy webhook

  otoroshi/javascript/src/extensions/catalogs/
    index.js           -- UI page: list/edit catalogs, deploy button, test/dry-run button

MODIFIED FILES:
  otoroshi/javascript/src/backoffice.js  -- Register the catalogs extension (add import + setupRemoteCatalogsExtension call)
```

---

## Phase 1: `extension.scala` -- Entity, Datastore, State, Extension, Job

### Entity: `RemoteCatalog`

Pattern: `app/next/workflow/extension.scala:92-170` (Workflow entity)

```scala
package otoroshi.next.catalogs

case class RemoteCatalogScheduling(
  enabled: Boolean = false,
  kind: JobKind = JobKind.ScheduledEvery,
  instantiation: JobInstantiation = JobInstantiation.OneInstancePerOtoroshiInstance,
  initialDelay: Option[FiniteDuration] = None,
  interval: Option[FiniteDuration] = None,
  cronExpression: Option[String] = None,
  deployArgs: JsObject = Json.obj()
)

case class RemoteCatalog(
  location: EntityLocation,
  id: String,
  name: String,
  description: String,
  tags: Seq[String],
  metadata: Map[String, String],
  enabled: Boolean,
  sourceKind: String,
  sourceConfig: JsObject,
  scheduling: RemoteCatalogScheduling,
  testDeployArgs: JsObject
) extends EntityLocationSupport
```

JSON serialization uses snake_case keys: `source_kind`, `source_config`, `scheduling.deploy_args`, `test_deploy_args`.

### Datastore

Pattern: `app/next/workflow/extension.scala:167-237`

- `trait RemoteCatalogDataStore extends BasicStore[RemoteCatalog]`
- `class KvRemoteCatalogDataStore` with `RedisLikeStore[RemoteCatalog]`
- Key: `s"${_env.storageRoot}:extensions:${extensionId.cleanup}:remote-catalogs:$id"`

### State

Pattern: `app/next/workflow/extension.scala:239-249`

- `class RemoteCatalogAdminExtensionState` with `UnboundedTrieMap[String, RemoteCatalog]`

### Extension Class

Pattern: `app/next/workflow/extension.scala:251-600`

```scala
class RemoteCatalogAdminExtension(val env: Env) extends AdminExtension {
  override def id = AdminExtensionId("otoroshi.extensions.RemoteCatalogs")
  override def name = "Otoroshi Remote Catalogs extension"
  override def enabled = true
}
```

Key methods:
- `start()`: call `CatalogSources.initDefaults()`
- `syncStates()`: load from datastore, update state, startJobsIfNeeded
- `entities()`: register RemoteCatalog via `GenericResourceAccessApiWithState`
  - group: `"catalogs.otoroshi.io"`, pluralName: `"remote-catalogs"`, singularName: `"remote-catalog"`
- `frontendExtensions()`: point to `/__otoroshi_assets/javascripts/extensions/catalogs.js`

### Admin API Routes

- `POST /api/extensions/remote-catalogs/_deploy` -- deploy catalogs from body `[{id, args}]`
- `POST /api/extensions/remote-catalogs/_test` -- dry-run, returns reconciliation report

### Backoffice Routes

- `POST /extensions/remote-catalogs/_deploy` -- same logic, backoffice auth
- `POST /extensions/remote-catalogs/_test` -- dry-run with backoffice auth

### Job: `RemoteCatalogJob`

Pattern: `app/next/workflow/job.scala`

- Unique ID: `JobId(s"io.otoroshi.next.catalogs.RemoteCatalogJob#${ref}")`
- `jobRun`: look up catalog from state, call `engine.deploy(catalog, catalog.scheduling.deployArgs)`
- `startJobsIfNeeded()`: same pattern as workflow (`handledJobs` TrieMap, register/unregister)

---

## Phase 2: `api.scala` -- Core Logic

### RemoteEntity

```scala
case class RemoteEntity(id: String, kind: String, source: String, syncAt: DateTime, content: JsObject)
```

### CatalogSource Trait

```scala
trait CatalogSource {
  def sourceKind: String
  def supportsWebhook: Boolean
  def webhookDeploySelect(possibleCatalogs: Seq[RemoteCatalog], payload: JsValue): Future[Either[JsValue, Seq[RemoteCatalog]]]
  def webhookDeployExtractArgs(catalog: RemoteCatalog, payload: JsValue): Future[Either[JsValue, JsObject]]
  def fetch(catalog: RemoteCatalog, args: JsObject)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Seq[RemoteEntity]]]
}
```

### CatalogSources Registry

```scala
object CatalogSources {
  private val possibleSources = new TrieMap[String, CatalogSource]()
  def initDefaults(): Unit = { /* register file, http, github, gitlab, s3 */ }
  def registerSource(name: String, source: CatalogSource): Unit
  def source(name: String): Option[CatalogSource]
}
```

### Remote Entities upsert/delete

for any given remote entity, you can upsert it by finding its `Resource` in `env.allResources.resources`. The `kind` of a remote entity can be `<resource-kind>` or `<resource_group>/<resource_kind>`. Then once you found the right Resource, you can use `resource.access.validateToJson` and `resource.access.findOne` and `resource.access.create`/`resource.access.update` to upsert it (see `app/api/api.scala` line 2364, the `upsert` method). You also have `resource.access.deleteOne` to delete an entity

### RemoteCatalogEngine

Core reconciliation class. Access all resources via `env.allResources.resources` (from `OtoroshiResources` class at `api.scala:520`) + `env.adminExtensions.resources()`. For reconciliation you can use `resource.access.all()` to read all entities of a kind from RAM (avoiding costly db call) to filter based on the metadata

**`deploy(catalog, args)`**:
1. Concurrent deploy guard (`deployingCatalogs` TrieMap)
2. Look up source via `CatalogSources.source(catalog.sourceKind)`
3. Call `source.fetch(catalog, args)` to get `Seq[RemoteEntity]`
4. Call `reconcile(catalog, entities, dryRun = false)`

**`dryRun(catalog, args)`**: Same but `dryRun = true`

**`reconcile(catalog, remoteEntities, dryRun)`**:
1. Group remote entities by `kind` (export key)
2. For each kind, find matching `Resource` in `allResources`
3. For each remote entity:
   - Inject metadata `created_by -> remote_catalog=<catalog.id>` into the entity JSON
   - Use `resource.access.key(entityId)` to get the storage key
   - Check if entity exists locally (for create vs update reporting)
   - If not dryRun: `env.datastores.rawDataStore.set(key, enrichedJson.stringify.byteString, None)`
4. Handle deletions:
   - For each resource type, `findAll` and filter for entities with `created_by: remote_catalog=<id>` metadata
   - Delete entities whose IDs are no longer in the remote set
   - If not dryRun: `env.datastores.rawDataStore.del(Seq(key))`
5. Return `DeployReport` with per-kind counts (created, updated, deleted, errors)

### Report Types

```scala
case class ReconcileResult(entityKind: String, created: Int, updated: Int, deleted: Int, errors: Seq[String])
case class DeployReport(catalogId: String, results: Seq[ReconcileResult], timestamp: DateTime)
```

### RemoteContentParser

Parses fetched content into `Seq[RemoteEntity]`:
- **Export format** (JsObject): iterate over known export keys, extract arrays of entities
- **Array format** (JsArray): each item must have a `kind` field
- Both supported, auto-detected

---

## Phase 3: `sources.scala` -- Source Implementations

All sources implement `CatalogSource`. Non-webhook sources return `Left` for webhook methods.

### CatalogSourceFile
- `source_config`: `{ "path": "/path/to/entities.json" }`
- Read file from local filesystem, parse with `RemoteContentParser`

### CatalogSourceHttp
- `source_config`: `{ "url": "https://...", "headers": {...}, "method": "GET", "timeout": 30000 }`
- Use `env.Ws.url(url)` for HTTP requests (Play WS client)
- Parse response body with `RemoteContentParser`

### CatalogSourceGithub
- `source_config`: `{ "repo": "https://github.com/owner/repo.git", "branch": "main", "path": "/entities.json", "token": "ghp_xxx" }`
- Use GitHub API: `GET /repos/{owner}/{repo}/contents/{path}?ref={branch}` with `Accept: application/vnd.github.v3.raw`
- `supportsWebhook = true`: match on `repository.full_name` + `ref` from push webhook
- Extract repo path from URL (handle `https://github.com/owner/repo.git` format)

### CatalogSourceGitlab
- `source_config`: `{ "repo": "https://gitlab.com/group/project", "branch": "main", "path": "/entities.json", "token": "glpat-xxx", "base_url": "https://gitlab.com" }`
- Use GitLab API: `GET /api/v4/projects/{encoded_path}/repository/files/{encoded_file}/raw?ref={branch}` with `PRIVATE-TOKEN`
- `supportsWebhook = true`: match on `project.web_url` + `ref`

### CatalogSourceS3
- `source_config`: `{ "bucket": "...", "key": "/entities.json", "endpoint": "https://s3.amazonaws.com", "region": "eu-west-1", "access": "AKIA...", "secret": "...", "v4auth": true }`
- Use Alpakka S3 (already in project, see `app/storage/drivers/inmemory/persistence.scala`)
- `supportsWebhook = false`

---

## Phase 4: `plugins.scala` -- 3 Plugins

Pattern: `app/next/workflow/plugins.scala`

### RemoteCatalogDeploySingle (NgBackendCall)
- Config: `catalog_ref` (select from catalogs API)
- `callBackend`: lookup catalog, parse body as args, call `engine.deploy(catalog, args)`
- `configSchema`: select field pointing to `/bo/api/proxy/apis/catalogs.otoroshi.io/v1/remote-catalogs`

### RemoteCatalogDeployMany (NgBackendCall)
- Config: `catalog_refs` (multi-select)
- Body: `[{"id": "...", "args": {...}}]`
- `callBackend`: iterate over body entries, filter against config whitelist, deploy each

### RemoteCatalogDeployWebhook (NgBackendCall)
- Config: `catalog_refs` (multi-select) + `source_type` (select: github/gitlab)
- `callBackend`: parse webhook body, call `source.webhookDeploySelect()` to find matching catalogs, call `source.webhookDeployExtractArgs()` for each, then `engine.deploy()`

---

## Phase 5: `catalogs/index.js` -- UI

Pattern: `javascript/src/extensions/httplisteners/index.js`

### RemoteCatalogsPage Component
- `formSchema`: standard fields (_loc, id, name, description, tags, metadata, enabled) + source_kind (select), source_config (jsonobjectcode), scheduling fields (nested), test_deploy_args, deploy button, test button
- `formFlow`: organized in sections (General, Source, Scheduling, Test, Actions)
- `columns`: Name, Source Kind, Enabled, Scheduled
- `client`: `BackOfficeServices.apisClient('catalogs.otoroshi.io', 'v1', 'remote-catalogs')`

### DeployButton Component
- Calls `POST /extensions/remote-catalogs/_deploy` with `[{id: currentItem.id, args: currentItem.test_deploy_args}]`
- Displays success/error result

### TestButton Component
- Calls `POST /extensions/remote-catalogs/_test` with `{id: currentItem.id, args: currentItem.test_deploy_args}`
- Displays reconciliation diff report (entities to create, update, delete)

---

## Phase 6: `backoffice.js` Registration

File: `otoroshi/javascript/src/backoffice.js`

Add:
```javascript
import { setupRemoteCatalogsExtension } from './extensions/catalogs';
// In setupLocalExtensions():
setupRemoteCatalogsExtension(registerExtension);
```

---

## Implementation Order

1. **extension.scala**: Entity + Datastore + State + Extension skeleton (syncStates, entities, start/stop)
2. **api.scala**: CatalogSource trait, CatalogSources registry, RemoteContentParser, EntityKindMapping, RemoteCatalogEngine (reconcile, deploy, dryRun), report types
3. **sources.scala**: CatalogSourceFile, CatalogSourceHttp (simplest to test with), CatalogSourceGithub, CatalogSourceGitlab, CatalogSourceS3
4. **extension.scala (contd)**: Admin API routes (_deploy, _test), Backoffice routes, RemoteCatalogJob, startJobsIfNeeded
5. **catalogs/index.js**: UI page with Table, DeployButton, TestButton
6. **backoffice.js**: Register extension
7. **plugins.scala**: DeploySingle, DeployMany, DeployWebhook

---

## Key Reference Files

| File | Purpose |
|---|---|
| `app/next/workflow/extension.scala` | Primary pattern (entity, datastore, state, extension, CRUD, routes, jobs) |
| `app/next/workflow/job.scala` | Job scheduling pattern (WorkflowJobConfig, WorkflowJob) |
| `app/next/workflow/plugins.scala` | Plugin pattern (NgBackendCall with configSchema) |
| `app/api/api.scala:520-1000` | OtoroshiResources - all entity type Resource definitions |
| `app/storage/stores/KvGlobalConfigDataStore.scala:261-283` | Export format JSON key names |
| `app/storage/storage.scala` | BasicStore, RedisLikeStore, RawDataStore |
| `app/next/extensions/extension.scala` | AdminExtension trait, routing, entity registration |
| `javascript/src/extensions/httplisteners/index.js` | UI page pattern |
| `javascript/src/backoffice.js:29-32` | Extension import/registration |
| `app/storage/drivers/inmemory/persistence.scala` | S3 Alpakka integration pattern |

---

## Verification

1. **Build**: `cd otoroshi && sbt compile` -- verify Scala compiles
2. **Frontend**: `cd otoroshi/javascript && yarn install && yarn start` -- verify JS builds
3. **CRUD test**: Start Otoroshi, verify `/bo/api/proxy/apis/catalogs.otoroshi.io/v1/remote-catalogs` returns `[]`
4. **UI test**: Navigate to `/bo/dashboard/extensions/remote-catalogs`, verify page loads
5. **Deploy test (HTTP source)**: Create a remote catalog with `source_kind: http` pointing to a JSON file served by a local HTTP server, click deploy, verify entities are created with `created_by` metadata
6. **Dry-run test**: Click "Test" button, verify report shows correct create/update/delete counts
7. **Reconciliation test**: Remove an entity from the remote JSON, redeploy, verify it's deleted locally
8. **Scheduling test**: Enable scheduling on a catalog, verify job runs and entities sync
9. **Plugin test**: Create a route with RemoteCatalogDeploySingle plugin, POST to it, verify deploy
