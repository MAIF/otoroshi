# Request transformers

When everything has failed and you absolutely need a feature in Otoroshi to make your use case work, there is a solution. Request transformer is an experimental feature hidden behind a feature flag that allow you to code how Otoroshi should behave when receiving and rounting an http request. With request transformers, you can change request / response headers and request / response body to way you want.

@@@ warning
Request transformers is a **very** powerful feature that allow you to do almost everything you want. But like any powerful feature, it comes with a price. The fact that you are responsible for how the request is transformed makes you responsible for all issues introduced by the transformer. If you block the current thread, introduce big latency, generate uncatched exceptions, etc. it's **your fault**. You have to ensure that your code is fast, non blocking, safe, etc. In any case, Otoroshi developpers will not responsible for issues caused by a request transformer.

And always remember this quote from ~~uncle Ben~~ Winston Churchill:

<p style="font-style: italic; text-align: right">"Where there is great power there is great responsibility"</p>
@@@

## Enabling request transformers

First you have to enable Otoroshi request transformers with the `-Dotoroshi.scripts.enabled=true` flag, or using env. variable `OTOROSHI_SCRIPTS_ENABLED=true`.

## Anatomy of a transformer

A request transformer is a piece of Scala code than can handle the complete lifecycle of an http request made on Otoroshi

