---
title: Otoroshi plugin system
sidebar_label: "Plugin System"
sidebar_position: 2
---
# Otoroshi plugin system

Otoroshi has an extensible plugin system that lets you customize every aspect of request processing. Plugins are Scala classes that implement one or more plugin traits. They are loaded from the classpath and can be attached to any route.

All plugin traits extend `NgPlugin`, which itself extends `StartableAndStoppable`, `NgNamedPlugin`, and `InternalEventListener`.

## Plugin lifecycle steps

Each plugin declares which steps of the request lifecycle it participates in via the `steps` method. The proxy engine executes plugins in the following order:

| Step | Trait | Description |
|------|-------|-------------|
| `MatchRoute` | `NgRouteMatcher` | Additional matching logic after the router selects a route. Can reject a route match based on custom criteria |
| `PreRoute` | `NgPreRouting` | Runs before access validation. Used to extract values (custom API keys, tokens, etc.) and store them in the request attributes for downstream plugins |
| `ValidateAccess` | `NgAccessValidator` | Decides whether the request is allowed to proceed. Returns `NgAllowed` or `NgDenied(result)` |
| `TransformRequest` | `NgRequestTransformer` | Transforms the outgoing request to the backend (headers, body, URL). Can also short-circuit with a direct response |
| `CallBackend` | `NgBackendCall` | Replaces or wraps the default backend call. Used for custom backends (static responses, mock servers, protocol bridges) |
| `TransformResponse` | `NgRequestTransformer` | Transforms the response from the backend before sending it to the client |
| `HandlesTunnel` | `NgTunnelHandler` | Handles WebSocket tunnel connections |
| `HandlesRequest` | `NgBackendCall` | Handles the full request (alternative to `CallBackend`) |
| `Sink` | `NgRequestSink` | Catches requests that did not match any route |
| `Router` | `NgRouter` | Custom routing logic (replaces the default router) |

## Plugin metadata

Every plugin must declare the following metadata through the `NgNamedPlugin` trait:

| Method | Type | Description |
|--------|------|-------------|
| `name` | `String` | Human-readable name displayed in the UI |
| `description` | `Option[String]` | Short description of what the plugin does |
| `visibility` | `NgPluginVisibility` | `NgUserLand` (visible in UI) or `NgInternal` (hidden, used internally) |
| `categories` | `Seq[NgPluginCategory]` | One or more categories for organizing plugins in the UI |
| `steps` | `Seq[NgStep]` | Which lifecycle steps this plugin participates in |
| `defaultConfigObject` | `Option[NgPluginConfig]` | Default configuration object. Return `None` if the plugin has no configuration |
| `multiInstance` | `Boolean` | Whether the plugin can be added multiple times to the same route (default: `true`) |
| `noJsForm` | `Boolean` | If `true`, the UI will not generate a form from the config schema (default: `false`) |
| `configFlow` | `Seq[String]` | Ordered list of config field names for the UI form layout |
| `configSchema` | `Option[JsObject]` | JSON schema describing each config field for the UI form |

## Plugin categories

Categories help organize plugins in the Otoroshi UI:

`AccessControl`, `Authentication`, `Classic`, `Custom`, `Experimental`, `Headers`, `Integrations`, `Logging`, `Monitoring`, `Other`, `Security`, `ServiceDiscovery`, `TrafficControl`, `Transformations`, `Tunnel`, `Wasm`, `Websocket`

## Plugin types reference

### NgPreRouting

Runs before access validation. Typically used to extract custom credentials or enrich the request context.

```scala
trait NgPreRouting extends NgPlugin {
  def preRoute(
    ctx: NgPreRoutingContext
  )(implicit env: Env, ec: ExecutionContext): Future[Either[NgPreRoutingError, Done]]
}
```

**Context** (`NgPreRoutingContext`):

| Field | Type | Description |
|-------|------|-------------|
| `snowflake` | `String` | Unique request ID |
| `request` | `RequestHeader` | The incoming Play request |
| `route` | `NgRoute` | The matched route |
| `config` | `JsValue` | Plugin configuration (merged default + instance) |
| `globalConfig` | `JsValue` | Global Otoroshi configuration |
| `attrs` | `TypedMap` | Shared attributes map (read/write) |

