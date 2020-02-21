# Otoroshi plugins

@@@ warning
This section is under rewrite. The following content is deprecated
@@@

When everything has failed and you absolutely need a feature in Otoroshi to make your use case work, there is a solution. Plugins is the feature in Otoroshi that allow you to code how Otoroshi should behave when receiving, validating and routing an http request. With request plugin, you can change request / response headers and request / response body the way you want, provide your own apikey, etc.

## Plugin types

there are many plugin types

* `request sinks` plugins: used when no services are matched in otoroshi. Can reply with any content
* `pre-routes` plugins: used to extract values (like custom apikeys) and provide them to other plugins or otoroshi engine
* `access validation` plugins: used to validate if a request can pass or not based on whatever you want
* `request transformer` plugins: used to transform request, responses and their body. Can be used to return arbitrary content
* `event listener` plugins: any plugin type can listen to otoroshi internal events and react to them


## Code and signatures

* https://github.com/MAIF/otoroshi/blob/master/otoroshi/app/script/requestsink.scala#L11-L16
* https://github.com/MAIF/otoroshi/blob/master/otoroshi/app/script/routing.scala#L60-L63
* https://github.com/MAIF/otoroshi/blob/master/otoroshi/app/script/accessvalidator.scala#L63-L82
* https://github.com/MAIF/otoroshi/blob/master/otoroshi/app/script/script.scala#L314-L455
* https://github.com/MAIF/otoroshi/blob/master/otoroshi/app/script/eventlistener.scala#L27-L48


for more information about APIs you can use

* https://www.playframework.com/documentation/2.6.x/api/scala/index.html#package
* https://www.playframework.com/documentation/2.6.x/api/scala/index.html#play.api.mvc.Results
* https://github.com/MAIF/otoroshi
* https://doc.akka.io/docs/akka/2.5/stream/index.html
* https://doc.akka.io/api/akka/current/akka/stream/index.html
* https://doc.akka.io/api/akka/current/akka/stream/scaladsl/Source.html

## Plugin examples

A lot of plugins comes with otoroshi, you can find it on [github](https://github.com/MAIF/otoroshi/tree/master/otoroshi/app/plugins)

## Writing a plugin from Otoroshi UI

Log into Otoroshi and go to `Settings (cog icon) / Plugins`. Here you can create multiple request transformer scripts and associate it with service descriptor later.

@@@ div { .centered-img }
<img src="../img/scripts-1.png" />
@@@

when you write for instance a transformer in the Otoroshi UI, do the following

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