```scala
case class HttpRequest(url: String, method: String, headers: Map[String, String], query: Map[String, String]) {
  lazy val host: String = headers.getOrElse("Host", "")
  lazy val uri: Uri = Uri(url)
  lazy val scheme: String = uri.scheme
  lazy val authority: Uri.Authority = uri.authority
  lazy val fragment: Option[String] = uri.fragment
  lazy val path: String = uri.path.toString()
  lazy val queryString: Option[String] = uri.rawQueryString
  lazy val relativeUri: String = uri.toRelative.toString()
}
case class HttpResponse(status: Int, headers: Map[String, String])

trait RequestTransformer {

  /**
   * See RequestTransformer.transformRequest
   */
  def transformRequestSync(
    snowflake: String, // a cluster unique request id
    rawRequest: HttpRequest, // the http request as received by Otoroshi
    otoroshiRequest: HttpRequest, // the http request modified by Otoroshi, ready to be sent to the target service
    desc: ServiceDescriptor, // the service description for the request 
    apiKey: Option[ApiKey] = None, // the api key used, if one
    user: Option[PrivateAppsUser] = None // the user, if one
  )(implicit 
      env: Env, // the otoroshi environnment
      ec: ExecutionContext, // the threadpool for the request to perform async actions
      mat: Materializer // the akka materializer to work with streams
  ): Either[Result, HttpRequest] = {
    Right(otoroshiRequest)
  }

  /**
   * Transform the request forwarded from Otoroshi to the target service
   */
  def transformRequest(
    snowflake: String, // a cluster unique request id
    rawRequest: HttpRequest, // the http request as received by Otoroshi
    otoroshiRequest: HttpRequest, // the http request modified by Otoroshi, ready to be sent to the target service
    desc: ServiceDescriptor, // the service description for the request 
    apiKey: Option[ApiKey] = None, // the api key used, if one
    user: Option[PrivateAppsUser] = None // the user, if one
  )(implicit 
      env: Env, // the otoroshi environnment
      ec: ExecutionContext, // the threadpool for the request to perform async actions
      mat: Materializer // the akka materializer to work with streams
    ): Future[Either[Result, HttpRequest]] = { // return a future of Either. On the left side, an http error if there was en err. On the Right side, the transformed http request
    FastFuture.successful(transformRequestSync(snowflake, rawRequest, otoroshiRequest, desc, apiKey, user)(env, ec, mat))
  }

  /**
   * See RequestTransformer.transformResponse
   */
  def transformResponseSync(
    snowflake: String, // a cluster unique request id
    rawResponse: HttpResponse, // the http response as received by Otoroshi from the target service
    otoroshiResponse: HttpResponse, // the http response modified by Otoroshi, ready to be sent back to the client
    desc: ServiceDescriptor, // the service description for the request 
    apiKey: Option[ApiKey] = None, // the api key used, if one
    user: Option[PrivateAppsUser] = None // the user, if one
  )(implicit 
      env: Env,  // the otoroshi environnment
      ec: ExecutionContext, // the threadpool for the request to perform async actions
      mat: Materializer // the akka materializer to work with streams
  ): Either[Result, HttpResponse] = {
    Right(otoroshiResponse)
  }

  /**
   * Transform the response from the target to the client
   */ 
  def transformResponse(
    snowflake: String, // a cluster unique request id
    rawResponse: HttpResponse, // the http response as received by Otoroshi from the target service
    otoroshiResponse: HttpResponse, // the http response modified by Otoroshi, ready to be sent back to the client
    desc: ServiceDescriptor, // the service description for the request 
    apiKey: Option[ApiKey] = None, // the api key used, if one
    user: Option[PrivateAppsUser] = None // the user, if one
  )(implicit 
      env: Env, // the otoroshi environnment
      ec: ExecutionContext, // the threadpool for the request to perform async actions
      mat: Materializer // the akka materializer to work with streams
  ): Future[Either[Result, HttpResponse]] = { // return a future of Either. On the left side, an http error if there was en err. On the Right side, the transformed http response
    FastFuture.successful(transformResponseSync(snowflake, rawResponse, otoroshiResponse, desc, apiKey, user)(env, ec, mat))
  }

  /**
   * Transform the request body forwarded from Otoroshi to the target service
   * It uses akka-stream (see https://doc.akka.io/docs/akka/2.5/index.html)
   */
  def transformRequestBody(
    snowflake: String, // a cluster unique request id
    body: Source[ByteString, _], // the body of the request, a stream of byte array
    rawRequest: HttpRequest, // the http request as received by Otoroshi
    otoroshiRequest: HttpRequest, // the http request modified by Otoroshi, ready to be sent to the target service
    desc: ServiceDescriptor, // the service description for the request 
    apiKey: Option[ApiKey] = None, // the api key used, if one
    user: Option[PrivateAppsUser] = None // the user, if one
  )(implicit 
      env: Env, // the otoroshi environnment
      ec: ExecutionContext, // the threadpool for the request to perform async actions
      mat: Materializer // the akka materializer to work with streams
  ): Source[ByteString, _] = { // return a stream of byte array
    body
  }

  /**
   * Transform the response body forwarded from target to the client. 
   * It uses akka-stream (see https://doc.akka.io/docs/akka/2.5/index.html)
   */
  def transformResponseBody(
    snowflake: String, // a cluster unique request id
    body: Source[ByteString, _], // the body of the response, a stream of byte array
    rawResponse: HttpResponse, // the http response as received by Otoroshi from the target service
    otoroshiResponse: HttpResponse, // the http response modified by Otoroshi, ready to be sent back to the client
    desc: ServiceDescriptor, // the service description for the request 
    apiKey: Option[ApiKey] = None, // the api key used, if one
    user: Option[PrivateAppsUser] = None // the user, if one
  )(implicit 
      env: Env, // the otoroshi environnment
      ec: ExecutionContext, // the threadpool for the request to perform async actions
      mat: Materializer // the akka materializer to work with streams
  ): Source[ByteString, _] = {
    body
  }
}
```

for more information about APIs you can use

* https://www.playframework.com/documentation/2.6.x/api/scala/index.html#package
* https://www.playframework.com/documentation/2.6.x/api/scala/index.html#play.api.mvc.Results
* https://github.com/MAIF/otoroshi
* https://doc.akka.io/docs/akka/2.5/stream/index.html
* https://doc.akka.io/api/akka/current/akka/stream/index.html
* https://doc.akka.io/api/akka/current/akka/stream/scaladsl/Source.html