**Return**: `Right(Done)` to continue, `Left(NgPreRoutingError)` to short-circuit with an error response.

A synchronous variant is available via `preRouteSync`. Set `isPreRouteAsync = false` when using the synchronous variant for better performance.

### NgAccessValidator

Validates whether a request is allowed to proceed.

```scala
trait NgAccessValidator extends NgPlugin {
  def access(
    ctx: NgAccessContext
  )(implicit env: Env, ec: ExecutionContext): Future[NgAccess]
}
```

**Context** (`NgAccessContext`):

| Field | Type | Description |
|-------|------|-------------|
| `snowflake` | `String` | Unique request ID |
| `request` | `RequestHeader` | The incoming Play request |
| `route` | `NgRoute` | The matched route |
| `user` | `Option[PrivateAppsUser]` | Authenticated user (if any) |
| `apikey` | `Option[ApiKey]` | API key (if any) |
| `config` | `JsValue` | Plugin configuration |
| `globalConfig` | `JsValue` | Global Otoroshi configuration |
| `attrs` | `TypedMap` | Shared attributes map |

**Return**: `NgAccess.NgAllowed` or `NgAccess.NgDenied(result)`.

The context provides a helper `deniedAccess(status, message)` to craft a properly formatted error response.

### NgRequestTransformer

Transforms requests and responses. This is the most versatile plugin type.

```scala
trait NgRequestTransformer extends NgPlugin {
  def transformRequest(
    ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]]

  def transformResponse(
    ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]]

  def transformError(
    ctx: NgTransformerErrorContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[NgPluginHttpResponse]

  def beforeRequest(ctx: NgBeforeRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit]
  def afterRequest(ctx: NgAfterRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit]
}
```

**Performance flags** (override to `false` when not needed):

| Flag | Default | Description |
|------|---------|-------------|
| `usesCallbacks` | `true` | Set to `false` if you don't use `beforeRequest`/`afterRequest` |
| `transformsRequest` | `true` | Set to `false` if you only transform responses |
| `transformsResponse` | `true` | Set to `false` if you only transform requests |
| `transformsError` | `true` | Set to `false` if you don't handle errors |

**Request context** (`NgTransformerRequestContext`):

| Field | Type | Description |
|-------|------|-------------|
| `rawRequest` | `NgPluginHttpRequest` | The original unmodified request |
| `otoroshiRequest` | `NgPluginHttpRequest` | The request as modified by previous plugins |
| `snowflake` | `String` | Unique request ID |
| `route` | `NgRoute` | The matched route |
| `apikey` | `Option[ApiKey]` | API key (if any) |
| `user` | `Option[PrivateAppsUser]` | Authenticated user (if any) |
| `request` | `RequestHeader` | The incoming Play request |
| `config` | `JsValue` | Plugin configuration |
| `attrs` | `TypedMap` | Shared attributes map |

**Return**: `Right(modifiedRequest)` to continue, `Left(result)` to short-circuit and return a response directly to the client.

Synchronous variants are available (`transformRequestSync`, `transformResponseSync`). Set `isTransformRequestAsync = false` or `isTransformResponseAsync = false` when using them.

### NgBackendCall

Replaces or wraps the default HTTP call to the backend. Useful for mock backends, protocol translation, or custom logic.

```scala
trait NgBackendCall extends NgPlugin {
  def useDelegates: Boolean
  def callBackend(
    ctx: NgbBackendCallContext,
    delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]]
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]]
}
```

When `useDelegates` is `true`, the `delegates` function calls the next backend plugin or the default HTTP backend. When `false`, the default backend is never called.

Helper methods are available on the trait:

| Method | Description |
|--------|-------------|
| `inMemoryBodyResponse(status, headers, body: ByteString)` | Create a response from an in-memory body |
| `sourceBodyResponse(status, headers, body: Source[ByteString, _])` | Create a response from a streaming body |
| `emptyBodyResponse(status, headers)` | Create a response with no body |

### NgRouteMatcher

Additional matching logic run after the router selects a route candidate.

```scala
trait NgRouteMatcher extends NgPlugin {
  def matches(ctx: NgRouteMatcherContext)(implicit env: Env): Boolean
}
```

Return `true` if the route should be used, `false` to reject it and try the next route candidate.

### NgRequestSink

Catches requests that did not match any route.

