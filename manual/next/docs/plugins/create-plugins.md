---
title: Create plugins
sidebar_label: "Create Plugins"
sidebar_position: 3
---
# Create plugins

Otoroshi plugins let you customize how Otoroshi processes HTTP requests. You can transform requests and responses, enforce access control, provide custom backends, intercept WebSocket messages, and more. Plugins are Scala classes deployed as JAR files on the classpath.

## Project setup

Create an SBT project with the Otoroshi dependency:

```scala
lazy val root = (project in file(".")).
  settings(
    inThisBuild(List(
      organization := "com.example",
      scalaVersion := "2.12.16",
      version      := "0.1.0-SNAPSHOT"
    )),
    name := "my-otoroshi-plugins",
    libraryDependencies += "fr.maif" %% "otoroshi" % "17.12.0"
  )
```

:::warning
Plugin classes **must** be in the `otoroshi_plugins` package or a sub-package. Otoroshi uses classgraph to scan for plugins and only looks inside this package namespace. For example:

```scala
package otoroshi_plugins.mycompany
```
:::
## Plugin structure

Every plugin must:

1. Extend one or more plugin traits (`NgAccessValidator`, `NgRequestTransformer`, `NgBackendCall`, etc.)
2. Declare its `name`, `description`, `visibility`, `categories`, and `steps`
3. Optionally provide a default configuration via `defaultConfigObject`
4. Implement the relevant methods for the chosen trait(s)

## Writing an access validator

An access validator decides whether a request is allowed to proceed. Here is a minimal example that blocks requests without a specific header:

```scala
package otoroshi_plugins.mycompany

import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class RequiredHeaderConfig(headerName: String = "X-Required-Header") extends NgPluginConfig {
  def json: JsValue = RequiredHeaderConfig.format.writes(this)
}

object RequiredHeaderConfig {
  val format = new Format[RequiredHeaderConfig] {
    override def reads(json: JsValue): JsResult[RequiredHeaderConfig] = Try {
      RequiredHeaderConfig(
        headerName = json.select("header_name").asOpt[String].getOrElse("X-Required-Header")
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: RequiredHeaderConfig): JsValue = Json.obj(
      "header_name" -> o.headerName
    )
  }
}

class RequiredHeaderValidator extends NgAccessValidator {

  override def name: String                                = "Required Header Validator"
  override def description: Option[String]                 = "Rejects requests missing a required header".some
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.AccessControl)
  override def steps: Seq[NgStep]                          = Seq(NgStep.ValidateAccess)
  override def defaultConfigObject: Option[NgPluginConfig] = Some(RequiredHeaderConfig())
  override def multiInstance: Boolean                      = true

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config = ctx.cachedConfig(internalName)(RequiredHeaderConfig.format).getOrElse(RequiredHeaderConfig())
    ctx.request.headers.get(config.headerName) match {
      case Some(_) => NgAccess.NgAllowed.vfuture
      case None    => NgAccess.NgDenied(
        Results.Forbidden(Json.obj("error" -> s"Missing header: ${config.headerName}"))
      ).vfuture
    }
  }
}
```

## Writing a request transformer

A request transformer can modify the request going to the backend and/or the response coming back.