## Writing a transformer from Otoroshi UI

Log into Otoroshi and go to `Settings (cog icon) / Scripts`. Here you can create multiple request transformer scripts and associate it with service descriptor later.

@@@ div { .centered-img }
<img src="../img/scripts-1.png" />
@@@

when you write an instance of a transformer in the Otoroshi UI, do the following

```scala
import akka.stream.Materializer
import env.Env
import models.{ApiKey, PrivateAppsUser, ServiceDescriptor}
import otoroshi.script._
import play.api.Logger
import play.api.mvc.{Result, Results}
import scala.util._
import scala.concurrent.{ExecutionContext, Future}

class MyTransformer extends RequestTransformer {

  val logger = Logger("my-transformer")

  // implements the methods you want
}

// WARN: do not forget this line to provide a working instance of your transformer to Otoroshi
new MyTransformer()
```

You can use the compile button to check if the script compiles, or code the transformer in your IDE (see next point).

Then go to a service descriptor, scroll to the bottom of the page, and select your transformer in the list

@@@ div { .centered-img }
<img src="../img/scripts-2.png" />
@@@

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
    resolvers += Resolver.bintrayRepo("maif", "maven"),
    libraryDependencies += "fr.maif.otoroshi" %% "otoroshi" % "1.x.x"
  )
```

When your code is ready, create a jar file 

```
sbt package
```

and add the jar file to the Otoroshi classpath

```sh
java -Dotoroshi.scripts.enabled=true -cp "/path/to/transformer.jar:$/path/to/otoroshi.jar" play.core.server.ProdServerStart
```

then, in your service descriptor, you can chose your transformer in the list. If you want to do it from the API, you have to defined the transformerRef using `cp:` prefix like 

```json
{
  "transformerRef": "cp:my.class.package.MyTransformer"
}
```

## Getting custom configuration from the Otoroshi config. file

Let say you need to provide custom configuration values for a script, then you can customize a configuration file of Otoroshi

```hocon
include "application.conf"

otoroshi {
  scripts {
    enabled = true
  }
}

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

then, in your transformer, you can write something like 

```scala
package com.example.otoroshi

import akka.stream.Materializer
import akka.stream.scaladsl._
import akka.util.ByteString
import env.Env
import models.{ApiKey, PrivateAppsUser, ServiceDescriptor}
import otoroshi.script._
import play.api.Logger
import play.api.mvc.{Result, Results}
import scala.util._
import scala.concurrent.{ExecutionContext, Future}

class BodyLengthLimiter extends RequestTransformer {

  override def transformResponseBody(
    snowflake: String,
    body: Source[ByteString, _],
    rawResponse: HttpResponse,
    otoroshiResponse: HttpResponse,
    desc: ServiceDescriptor,
    apiKey: Option[ApiKey],
    user: Option[PrivateAppsUser])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Source[ByteString, _] = {
    val max = env.configuration.getOptional[Long]("my-transformer.maxResponseBodySize").getOrElse(Long.MaxValue)
    body.limitWeighted(max)(_.size)
  }

  override def transformRequestBody(
    snowflake: String,
    body: Source[ByteString, _],
    rawRequest: HttpRequest,
    otoroshiRequest: HttpRequest,
    desc: ServiceDescriptor,
    apiKey: Option[ApiKey],
    user: Option[PrivateAppsUser])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Source[ByteString, _] = {
    val max = env.configuration.getOptional[Long]("my-transformer.maxRequestBodySize").getOrElse(Long.MaxValue)
    body.limitWeighted(max)(_.size)
  }
}
```

## Using a library that is not embedded in Otoroshi

Just use the `classpath` option when running Otoroshi

```sh
java -Dotoroshi.scripts.enabled=true -cp "/path/to/library.jar:$/path/to/otoroshi.jar" play.core.server.ProdServerStart
```

Be carefull as your library can conflict with other libraries used by Otoroshi and affect its stability
