---
title: Admin Extensions
sidebar_position: 2
---
# Admin Extensions

Admin extensions are a powerful mechanism to extend Otoroshi beyond route plugins. While plugins operate within the request lifecycle of a route, admin extensions let you add **custom entities**, **custom admin API endpoints**, **custom backoffice pages**, **custom well-known endpoints**, **custom secret vaults**, and even **custom datastore backends** to Otoroshi.

Extensions are Scala classes discovered automatically at startup via classgraph scanning. They follow the same packaging and deployment model as plugins (JAR on the classpath).

## What extensions can do

| Capability | Description |
|------------|-------------|
| **Custom entities** | Define new entity types with full CRUD API, storage, and in-memory state. Entities get automatic REST endpoints under `/apis/{group}/v1/{plural}` |
| **Admin API routes** | Add custom authenticated routes under `/api/extensions/` or `/apis/extensions/` |
| **Backoffice routes** | Add custom pages to the Otoroshi admin UI (authenticated or public) |
| **Well-known routes** | Add custom `/.well-known/otoroshi/extensions/` endpoints |
| **Frontend extensions** | Load custom JavaScript files into the admin UI |
| **Custom vaults** | Register new secret vault backends |
| **Custom datastores** | Provide alternative storage backends |
| **Public keys** | Expose additional JWK public keys on the JWKS endpoint |
| **Override routes** | Shadow built-in Otoroshi routes with custom implementations |

## Extension structure

An admin extension is a Scala class that:

1. Extends the `AdminExtension` trait
2. Takes an `Env` parameter in its constructor
3. Is placed in the `otoroshi_plugins` package (or a sub-package)

Here is the minimal structure:

```scala
package otoroshi_plugins.mycompany

import otoroshi.env.Env
import otoroshi.next.extensions._
import otoroshi.utils.syntax.implicits._

class MyExtension(val env: Env) extends AdminExtension {

  override def id: AdminExtensionId    = AdminExtensionId("otoroshi.extensions.MyExtension")
  override def name: String            = "My Extension"
  override def description: Option[String] = "My custom extension".some
  override def enabled: Boolean        = configuration.getOptional[Boolean]("enabled").getOrElse(false)

  override def start(): Unit = {
    // Called when the extension is loaded
  }

  override def stop(): Unit = {
    // Called when the extension is unloaded
  }
}
```

The `configuration` method provides access to the extension's dedicated configuration section from the Otoroshi config file:

```hocon
otoroshi {
  admin-extensions {
    configurations {
      otoroshi_extensions_myextension {  # id with dots replaced by underscores, lowercased
        enabled = true
        my-custom-setting = "value"
      }
    }
  }
}
```

## AdminExtension trait reference

| Method | Return type | Description |
|--------|-------------|-------------|
| `id` | `AdminExtensionId` | Unique identifier for the extension (used for storage keys and configuration) |
| `name` | `String` | Display name |
| `description` | `Option[String]` | Description |
| `enabled` | `Boolean` | Whether the extension is active |
| `start()` | `Unit` | Called at startup |
| `stop()` | `Unit` | Called at shutdown |
| `syncStates()` | `Future[Unit]` | Called periodically to sync in-memory state from the datastore |
| `entities()` | `Seq[AdminExtensionEntity[_]]` | Custom entity definitions |
| `frontendExtensions()` | `Seq[AdminExtensionFrontendExtension]` | Frontend JavaScript files to load |
| `adminApiRoutes()` | `Seq[AdminExtensionAdminApiRoute]` | Custom admin API endpoints |
| `backofficeAuthRoutes()` | `Seq[AdminExtensionBackofficeAuthRoute]` | Authenticated backoffice routes |
| `backofficePublicRoutes()` | `Seq[AdminExtensionBackofficePublicRoute]` | Public backoffice routes |
| `wellKnownRoutes()` | `Seq[AdminExtensionWellKnownRoute]` | Custom well-known endpoints |
| `assets()` | `Seq[AdminExtensionAssetRoute]` | Static asset routes |
| `vaults()` | `Seq[AdminExtensionVault]` | Custom vault implementations |
| `datastoreBuilders()` | `Map[String, DataStoresBuilder]` | Custom datastore backends |
| `publicKeys()` | `Future[Seq[PublicKeyJwk]]` | Additional JWK public keys |
| `configuration` | `Configuration` | Access to the extension's configuration section |