```scala
package otoroshi_plugins.mycompany

import akka.stream.Materializer
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.Result

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class AddHeadersConfig(
    requestHeaders: Map[String, String] = Map.empty,
    responseHeaders: Map[String, String] = Map.empty
) extends NgPluginConfig {
  def json: JsValue = AddHeadersConfig.format.writes(this)
}

object AddHeadersConfig {
  val format = new Format[AddHeadersConfig] {
    override def reads(json: JsValue): JsResult[AddHeadersConfig] = Try {
      AddHeadersConfig(
        requestHeaders = json.select("request_headers").asOpt[Map[String, String]].getOrElse(Map.empty),
        responseHeaders = json.select("response_headers").asOpt[Map[String, String]].getOrElse(Map.empty)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: AddHeadersConfig): JsValue = Json.obj(
      "request_headers"  -> o.requestHeaders,
      "response_headers" -> o.responseHeaders
    )
  }
}

class AddHeaders extends NgRequestTransformer {

  override def name: String                                = "Add Headers"
  override def description: Option[String]                 = "Adds custom headers to request and response".some
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Headers)
  override def steps: Seq[NgStep]                          = Seq(NgStep.TransformRequest, NgStep.TransformResponse)
  override def defaultConfigObject: Option[NgPluginConfig] = Some(AddHeadersConfig())
  override def multiInstance: Boolean                      = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = true
  override def transformsResponse: Boolean                 = true
  override def transformsError: Boolean                    = false

  override def transformRequestSync(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpRequest] = {
    val config = ctx.cachedConfig(internalName)(AddHeadersConfig.format).getOrElse(AddHeadersConfig())
    Right(ctx.otoroshiRequest.copy(headers = ctx.otoroshiRequest.headers ++ config.requestHeaders))
  }

  override def transformResponseSync(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpResponse] = {
    val config = ctx.cachedConfig(internalName)(AddHeadersConfig.format).getOrElse(AddHeadersConfig())
    Right(ctx.otoroshiResponse.copy(headers = ctx.otoroshiResponse.headers ++ config.responseHeaders))
  }
}
```

Note the use of `transformRequestSync` / `transformResponseSync` instead of their async counterparts. When your logic is synchronous, prefer the sync variants and set `isTransformRequestAsync = false` / `isTransformResponseAsync = false` for better performance.

## Writing a backend call plugin

A backend call plugin replaces or wraps the default HTTP call to the backend target:

```scala
package otoroshi_plugins.mycompany

import akka.stream.Materializer
import akka.util.ByteString
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}

class MockBackend extends NgBackendCall {

  override def name: String                                = "Mock Backend"
  override def description: Option[String]                 = "Returns a static JSON response".some
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Other)
  override def steps: Seq[NgStep]                          = Seq(NgStep.CallBackend)
  override def defaultConfigObject: Option[NgPluginConfig] = None
  override def useDelegates: Boolean                       = false

  override def callBackend(
      ctx: NgbBackendCallContext,
      delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]]
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val body = Json.obj("message" -> "Hello from mock backend", "request_path" -> ctx.request.path)
    val bodyBytes = ByteString(Json.stringify(body))
    inMemoryBodyResponse(200, Map("Content-Type" -> "application/json"), bodyBytes).vfuture
  }
}
```

If you want to wrap the default backend call (e.g., add caching), set `useDelegates = true` and call `delegates()` to execute the actual backend call.

## Writing a pre-routing plugin

A pre-routing plugin runs before access validation and is typically used to extract custom credentials:

```scala
package otoroshi_plugins.mycompany

import akka.Done
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}

class CustomTokenExtractor extends NgPreRouting {

  override def name: String                                = "Custom Token Extractor"
  override def description: Option[String]                 = "Extracts a custom token from a header".some
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.PreRouting)
  override def steps: Seq[NgStep]                          = Seq(NgStep.PreRoute)
  override def defaultConfigObject: Option[NgPluginConfig] = None

  override def preRoute(
      ctx: NgPreRoutingContext
  )(implicit env: Env, ec: ExecutionContext): Future[Either[NgPreRoutingError, Done]] = {
    ctx.request.headers.get("X-Custom-Token") match {
      case Some(token) =>
        // Store the token in attrs for downstream plugins to use
        ctx.attrs.put(otoroshi.plugins.Keys.ExtraAnalyticsDataKey -> Json.obj("custom_token" -> token))
        NgPreRouting.futureDone
      case None =>
        NgPreRouting.futureDone // don't block, just skip
    }
  }
}
```

## Writing a route matcher

A route matcher adds custom matching logic on top of the standard domain/path matching:

```scala
package otoroshi_plugins.mycompany

import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import scala.util.{Failure, Success, Try}

case class TimeBasedMatcherConfig(startHour: Int = 9, endHour: Int = 17) extends NgPluginConfig {
  def json: JsValue = Json.obj("start_hour" -> startHour, "end_hour" -> endHour)
}

object TimeBasedMatcherConfig {
  val format = new Format[TimeBasedMatcherConfig] {
    override def reads(json: JsValue): JsResult[TimeBasedMatcherConfig] = Try {
      TimeBasedMatcherConfig(
        startHour = json.select("start_hour").asOpt[Int].getOrElse(9),
        endHour = json.select("end_hour").asOpt[Int].getOrElse(17)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: TimeBasedMatcherConfig): JsValue = o.json
  }
}

class TimeBasedMatcher extends NgRouteMatcher {

  override def name: String                                = "Time-Based Matcher"
  override def description: Option[String]                 = "Only matches during business hours".some
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.TrafficControl)
  override def steps: Seq[NgStep]                          = Seq(NgStep.MatchRoute)
  override def defaultConfigObject: Option[NgPluginConfig] = Some(TimeBasedMatcherConfig())

  override def matches(ctx: NgRouteMatcherContext)(implicit env: Env): Boolean = {
    val config = ctx.cachedConfig(internalName)(TimeBasedMatcherConfig.format).getOrElse(TimeBasedMatcherConfig())
    val hour = java.time.LocalTime.now().getHour
    hour >= config.startHour && hour < config.endHour
  }
}
```

## Writing a request sink

A request sink handles requests that did not match any route:

```scala
package otoroshi_plugins.mycompany

import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.{Result, Results}

import scala.concurrent.{ExecutionContext, Future}

class CustomNotFoundSink extends NgRequestSink {

  override def name: String                                = "Custom 404 Handler"
  override def description: Option[String]                 = "Returns a custom 404 response".some
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Other)
  override def steps: Seq[NgStep]                          = Seq(NgStep.Sink)
  override def defaultConfigObject: Option[NgPluginConfig] = None

  override def matches(ctx: NgRequestSinkContext)(implicit env: Env, ec: ExecutionContext): Boolean = true

  override def handle(ctx: NgRequestSinkContext)(implicit env: Env, ec: ExecutionContext): Future[Result] = {
    Results.NotFound(Json.obj(
      "error" -> "not_found",
      "message" -> s"No route matched: ${ctx.request.method} ${ctx.request.path}"
    )).vfuture
  }
}
```

## Plugin configuration

### Config object pattern

Define a case class extending `NgPluginConfig` with a companion object providing a `Format`:

```scala
case class MyPluginConfig(threshold: Int = 100) extends NgPluginConfig {
  def json: JsValue = MyPluginConfig.format.writes(this)
}

object MyPluginConfig {
  val format = new Format[MyPluginConfig] {
    override def reads(json: JsValue): JsResult[MyPluginConfig] = Try {
      MyPluginConfig(threshold = json.select("threshold").asOpt[Int].getOrElse(100))
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: MyPluginConfig): JsValue = Json.obj("threshold" -> o.threshold)
  }
}
```

Then in your plugin:

```scala
override def defaultConfigObject: Option[NgPluginConfig] = Some(MyPluginConfig())
```

### Reading configuration in plugin methods

Use `cachedConfig` for optimal performance (5-second TTL cache):

```scala
val config = ctx.cachedConfig(internalName)(MyPluginConfig.format).getOrElse(MyPluginConfig())
```

### UI form generation

To generate a configuration form in the Otoroshi UI, provide `configFlow` (field order) and `configSchema` (field types):

```scala
override def configFlow: Seq[String] = Seq("threshold")
override def configSchema: Option[JsObject] = Some(Json.obj(
  "threshold" -> Json.obj(
    "type"  -> "number",
    "label" -> "Request threshold",
    "props" -> Json.obj(
      "label"  -> "Threshold",
      "suffix" -> "requests"
    )
  )
))
```

Set `noJsForm = true` if you provide your own form via a frontend extension.

### Reading from Otoroshi configuration file

You can also read values from the static Otoroshi configuration file:

```scala
val maxSize = env.configuration.getOptional[Long]("my-plugin.max-size").getOrElse(4 * 1024 * 1024L)
```

With a custom config file:

```hocon
include "application.conf"

my-plugin {
  max-size = 2048
}
```

Start Otoroshi with: `java -Dconfig.file=/path/to/custom.conf -jar otoroshi.jar`

## Plugin lifecycle

Plugins can implement lifecycle hooks from the `StartableAndStoppable` trait:

```scala
class MyPlugin extends NgAccessValidator {
  // ... metadata overrides ...

  override def start(env: Env): Future[Unit] = {
    // Called when the plugin is loaded (at startup or when a route is created/updated)
    println("Plugin started!")
    Future.successful(())
  }

  override def stop(env: Env): Future[Unit] = {
    // Called when the plugin is unloaded (at shutdown or when a route is deleted/updated)
    println("Plugin stopped!")
    Future.successful(())
  }
}
```

## Listening to internal events

Any plugin can listen to Otoroshi internal events by overriding the `InternalEventListener` methods:

```scala
class MyPlugin extends NgAccessValidator {
  // ... metadata overrides ...

  override def listening: Boolean = true

  override def onEvent(evt: otoroshi.events.OtoroshiEvent)(implicit env: Env): Unit = {
    // React to internal events (alerts, analytics events, etc.)
  }
}
```

## Building and deploying

### Package as a JAR

```sh
sbt package
```

### Run with Otoroshi

Add the JAR to the classpath:

```sh
java -cp "/path/to/my-plugins.jar:/path/to/otoroshi.jar" play.core.server.ProdServerStart
```

### Using external libraries

If your plugin depends on external libraries, add them to the classpath too:

```sh
java -cp "/path/to/library.jar:/path/to/my-plugins.jar:/path/to/otoroshi.jar" play.core.server.ProdServerStart
```

Be careful as external libraries can conflict with libraries already bundled in Otoroshi.

### Referencing a plugin in a route

In the route configuration, reference your plugin with the `cp:` prefix:

```json
{
  "plugin": "cp:otoroshi_plugins.mycompany.RequiredHeaderValidator",
  "enabled": true,
  "debug": false,
  "include": [],
  "exclude": [],
  "bound_listeners": [],
  "config": {
    "header_name": "X-Api-Token"
  }
}
```

In the Otoroshi UI, your plugin will appear in the plugin selector with its `name` and `description`.

## Performance tips

* Set `isPreRouteAsync = false`, `isAccessAsync = false`, `isTransformRequestAsync = false`, or `isTransformResponseAsync = false` when your logic is synchronous. This avoids unnecessary `Future` wrapping.
* Set `usesCallbacks = false` if you don't use `beforeRequest`/`afterRequest`.
* Set `transformsRequest = false` or `transformsResponse = false` if you only transform one direction.
* Set `transformsError = false` if you don't handle error responses.
* Use `cachedConfig` instead of `rawConfig` to avoid re-parsing JSON configuration on every request.
* For body transformation, use streaming (`Source[ByteString, _]`) instead of materializing the entire body into memory when possible.

## Full examples

Example projects from Cloud APIM:

* Basic plugin: [Cloud APIM Mailer plugin](https://github.com/cloud-apim/otoroshi-plugin-mailer)
* Custom datastore: [Cloud APIM Couchbase plugin](https://github.com/cloud-apim/otoroshi-plugin-couchbase)
* Full extensions with plugins, entities, and admin UIs:
    * [Cloud APIM LLM Extension](https://github.com/cloud-apim/otoroshi-llm-extension)
    * [Cloud APIM Biscuit Studio](https://github.com/cloud-apim/otoroshi-biscuit-studio)

All built-in plugins are available as reference implementations on [GitHub](https://github.com/MAIF/otoroshi/tree/master/otoroshi/app/next/plugins).

## Related

* [Plugin system](./plugins.md) - Plugin types reference and lifecycle steps
* [Built-in plugins](./built-in-plugins.mdx) - All built-in plugin references
* [Admin extensions](../topics/admin-extensions.md) - Extend Otoroshi with custom entities, routes, and UIs
* [WASM plugins](../topics/wasm-usage.mdx) - Write plugins in any language compiled to WASM
