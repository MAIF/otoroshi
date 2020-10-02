package otoroshi.plugins.izanami

import java.util.concurrent.atomic.AtomicBoolean

import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import env.Env
import otoroshi.script.{AfterRequestContext, BeforeRequestContext, HttpRequest, RequestTransformer, TransformerRequestBodyContext, TransformerRequestContext}
import play.api.libs.json.{JsNull, JsObject, JsValue, Json}
import play.api.mvc.{Result, Results}
import otoroshi.utils.syntax.implicits._
import play.api.libs.ws.WSAuthScheme

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future, Promise}

case class IzanamiProxyConfig(
  path: String,
  featurePattern: String,
  configPattern: String,
  autoContext: Boolean,
  featuresEnabled: Boolean,
  featuresWithContextEnabled: Boolean,
  configurationEnabled: Boolean,
  izanamiUrl: String,
  izanamiClientId: String,
  izanamiClientSecret: String,
  timeout: FiniteDuration
)

class IzanamiProxy extends RequestTransformer {

  override def name: String = "Izanami APIs Proxy"

  override def defaultConfig: Option[JsObject] =
    Some(
      Json.obj(
        "IzanamiProxy" -> Json.obj(
          "path"             -> "/api/izanami",
          "featurePattern" -> "*",
          "configPattern" -> "*",
          "autoContext" -> false,
          "featuresEnabled" -> true,
          "featuresWithContextEnabled" -> true,
          "configurationEnabled" -> false,
          "izanamiUrl" -> "https://izanami.foo.bar",
          "izanamiClientId" -> "client",
          "izanamiClientSecret" -> "secret",
          "timeout" -> 5000
        )
      )
    )

  override def description: Option[String] =
    Some(
      s"""This plugin exposes routes to proxy Izanami configuration and features tree APIs.
        |
        |This plugin can accept the following configuration
        |
        |```json
        |${Json.prettyPrint(defaultConfig.get)}
        |```
    """.stripMargin
    )

  def readConfig(ctx: TransformerRequestContext): IzanamiProxyConfig = {
    val rawConfig = ctx.configFor("IzanamiProxy")
    IzanamiProxyConfig(
      path = (rawConfig \ "path").asOpt[String].getOrElse("/api/izanami"),
      featurePattern = (rawConfig \ "featurePattern").asOpt[String].getOrElse("*"),
      configPattern = (rawConfig \ "configPattern").asOpt[String].getOrElse("*"),
      autoContext = (rawConfig \ "autoContext").asOpt[Boolean].getOrElse(false),
      featuresEnabled = (rawConfig \ "featuresEnabled").asOpt[Boolean].getOrElse(true),
      featuresWithContextEnabled = (rawConfig \ "featuresWithContextEnabled").asOpt[Boolean].getOrElse(true),
      configurationEnabled = (rawConfig \ "configurationEnabled").asOpt[Boolean].getOrElse(false),
      izanamiUrl = (rawConfig \ "izanamiUrl").asOpt[String].getOrElse("https://izanami.foo.bar"),
      izanamiClientId = (rawConfig \ "izanamiClientId").asOpt[String].getOrElse("client"),
      izanamiClientSecret = (rawConfig \ "izanamiClientSecret").asOpt[String].getOrElse("secret"),
      timeout = (rawConfig \ "timeout").asOpt[Long].map(_.millis).getOrElse(5000.millis),
    )
  }

  private val awaitingRequests = new TrieMap[String, Promise[Source[ByteString, _]]]()

  override def beforeRequest(ctx: BeforeRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    awaitingRequests.putIfAbsent(ctx.snowflake, Promise[Source[ByteString, _]])
    funit
  }

  override def afterRequest(ctx: AfterRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    awaitingRequests.remove(ctx.snowflake)
    funit
  }