Each route type also has an "overrides" variant (e.g., `adminApiOverridesRoutes()`) that can shadow built-in Otoroshi routes.

## Adding custom entities

The most common use case for extensions is adding custom entities with full CRUD support. This involves:

1. A **case class** for the entity
2. A **JSON format** (Play `Format[T]`)
3. A **datastore** (typically `RedisLikeStore[T]`)
4. An **in-memory state** (for fast reads)
5. Registering everything in `entities()`

### Step 1: Define the entity

```scala
package otoroshi_plugins.mycompany

import otoroshi.models.{EntityLocation, EntityLocationSupport}
import play.api.libs.json._
import scala.util.{Failure, Success, Try}

case class Widget(
    location: EntityLocation,
    id: String,
    name: String,
    description: String,
    color: String,
    tags: Seq[String],
    metadata: Map[String, String]
) extends EntityLocationSupport {
  override def internalId: String               = id
  override def json: JsValue                    = Widget.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata
}

object Widget {
  val format = new Format[Widget] {
    override def writes(o: Widget): JsValue = o.location.jsonWithKey ++ Json.obj(
      "id"          -> o.id,
      "name"        -> o.name,
      "description" -> o.description,
      "color"       -> o.color,
      "metadata"    -> o.metadata,
      "tags"        -> JsArray(o.tags.map(JsString.apply))
    )
    override def reads(json: JsValue): JsResult[Widget] = Try {
      Widget(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = (json \ "id").as[String],
        name = (json \ "name").as[String],
        description = (json \ "description").asOpt[String].getOrElse("--"),
        color = (json \ "color").asOpt[String].getOrElse("blue"),
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty)
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}
```

The entity must extend `EntityLocationSupport` which provides multi-tenancy support (`location` contains the tenant and team information).

### Step 2: Create a datastore

```scala
package otoroshi_plugins.mycompany

import otoroshi.env.Env
import otoroshi.next.extensions.AdminExtensionId
import otoroshi.storage.{BasicStore, RedisLike, RedisLikeStore}
import play.api.libs.json.Format

trait WidgetDataStore extends BasicStore[Widget]

class KvWidgetDataStore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
    extends WidgetDataStore with RedisLikeStore[Widget] {

  override def fmt: Format[Widget]                        = Widget.format
  override def redisLike(implicit env: Env): RedisLike    = redisCli
  override def key(id: String): String                    =
    s"${_env.storageRoot}:extensions:${extensionId.cleanup}:widgets:$id"
  override def extractId(value: Widget): String           = value.id
}
```

The key format follows the convention `{storageRoot}:extensions:{extensionId}:{entityType}:{id}`.

### Step 3: Create an in-memory state

```scala
package otoroshi_plugins.mycompany

import otoroshi.env.Env
import otoroshi.utils.cache.types.UnboundedTrieMap

class WidgetExtensionState(env: Env) {
  private val widgets = new UnboundedTrieMap[String, Widget]()

  def widget(id: String): Option[Widget] = widgets.get(id)
  def allWidgets(): Seq[Widget]          = widgets.values.toSeq

  private[mycompany] def updateWidgets(values: Seq[Widget]): Unit = {
    widgets.addAll(values.map(v => (v.id, v))).remAll(widgets.keySet.toSeq.diff(values.map(_.id)))
  }
}
```

The `addAll` + `remAll` pattern ensures the in-memory state is always in sync with the datastore.

### Step 4: Register the entity in the extension