```scala
trait NgRequestSink extends NgPlugin {
  def matches(ctx: NgRequestSinkContext)(implicit env: Env, ec: ExecutionContext): Boolean
  def handle(ctx: NgRequestSinkContext)(implicit env: Env, ec: ExecutionContext): Future[Result]
}
```

`matches` determines if this sink should handle the request. If multiple sinks match, the first one wins.

### NgTunnelHandler

Handles WebSocket tunnel connections. Extends `NgAccessValidator` to control access before establishing the tunnel.

```scala
trait NgTunnelHandler extends NgPlugin with NgAccessValidator {
  def handle(ctx: NgTunnelHandlerContext)(implicit env: Env, ec: ExecutionContext): Flow[Message, Message, _]
}
```

### NgWebsocketPlugin

Intercepts and transforms WebSocket messages on an established connection.

```scala
trait NgWebsocketPlugin extends NgPlugin {
  def onRequestMessage(ctx: NgWebsocketPluginContext, message: WebsocketMessage)(implicit env: Env, ec: ExecutionContext): Future[Either[NgWebsocketError, WebsocketMessage]]
  def onResponseMessage(ctx: NgWebsocketPluginContext, message: WebsocketMessage)(implicit env: Env, ec: ExecutionContext): Future[Either[NgWebsocketError, WebsocketMessage]]
}
```

Set `onRequestFlow` and/or `onResponseFlow` to `true` to enable interception on each direction.

### NgWebsocketBackendPlugin

Provides a custom WebSocket backend (replaces the default WebSocket proxy to the backend target).

```scala
trait NgWebsocketBackendPlugin extends NgPlugin {
  def callBackend(ctx: NgWebsocketPluginContext)(implicit env: Env, ec: ExecutionContext): Flow[PlayWSMessage, PlayWSMessage, _]
}
```

### NgRouter

Provides entirely custom routing logic. Replaces the default Otoroshi router.

```scala
trait NgRouter extends NgPlugin {
  def findRoute(ctx: NgRouterContext)(implicit env: Env, ec: ExecutionContext): Option[NgMatchedRoute]
}
```

## HTTP request and response models

Plugins work with `NgPluginHttpRequest` and `NgPluginHttpResponse` instead of raw Play objects. These are mutable-friendly case classes that represent the HTTP message flowing through the proxy.

### NgPluginHttpRequest

| Field | Type | Description |
|-------|------|-------------|
| `url` | `String` | Full URL |
| `method` | `String` | HTTP method |
| `headers` | `Map[String, String]` | Request headers |
| `cookies` | `Seq[WSCookie]` | Request cookies |
| `version` | `String` | HTTP version |
| `clientCertificateChain` | `() => Option[Seq[X509Certificate]]` | Client TLS certificate chain (lazy) |
| `body` | `Source[ByteString, _]` | Request body as a streaming source |
| `backend` | `Option[NgTarget]` | Selected backend target |

Useful computed fields: `path`, `host`, `queryString`, `queryParams`, `contentType`, `contentLength`, `hasBody`.

### NgPluginHttpResponse

| Field | Type | Description |
|-------|------|-------------|
| `status` | `Int` | HTTP status code |
| `headers` | `Map[String, String]` | Response headers |
| `cookies` | `Seq[WSCookie]` | Response cookies |
| `body` | `Source[ByteString, _]` | Response body as a streaming source |

## Configuration caching

All plugin contexts extend `NgCachedConfigContext`, which provides efficient configuration parsing with a 5-second TTL cache:

```scala
// Parse config using a Play JSON Reads (cached per route + plugin + index)
val config = ctx.cachedConfig(internalName)(MyConfig.format).getOrElse(MyConfig())

// Parse config using a custom function (cached)
val config = ctx.cachedConfigFn(internalName)(json => MyConfig.parse(json))

// Parse config without caching (re-parsed on every call)
val config = ctx.rawConfig(MyConfig.format)
```

Always prefer `cachedConfig` in hot paths for performance.

## Related

* [Create plugins](./create-plugins.md) - How to build, package, and deploy custom plugins
* [Built-in plugins](./built-in-plugins.mdx) - All built-in plugin references
* [Admin extensions](../topics/admin-extensions.md) - Extend Otoroshi with custom entities, routes, and UIs
