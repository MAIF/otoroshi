# Create plugins

When everything has failed and you absolutely need a feature in Otoroshi to make your use case work, there is a solution. Plugins is the feature in Otoroshi that allow you to code how Otoroshi should behave when receiving, validating and routing an http request. With request plugin, you can change request / response headers and request / response body the way you want, provide your own apikey, etc.

## Plugin types

there are many plugin types explained @ref:[here](./plugins.md) 

## Code and signatures

* https://github.com/MAIF/otoroshi/blob/master/otoroshi/app/next/plugins/api.scala


for more information about APIs you can use

* https://www.playframework.com/documentation/2.8.x/api/scala/index.html#package
* https://www.playframework.com/documentation/2.8.x/api/scala/index.html#play.api.mvc.Results
* https://github.com/MAIF/otoroshi
* https://doc.akka.io/docs/akka/2.5/stream/index.html
* https://doc.akka.io/api/akka/current/akka/stream/index.html
* https://doc.akka.io/api/akka/current/akka/stream/scaladsl/Source.html

## Plugin examples

@ref:[A lot of plugins](./built-in-plugins.md) comes with otoroshi, you can find them on [github](https://github.com/MAIF/otoroshi/tree/master/otoroshi/app/next/plugins)

## Providing a transformer from Java classpath

You can write your own transformer using your favorite IDE. Just create an SBT project with the following dependencies. It can be quite handy to manage the source code like any other piece of code, and it avoid the compilation time for the script at Otoroshi startup.

```scala
lazy val root = (project in file(".")).
  settings(
    inThisBuild(List(
      organization := "com.example",
      scalaVersion := "2.12.7",
      version      := "0.1.0-SNAPSHOT"
    )),
    name := "request-transformer-example",
    libraryDependencies += "fr.maif" %% "otoroshi" % "17.x.x"
  )
```


then, you can write something like 

```scala
package otoroshi_plugins.mycompany.demo

import akka.stream.Materializer
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.Result

import scala.concurrent.{ExecutionContext, Future}
import scala.util._

case class BodyLengthLimiterConfig(maxRequestBodySize: Option[Long] = None, maxResponseBodySize: Option[Long] = None) extends NgPluginConfig {
  def json: JsValue = BodyLengthLimiterConfig.format.writes(this)
}

object BodyLengthLimiterConfig {
  val configFlow: Seq[String]        = Seq("max_request_body_size", "max_response_body_size")
  val configSchema: Option[JsObject] = Some(
    Json.obj(
      "max_response_body_size" -> Json.obj(
        "type"  -> "number",
        "label" -> "Max response length",
        "props" -> Json.obj(
          "label"  -> "Max response Length",
          "suffix" -> "bytes"
        )
      ),
      "max_request_body_size" -> Json.obj(
        "type"  -> "number",
        "label" -> "Max request length",
        "props" -> Json.obj(
          "label"  -> "Max request Length",
          "suffix" -> "bytes"
        )
      )
    )
  )
  val format = new Format[BodyLengthLimiterConfig] {
    override def reads(json: JsValue): JsResult[BodyLengthLimiterConfig] = Try {
      BodyLengthLimiterConfig(
        maxRequestBodySize = json.select("max_request_body_size").asOpt[Long],
        maxResponseBodySize = json.select("max_response_body_size").asOpt[Long],
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }
    override def writes(o: BodyLengthLimiterConfig): JsValue = Json.obj(
      "max_request_body_size" -> o.maxRequestBodySize,
      "max_response_body_size" -> o.maxResponseBodySize
    )
  }
}

class BodyLengthLimiter extends NgRequestTransformer {

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest, NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Transformations)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = true
  override def transformsResponse: Boolean                 = true
  override def name: String                                = "Body length limiter"
  override def description: Option[String]                 = "This plugin will limit request and response body length".some
  override def defaultConfigObject: Option[NgPluginConfig] = Some(BodyLengthLimiterConfig())
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = BodyLengthLimiterConfig.configFlow
  override def configSchema: Option[JsObject] = BodyLengthLimiterConfig.configSchema

  override def transformRequest(ctx: NgTransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val config = ctx.cachedConfig(internalName)(BodyLengthLimiterConfig.format).getOrElse(BodyLengthLimiterConfig())
    val maxFromConfigFile = env.configuration.getOptional[Long]("my-transformer.maxRequestBodySize")
    val max: Long = config.maxRequestBodySize.orElse(maxFromConfigFile).getOrElse(4 * 1024 * 1024)
    Right(ctx.otoroshiRequest.copy(body = ctx.otoroshiRequest.body.limitWeighted(max)(_.size))).vfuture
  }

  override def transformResponse(ctx: NgTransformerResponseContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val config = ctx.cachedConfig(internalName)(BodyLengthLimiterConfig.format).getOrElse(BodyLengthLimiterConfig())
    val maxFromConfigFile = env.configuration.getOptional[Long]("my-transformer.maxResponseBodySize")
    val max: Long = config.maxResponseBodySize.orElse(maxFromConfigFile).getOrElse(4 * 1024 * 1024)
    Right(ctx.otoroshiResponse.copy(body = ctx.otoroshiResponse.body.limitWeighted(max)(_.size))).vfuture
  }
}
```

@@@ warning
you MUST provide plugins that lies in the `otoroshi_plugins` package or in a sub-package of `otoroshi_plugins`. If you do not, your plugin will not be found by otoroshi. for example

```scala
package otoroshi_plugins.mycompany.demo
```
@@@

When your code is ready, create a jar file 

```
sbt package
```

and add the jar file to the Otoroshi classpath

```sh
java -cp "/path/to/transformer.jar:$/path/to/otoroshi.jar" play.core.server.ProdServerStart
```

then, in the route designer, you can chose your transformer in the list. If you want to do it from the API, you have to defined the transformerRef using `cp:` prefix like 

```json
{
  "plugin": "cp:otoroshi_plugins.mycompany.demo.BodyLengthLimiter",
  "enabled": true,
  "debug": false,
  "include": [],
  "exclude": [],
  "bound_listeners": [],
  "config": {
    "max_request_body_size": null,
    "max_response_body_size": null
  }
}
```

## Getting custom configuration from the Otoroshi config. file

Let say you need to provide custom configuration values for a script, then you can customize a configuration file of Otoroshi

```hocon
include "application.conf"

my-transformer {
  env = "prod"
  maxRequestBodySize = 2048
  maxResponseBodySize = 2048
}
```

then start Otoroshi like

```sh
java -Dconfig.file=/path/to/custom.conf -jar otoroshi.jar
```

## Using a library that is not embedded in Otoroshi

Just use the `classpath` option when running Otoroshi

```sh
java -cp "/path/to/library.jar:/path/to/transformer.jar:$/path/to/otoroshi.jar" play.core.server.ProdServerStart
```

Be carefull as your library can conflict with other libraries used by Otoroshi and affect its stability

## Full examples

you can find some example projects on the [Cloud APIM]() github account: 

- basic plugin example: [Cloud APIM Mailer plugin](https://github.com/cloud-apim/otoroshi-plugin-mailer)
- custom datastore and custom data exporter exporter: [Cloud APIM Couchbase plugin](https://github.com/cloud-apim/otoroshi-plugin-couchbase)
- extensions with multiple plugins, entities, admin APIs/UIs
    - [Cloud APIM LLM Extension](https://github.com/cloud-apim/otoroshi-llm-extension)
    - [Cloud APIM Biscuit Studio](https://github.com/cloud-apim/otoroshi-biscuit-studio) 