```scala
package otoroshi_plugins.mycompany

import otoroshi.api._
import otoroshi.env.Env
import otoroshi.models.EntityLocationSupport
import otoroshi.next.extensions._
import otoroshi.utils.syntax.implicits._

import scala.concurrent.Future

class WidgetExtension(val env: Env) extends AdminExtension {

  private lazy val datastores = new KvWidgetDataStore(id, env.datastores.redis, env)
  private lazy val states     = new WidgetExtensionState(env)

  override def id: AdminExtensionId    = AdminExtensionId("otoroshi.extensions.Widgets")
  override def name: String            = "Widgets"
  override def description: Option[String] = "Manage widgets".some
  override def enabled: Boolean        = configuration.getOptional[Boolean]("enabled").getOrElse(false)

  override def syncStates(): Future[Unit] = {
    implicit val ec = env.otoroshiExecutionContext
    implicit val ev = env
    for {
      widgets <- datastores.findAll()
    } yield {
      states.updateWidgets(widgets)
      ()
    }
  }

  override def entities(): Seq[AdminExtensionEntity[EntityLocationSupport]] = {
    Seq(
      AdminExtensionEntity(
        Resource(
          "Widget",                                    // kind (singular, PascalCase)
          "widgets",                                   // plural name (used in API path)
          "widget",                                    // singular name
          "widgets.extensions.otoroshi.io",             // API group
          ResourceVersion("v1", true, false, true),    // version, enabled, deprecated, beta
          GenericResourceAccessApiWithState[Widget](
            Widget.format,                             // JSON format
            classOf[Widget],                           // class reference
            id => datastores.key(id),                  // storage key function
            c => datastores.extractId(c),              // extract ID from entity
            json => json.select("id").asString,        // extract ID from JSON
            () => "id",                                // ID field name
            stateAll = () => states.allWidgets(),       // read all from in-memory state
            stateOne = id => states.widget(id),        // read one from in-memory state
            stateUpdate = values => states.updateWidgets(values)  // update in-memory state
          )
        )
      )
    )
  }
}
```

This automatically exposes the following admin API endpoints:

```
GET    /apis/widgets.extensions.otoroshi.io/v1/widgets              # List all widgets
POST   /apis/widgets.extensions.otoroshi.io/v1/widgets              # Create a widget
GET    /apis/widgets.extensions.otoroshi.io/v1/widgets/:id          # Get a widget
PUT    /apis/widgets.extensions.otoroshi.io/v1/widgets/:id          # Update a widget
PATCH  /apis/widgets.extensions.otoroshi.io/v1/widgets/:id          # Partially update a widget
DELETE /apis/widgets.extensions.otoroshi.io/v1/widgets/:id          # Delete a widget
GET    /apis/widgets.extensions.otoroshi.io/v1/widgets/_count       # Count widgets
GET    /apis/widgets.extensions.otoroshi.io/v1/widgets/_template    # Get a widget template
POST   /apis/widgets.extensions.otoroshi.io/v1/widgets/_bulk        # Bulk create
PUT    /apis/widgets.extensions.otoroshi.io/v1/widgets/_bulk        # Bulk update
PATCH  /apis/widgets.extensions.otoroshi.io/v1/widgets/_bulk        # Bulk patch
DELETE /apis/widgets.extensions.otoroshi.io/v1/widgets/_bulk        # Bulk delete
```

### Write and delete validation

To add custom validation logic when entities are created, updated, or deleted, use `GenericResourceAccessApiWithStateAndWriteValidation`:

```scala
GenericResourceAccessApiWithStateAndWriteValidation[Widget](
  Widget.format,
  classOf[Widget],
  id => datastores.key(id),
  c => datastores.extractId(c),
  json => json.select("id").asString,
  () => "id",
  stateAll = () => states.allWidgets(),
  stateOne = id => states.widget(id),
  stateUpdate = values => states.updateWidgets(values),
  writeValidator = (entity, body, oldEntity, singularName, id, action, env) => {
    // Return Right(entity) to allow, Left(jsonError) to reject
    // action is WriteAction.Create or WriteAction.Update
    if (entity.name.isEmpty) {
      Json.obj("error" -> "name cannot be empty", "http_status_code" -> 400).leftf
    } else {
      entity.rightf
    }
  },
  deleteValidator = (entity, body, singularName, id, action, env) => {
    // Return Right(()) to allow, Left(jsonError) to reject
    ().rightf
  }
)
```

