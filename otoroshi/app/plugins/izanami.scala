package otoroshi.plugins.izanami

import java.util.concurrent.atomic.AtomicBoolean
import akka.http.scaladsl.model.Uri
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import com.github.blemale.scaffeine.{Cache, Scaffeine}
import env.Env
import otoroshi.script.{AfterRequestContext, BeforeRequestContext, HttpRequest, HttpResponse, RequestTransformer, TransformerRequestBodyContext, TransformerRequestContext, TransformerResponseContext}
import otoroshi.utils.{RegexPool, TypedMap}
import play.api.libs.json.{JsNull, JsObject, JsValue, Json}
import play.api.mvc.{Cookie, RequestHeader, Result, Results}
import otoroshi.utils.syntax.implicits._
import play.api.libs.ws.{DefaultWSCookie, WSAuthScheme, WSCookie}
import otoroshi.security.IdGenerator
import otoroshi.utils.http.RequestImplicits._
import otoroshi.utils.http.{MtlsConfig, WSCookieWithSameSite}
import otoroshi.utils.http.WSCookieWithSameSite

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Success

case class IzanamiProxyConfig(
  path: String,
  featurePattern: String,
  configPattern: String,
  autoContext: Boolean,
  featuresEnabled: Boolean,
  featuresWithContextEnabled: Boolean,
  configurationEnabled: Boolean,
  mtls: MtlsConfig,
  izanamiUrl: String,
  izanamiClientId: String,
  izanamiClientSecret: String,
  timeout: FiniteDuration
)