  def getFeatures(ctx: TransformerRequestContext, config: IzanamiProxyConfig)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    if (config.autoContext) {
      env.Ws
        .url(s"${config.izanamiUrl}/api/tree/features?pattern=${config.featurePattern}")
        .withRequestTimeout(config.timeout)
        .withHttpHeaders(
          "Izanami-Client-Id" -> config.izanamiClientId,
          "Izanami-Client-Secret" -> config.izanamiClientSecret
        )
        .withAuth(config.izanamiClientId, config.izanamiClientSecret, WSAuthScheme.BASIC)
        .post(ByteString(Json.stringify(Json.obj(
          "user" -> ctx.user.map(_.asJsonCleaned).getOrElse(JsNull).as[JsValue],
          "apikey" -> ctx.apikey.map(_.lightJson).getOrElse(JsNull).as[JsValue],
        )))).map { resp =>
          Results
            .Status(resp.status)(resp.json)
            .withHeaders(resp.headers.mapValues(_.last).filterNot(v => v._1.toLowerCase == "content-type" || v._1.toLowerCase == "content-length").toSeq: _*)
            .as(resp.header("Content-Type").getOrElse("application/json"))
            .left
        }
    } else {
      env.Ws
        .url(s"${config.izanamiUrl}/api/tree/features?pattern=${config.featurePattern}")
        .withRequestTimeout(config.timeout)
        .withHttpHeaders(
          "Izanami-Client-Id" -> config.izanamiClientId,
          "Izanami-Client-Secret" -> config.izanamiClientSecret
        )
        .withAuth(config.izanamiClientId, config.izanamiClientSecret, WSAuthScheme.BASIC)
        .get().map { resp =>
          Results
            .Status(resp.status)(resp.json)
            .withHeaders(resp.headers.mapValues(_.last).filterNot(v => v._1.toLowerCase == "content-type" || v._1.toLowerCase == "content-length").toSeq: _*)
            .as(resp.header("Content-Type").getOrElse("application/json"))
            .left
        }
    }
  }

  def getFeaturesWithBody(ctx: TransformerRequestContext, config: IzanamiProxyConfig)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    awaitingRequests.get(ctx.snowflake).map { promise =>

      val bodySource: Source[ByteString, _] = Source
        .future(promise.future)
        .flatMapConcat(s => s)

      bodySource.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
        env.Ws
          .url(s"${config.izanamiUrl}/api/tree/features?pattern=${config.featurePattern}")
          .withRequestTimeout(config.timeout)
          .withHttpHeaders(
            "Content-Type" -> "application/json",
            "Izanami-Client-Id" -> config.izanamiClientId,
            "Izanami-Client-Secret" -> config.izanamiClientSecret
          ).withAuth(config.izanamiClientId, config.izanamiClientSecret, WSAuthScheme.BASIC)
          .post(bodyRaw).map { resp =>
            Results
              .Status(resp.status)(resp.json)
              .withHeaders(resp.headers.mapValues(_.last).filterNot(v => v._1.toLowerCase == "content-type" || v._1.toLowerCase == "content-length").toSeq: _*)
              .as(resp.header("Content-Type").getOrElse("application/json"))
              .left
          }
      }
    }.getOrElse {
      Results.BadRequest(Json.obj("error" -> "bad body")).left.future
    }
  }

  def getConfig(ctx: TransformerRequestContext, config: IzanamiProxyConfig)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    env.Ws
      .url(s"${config.izanamiUrl}/api/tree/configs?pattern=${config.configPattern}")
      .withRequestTimeout(config.timeout)
      .withHttpHeaders(
        "Izanami-Client-Id" -> config.izanamiClientId,
        "Izanami-Client-Secret" -> config.izanamiClientSecret
      ).withAuth(config.izanamiClientId, config.izanamiClientSecret, WSAuthScheme.BASIC)
      .get().map { resp =>
        Results
          .Status(resp.status)(resp.json)
          .withHeaders(resp.headers.mapValues(_.last).filterNot(v => v._1.toLowerCase == "content-type" || v._1.toLowerCase == "content-length").toSeq: _*)
          .as(resp.header("Content-Type").getOrElse("application/json"))
          .left
    }
  }

  override def transformRequestWithCtx(
    ctx: TransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    val config = readConfig(ctx)
    (ctx.request.method.toLowerCase, ctx.request.path) match {
      case ("get", path)  if path == config.path + "/features" && config.featuresEnabled            => getFeatures(ctx, config)
      case ("post", path) if path == config.path + "/features" && config.featuresWithContextEnabled => getFeaturesWithBody(ctx, config)
      case ("get", path)  if path == config.path + "/configs"  && config.configurationEnabled       => getConfig(ctx, config)
      case _ => ctx.otoroshiRequest.right.future
    }
  }

  override def transformRequestBodyWithCtx(ctx: TransformerRequestBodyContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Source[ByteString, _] = {
    awaitingRequests.get(ctx.snowflake).map(_.trySuccess(ctx.body))
    ctx.body
  }
}