## Adding custom routes

### Admin API routes

Admin API routes require API key authentication. They are available under `/api/extensions/` or `/apis/extensions/`:

```scala
override def adminApiRoutes(): Seq[AdminExtensionAdminApiRoute] = Seq(
  AdminExtensionAdminApiRoute(
    method = "GET",
    path = "/api/extensions/widgets/stats",
    wantsBody = false,
    handle = (ctx, request, apiKey, body) => {
      val count = states.allWidgets().size
      Results.Ok(Json.obj("widget_count" -> count)).vfuture
    }
  ),
  AdminExtensionAdminApiRoute(
    method = "POST",
    path = "/api/extensions/widgets/import",
    wantsBody = true,
    handle = (ctx, request, apiKey, body) => {
      // body is Option[Source[ByteString, _]]
      // Process the request body...
      Results.Ok(Json.obj("imported" -> true)).vfuture
    }
  )
)
```

### Backoffice routes

Authenticated routes for the admin UI, available under `/extensions/`:

```scala
override def backofficeAuthRoutes(): Seq[AdminExtensionBackofficeAuthRoute] = Seq(
  AdminExtensionBackofficeAuthRoute(
    method = "GET",
    path = "/extensions/widgets/dashboard",
    wantsBody = false,
    handle = (ctx, request, user, body) => {
      // user is Option[BackOfficeUser]
      Results.Ok(Json.obj("widgets" -> states.allWidgets().map(_.json))).vfuture
    }
  )
)
```

Public routes (no authentication required) are available under `/extensions/pub/`:

```scala
override def backofficePublicRoutes(): Seq[AdminExtensionBackofficePublicRoute] = Seq(
  AdminExtensionBackofficePublicRoute(
    method = "GET",
    path = "/extensions/pub/widgets/health",
    wantsBody = false,
    handle = (ctx, request, body) => {
      Results.Ok(Json.obj("status" -> "healthy")).vfuture
    }
  )
)
```

### Well-known routes

Available under `/.well-known/otoroshi/extensions/`:

```scala
override def wellKnownRoutes(): Seq[AdminExtensionWellKnownRoute] = Seq(
  AdminExtensionWellKnownRoute(
    method = "GET",
    path = "/.well-known/otoroshi/extensions/widgets/config",
    wantsBody = false,
    handle = (ctx, request, body) => {
      Results.Ok(Json.obj("version" -> "1.0")).vfuture
    }
  )
)
```

### Route parameters

Routes support named parameters (`:name`) and splat parameters. Use the `AdminExtensionRouterContext` to extract them:

```scala
AdminExtensionAdminApiRoute(
  method = "GET",
  path = "/api/extensions/widgets/:id/details",
  wantsBody = false,
  handle = (ctx, request, apiKey, body) => {
    val widgetId = ctx.named("id").getOrElse("unknown")
    states.widget(widgetId) match {
      case Some(widget) => Results.Ok(widget.json).vfuture
      case None         => Results.NotFound(Json.obj("error" -> "not found")).vfuture
    }
  }
)
```

## Frontend extensions

To add custom JavaScript to the Otoroshi admin UI:

```scala
override def frontendExtensions(): Seq[AdminExtensionFrontendExtension] = Seq(
  AdminExtensionFrontendExtension("/__otoroshi_assets/javascripts/extensions/widgets.js")
)
```

The JavaScript file is typically served via an asset route. The JS module should export a setup function that registers UI components:

```javascript
// javascript/src/extensions/widgets/index.js
export function setupWidgetsExtension(registerExtension) {
  registerExtension({
    id: 'otoroshi.extensions.Widgets',
    categories: [],
    features: [],
    sidebarItems: [
      {
        title: 'Widgets',
        text: 'Widgets',
        path: 'extensions/widgets/widgets',
        icon: 'cubes',
      }
    ],
    searchItems: [],
    routes: [],
    entities: [],
    properties: {},
  });
}
```

Register it in `javascript/src/backoffice.js`:

```javascript
import { setupWidgetsExtension } from './extensions/widgets';
// In setupLocalExtensions:
setupWidgetsExtension(registerExtension);
```