case class IzanamiCanaryConfig(
  experimentId: String,
  configId: String,
  izanamiUrl: String,
  mtls: MtlsConfig,
  izanamiClientId: String,
  izanamiClientSecret: String,
  timeout: FiniteDuration,
  routeConfig: Option[JsObject]
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

  override def configFlow: Seq[String] = Seq(
    "featuresEnabled",
    "featuresWithContextEnabled",
    "configurationEnabled",
    "autoContext",
    "---",
    "path",
    "featurePattern",
    "configPattern",
    "---",
    "izanamiUrl",
    "izanamiClientId",
    "izanamiClientSecret",
    "timeout"
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
      mtls = MtlsConfig.format.reads(rawConfig.select("mtls").as[JsValue]).getOrElse(MtlsConfig())
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

case class IzanamiCanaryRoutingConfigRoute(
  route: String,
  variants: Map[String, String],
  default: String,
  wildcard: Boolean,
  exact: Boolean,
  regex: Boolean
)
case class IzanamiCanaryRoutingConfig(
  routes: Seq[IzanamiCanaryRoutingConfigRoute]
)

object IzanamiCanaryRoutingConfig {
  def fromJson(json: JsValue): IzanamiCanaryRoutingConfig = {
    IzanamiCanaryRoutingConfig(
      routes = json.select("routes").asArray.value.map { item =>
        IzanamiCanaryRoutingConfigRoute(
          route = item.select("route").asString,
          default = item.select("default").asString,
          variants = item.select("variants").asOpt[Map[String, String]].getOrElse(Map.empty),
          wildcard = item.select("wildcard").asOpt[Boolean].getOrElse(false),
          exact = item.select("exact").asOpt[Boolean].getOrElse(false),
          regex = item.select("regex").asOpt[Boolean].getOrElse(false),
        )
      }
    )
  }
}

class IzanamiCanary extends RequestTransformer {

  private val cookieJar = new TrieMap[String, WSCookie]()

  private val cache: Cache[String, JsValue] = Scaffeine()
    .recordStats()
    .expireAfterWrite(10.minutes)
    .maximumSize(1000)
    .build()

  override def name: String = "Izanami Canary Campaign"

  override def defaultConfig: Option[JsObject] = {
    Some(
      Json.obj(
        "IzanamiCanary" -> Json.obj(
          "experimentId" -> "foo:bar:qix",
          "configId" -> "foo:bar:qix:config",
          "izanamiUrl" -> "https://izanami.foo.bar",
          "izanamiClientId" -> "client",
          "izanamiClientSecret" -> "secret",
          "timeout" -> 5000,
          "mtls" -> MtlsConfig().json
        )
      )
    )
  }

  override def configFlow: Seq[String] = Seq(
    "experimentId",
    "configId",
    "---",
    "izanamiUrl",
    "izanamiClientId",
    "izanamiClientSecret",
    "timeout",
    "---",
    "mtls.certs",
    "mtls.trustedCerts",
    "mtls.mtls",
    "mtls.loose",
    "mtls.trustAll",
  )

  override def description: Option[String] = {
    Some(
      s"""This plugin allow you to perform canary testing based on an izanami experiment campaign (A/B test).
         |
         |This plugin can accept the following configuration
         |
         |```json
         |${Json.prettyPrint(defaultConfig.get)}
         |```
    """.stripMargin
    )
  }

  def readConfig(ctx: TransformerRequestContext): IzanamiCanaryConfig = {
    val rawConfig = ctx.configFor("IzanamiCanary")
    IzanamiCanaryConfig(
      experimentId = (rawConfig \ "experimentId").as[String],
      configId = (rawConfig \ "configId").asOpt[String].getOrElse((rawConfig \ "experimentId").as[String] + ":route_config"),
      izanamiUrl = (rawConfig \ "izanamiUrl").asOpt[String].getOrElse("https://izanami.foo.bar"),
      izanamiClientId = (rawConfig \ "izanamiClientId").asOpt[String].getOrElse("client"),
      izanamiClientSecret = (rawConfig \ "izanamiClientSecret").asOpt[String].getOrElse("secret"),
      timeout = (rawConfig \ "timeout").asOpt[Long].map(_.millis).getOrElse(5000.millis),
      mtls = MtlsConfig.format.reads(rawConfig.select("mtls").as[JsValue]).getOrElse(MtlsConfig()),
      routeConfig = rawConfig.select("routeConfig").asOpt[JsObject]
    )
  }

  def canaryId(ctx: TransformerRequestContext)(implicit env: Env): String = {
    val attrs: TypedMap = ctx.attrs
    val reqNumber: Option[Int] = attrs.get(otoroshi.plugins.Keys.RequestNumberKey)
    val maybeCanaryId: Option[String] = attrs.get(otoroshi.plugins.Keys.RequestCanaryIdKey)
    val canaryId: String = maybeCanaryId.getOrElse(IdGenerator.uuid + "-" + reqNumber.get)
    canaryId
  }

  def canaryCookie(cid: String, ctx: TransformerRequestContext)(implicit env: Env): WSCookie = {
    ctx.request.cookies.get("otoroshi-canary").map { cookie =>
      WSCookieWithSameSite(
        name = cookie.name,
        value = cookie.value,
        domain = cookie.domain,
        path = cookie.path.some,
        maxAge = cookie.maxAge.map(_.toLong),
        secure = cookie.secure,
        httpOnly = cookie.httpOnly,
        sameSite = cookie.sameSite
      )
    } getOrElse {
      WSCookieWithSameSite(
        name = "otoroshi-canary",
        value = s"${env.sign(cid)}::$cid",
        domain = ctx.request.theDomain.some,
        path = "/".some,
        maxAge = Some(2592000),
        secure = false,
        httpOnly = false,
        sameSite = Cookie.SameSite.Lax.some
      )
    }
  }

  def withCache(key: String)(f: String => Future[JsValue])(implicit ec: ExecutionContext): Future[JsValue] = {
    cache.getIfPresent(key).map(_.future).getOrElse {
      f(key).andThen {
        case Success(v) => cache.put(key, v)
      }
    }
  }

  def fetchIzanamiVariant(cid: String, config: IzanamiCanaryConfig, ctx: TransformerRequestContext)(implicit env: Env, ec: ExecutionContext): Future[String] = {
    withCache(s"${config.izanamiUrl}/api/experiments/${config.experimentId}/displayed?clientId=$cid") { url =>
      env.MtlsWs
        .url(url, config.mtls)
        .withRequestTimeout(config.timeout)
        .withHttpHeaders(
          "Content-Type" -> "application/json",
          "Izanami-Client-Id" -> config.izanamiClientId,
          "Izanami-Client-Secret" -> config.izanamiClientSecret
        )
        .withAuth(config.izanamiClientId, config.izanamiClientSecret, WSAuthScheme.BASIC)
        .post("").map(_.json)
    }.map(r => r.asObject.select("variant").select("id").asOpt[String].getOrElse(IdGenerator.uuid))
  }

  def fetchIzanamiRoutingConfig(config: IzanamiCanaryConfig, ctx: TransformerRequestContext)(implicit env: Env, ec: ExecutionContext): Future[IzanamiCanaryRoutingConfig] = {
    withCache(s"${config.izanamiUrl}/api/configs/${config.configId}") { url =>
      config.routeConfig match {
        case Some(c) => c.future
        case None => {
          env.MtlsWs
            .url(url, config.mtls)
            .withRequestTimeout(config.timeout)
            .withHttpHeaders(
              "Izanami-Client-Id" -> config.izanamiClientId,
              "Izanami-Client-Secret" -> config.izanamiClientSecret
            )
            .withAuth(config.izanamiClientId, config.izanamiClientSecret, WSAuthScheme.BASIC)
            .get().map(_.json)
        }
      }
    }.map { json =>
      val routing = json.asObject.select("value").as[JsObject]
      IzanamiCanaryRoutingConfig.fromJson(routing)
    }
  }

  override def transformRequestWithCtx(ctx: TransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    val config = readConfig(ctx)
    val cid = canaryId(ctx)
    val cookie = canaryCookie(cid, ctx)
    for {
      routing <- fetchIzanamiRoutingConfig(config, ctx)
      variant <- fetchIzanamiVariant(cid, config, ctx)
    } yield {
      val uri = ctx.otoroshiRequest.uri
      val path: Uri.Path = uri.path
      val pathStr = path.toString()
      val newPath: Uri.Path = routing.routes.find {
        case r if r.wildcard => RegexPool(r.route).matches(pathStr)
        case r if r.regex => RegexPool.regex(r.route).matches(pathStr)
        case r if r.exact => pathStr == r.route
        case r => pathStr.startsWith(r.route)
      } match {
        case Some(route) if route.wildcard || route.regex || route.exact => route.variants.get(variant) match {
          case Some(variantPath) => Uri.Path(variantPath)
          case None => Uri.Path(route.default)
        }
        case Some(route) =>
          val strippedPath = pathStr.replaceFirst(route.route, "")
          route.variants.get(variant) match {
            case Some(variantPathBeginning) => Uri.Path(variantPathBeginning + strippedPath)
            case None => Uri.Path(route.default + strippedPath)
          }
        case None => path
      }
      val newUri = uri.copy(path = newPath)
      val newUriStr = newUri.toString()
      cookieJar.put(ctx.snowflake, cookie)
      Right(ctx.otoroshiRequest.copy(url = newUriStr))
    }
  }

  override def transformResponseWithCtx(ctx: TransformerResponseContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpResponse]] = {
    cookieJar.get(ctx.snowflake).map { cookie =>
      val allCookies = (ctx.otoroshiResponse.cookies :+ cookie)
      val cookies = allCookies.distinct
      cookieJar.remove(ctx.snowflake)
      ctx.otoroshiResponse.copy(cookies = cookies).rightf
    } getOrElse {
      ctx.otoroshiResponse.rightf
    }
  }
}