Use `BackOfficeServices.apisClient(group, version, plural)` for CRUD operations on extension entities:

```javascript
const client = BackOfficeServices.apisClient('widgets.extensions.otoroshi.io', 'v1', 'widgets');
// client.findAll(), client.findById(id), client.create(entity), client.update(entity), client.delete(entity)
```

## Custom vaults

Extensions can register custom secret vault backends:

```scala
override def vaults(): Seq[AdminExtensionVault] = Seq(
  AdminExtensionVault(
    name = "my-vault",
    build = (name, configuration, env) => {
      new MyCustomVault(name, configuration, env)
    }
  )
)
```

The vault must implement the `otoroshi.next.utils.Vault` trait.

## Custom datastores

Extensions can provide alternative storage backends:

```scala
override def datastoreBuilders(): Map[String, DataStoresBuilder] = Map(
  "my-storage" -> new MyDataStoresBuilder()
)
```

## Auto-discovery

Extensions are automatically discovered at startup. The `AdminExtensions` container:

1. Scans the classpath for classes extending `AdminExtension`
2. Instantiates each extension with the `Env` parameter
3. Filters out disabled extensions (`enabled = false`)
4. Collects all entities, routes, and other contributions
5. Builds internal routers for each route type
6. Calls `start()` on all enabled extensions
7. Periodically calls `syncStates()` to keep in-memory state up to date

## Export and import

All extension entities are automatically included in Otoroshi's global export/import functionality. When you export the Otoroshi configuration, extension entities are included. When you import, they are restored.

## Real-world examples

### Built-in extensions

Otoroshi itself uses the admin extension system for several features:

* **HTTP Listeners** (`otoroshi.next.extensions.HttpListenerAdminExtension`): manages dynamic HTTP listeners with Netty servers
* **Workflows** (`otoroshi.next.workflow.WorkflowAdminExtension`): the visual workflow engine
* **Remote Catalogs**: GitOps-style entity management from external sources

### Community extensions

* [Cloud APIM LLM Extension](https://github.com/cloud-apim/otoroshi-llm-extension): LLM integration with custom entities and UIs
* [Cloud APIM Biscuit Studio](https://github.com/cloud-apim/otoroshi-biscuit-studio): Biscuit token management with multiple entities

## Complete example

Here is a complete, self-contained extension with a custom entity, datastore, state, and admin API route:

```scala
package otoroshi_plugins.mycompany

import otoroshi.api._
import otoroshi.env.Env
import otoroshi.models.{EntityLocation, EntityLocationSupport}
import otoroshi.next.extensions._
import otoroshi.storage.{BasicStore, RedisLike, RedisLikeStore}
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.Results

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

// --- Entity ---

case class Bookmark(
    location: EntityLocation,
    id: String,
    name: String,
    description: String,
    url: String,
    tags: Seq[String],
    metadata: Map[String, String]
) extends EntityLocationSupport {
  override def internalId: String               = id
  override def json: JsValue                    = Bookmark.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata
}

object Bookmark {
  val format = new Format[Bookmark] {
    override def writes(o: Bookmark): JsValue = o.location.jsonWithKey ++ Json.obj(
      "id" -> o.id, "name" -> o.name, "description" -> o.description,
      "url" -> o.url, "metadata" -> o.metadata, "tags" -> JsArray(o.tags.map(JsString.apply))
    )
    override def reads(json: JsValue): JsResult[Bookmark] = Try {
      Bookmark(
        location = EntityLocation.readFromKey(json),
        id = (json \ "id").as[String],
        name = (json \ "name").as[String],
        description = (json \ "description").asOpt[String].getOrElse("--"),
        url = (json \ "url").asOpt[String].getOrElse(""),
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty)
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

// --- Datastore ---

trait BookmarkDataStore extends BasicStore[Bookmark]

class KvBookmarkDataStore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
    extends BookmarkDataStore with RedisLikeStore[Bookmark] {
  override def fmt: Format[Bookmark]                     = Bookmark.format
  override def redisLike(implicit env: Env): RedisLike   = redisCli
  override def key(id: String): String                   =
    s"${_env.storageRoot}:extensions:${extensionId.cleanup}:bookmarks:$id"
  override def extractId(value: Bookmark): String        = value.id
}

// --- State ---

class BookmarkState(env: Env) {
  private val bookmarks = new UnboundedTrieMap[String, Bookmark]()
  def bookmark(id: String): Option[Bookmark] = bookmarks.get(id)
  def allBookmarks(): Seq[Bookmark]          = bookmarks.values.toSeq
  private[mycompany] def updateBookmarks(values: Seq[Bookmark]): Unit =
    bookmarks.addAll(values.map(v => (v.id, v))).remAll(bookmarks.keySet.toSeq.diff(values.map(_.id)))
}

// --- Extension ---

class BookmarkExtension(val env: Env) extends AdminExtension {

  private lazy val datastore = new KvBookmarkDataStore(id, env.datastores.redis, env)
  private lazy val state     = new BookmarkState(env)

  override def id: AdminExtensionId       = AdminExtensionId("otoroshi.extensions.Bookmarks")
  override def name: String               = "Bookmarks"
  override def description: Option[String] = "Manage bookmarks".some
  override def enabled: Boolean           = configuration.getOptional[Boolean]("enabled").getOrElse(false)

  override def syncStates(): Future[Unit] = {
    implicit val ec = env.otoroshiExecutionContext
    implicit val ev = env
    datastore.findAll().map(values => state.updateBookmarks(values))
  }

  override def adminApiRoutes(): Seq[AdminExtensionAdminApiRoute] = Seq(
    AdminExtensionAdminApiRoute(
      "GET", "/api/extensions/bookmarks/search", false,
      (ctx, request, apiKey, body) => {
        val query = request.getQueryString("q").getOrElse("")
        val results = state.allBookmarks().filter(b => b.name.contains(query) || b.url.contains(query))
        Results.Ok(JsArray(results.map(_.json))).vfuture
      }
    )
  )

  override def entities(): Seq[AdminExtensionEntity[EntityLocationSupport]] = Seq(
    AdminExtensionEntity(
      Resource(
        "Bookmark", "bookmarks", "bookmark",
        "bookmarks.extensions.otoroshi.io",
        ResourceVersion("v1", true, false, true),
        GenericResourceAccessApiWithState[Bookmark](
          Bookmark.format, classOf[Bookmark],
          id => datastore.key(id),
          c => datastore.extractId(c),
          json => json.select("id").asString,
          () => "id",
          stateAll = () => state.allBookmarks(),
          stateOne = id => state.bookmark(id),
          stateUpdate = values => state.updateBookmarks(values)
        )
      )
    )
  )
}
```

Enable it in the Otoroshi configuration:

```hocon
otoroshi {
  admin-extensions {
    configurations {
      otoroshi_extensions_bookmarks {
        enabled = true
      }
    }
  }
}
```

Then you can use the automatically generated REST API:

```sh
# Create a bookmark
curl -X POST 'http://otoroshi-api.oto.tools:8080/apis/bookmarks.extensions.otoroshi.io/v1/bookmarks' \
  -H 'Content-Type: application/json' \
  -u admin-api-apikey-id:admin-api-apikey-secret \
  -d '{
    "id": "bm_1",
    "name": "Otoroshi Docs",
    "description": "Otoroshi documentation",
    "url": "https://maif.github.io/otoroshi/manual/"
  }'

# List all bookmarks
curl 'http://otoroshi-api.oto.tools:8080/apis/bookmarks.extensions.otoroshi.io/v1/bookmarks' \
  -u admin-api-apikey-id:admin-api-apikey-secret

# Search bookmarks (custom route)
curl 'http://otoroshi-api.oto.tools:8080/api/extensions/bookmarks/search?q=otoroshi' \
  -u admin-api-apikey-id:admin-api-apikey-secret
```

## Related

* [Create plugins](../plugins/create-plugins.md) - Build and deploy custom plugins
* [Plugin system](../plugins/plugins-system.md) - Plugin types and lifecycle
