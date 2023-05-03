package otoroshi.next.plugins

import akka.http.scaladsl.model.Uri
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.github.blemale.scaffeine.{Cache, Scaffeine}
import otoroshi.env.Env
import otoroshi.next.models.NgTlsConfig
import otoroshi.next.plugins.api._
import otoroshi.security.IdGenerator
import otoroshi.utils.RegexPool
import otoroshi.utils.cache.types.LegitTrieMap
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.http.WSCookieWithSameSite
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.libs.ws.{WSAuthScheme, WSCookie}
import play.api.mvc.{Cookie, Result, Results}

import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.util._

case class NgIzanamiV1ProxyConfig(
    path: String = "/api/izanami",
    featurePattern: String = "*",
    configPattern: String = "*",
    autoContext: Boolean = false,
    featuresEnabled: Boolean = true,
    featuresWithContextEnabled: Boolean = true,
    configurationEnabled: Boolean = false,
    tls: NgTlsConfig = NgTlsConfig.default,
    izanamiUrl: String = "https://izanami.foo.bar",
    clientId: String = "client",
    clientSecret: String = "secret",
    timeout: FiniteDuration = 500.millis
) extends NgPluginConfig {
  override def json: JsValue = Json.obj(
    "path"                          -> path,
    "feature_pattern"               -> featurePattern,
    "config_pattern"                -> configPattern,
    "auto_context"                  -> autoContext,
    "features_enabled"              -> featuresEnabled,
    "features_with_context_enabled" -> featuresWithContextEnabled,
    "configuration_enabled"         -> configurationEnabled,
    "tls"                           -> tls.json,
    "izanami_url"                   -> izanamiUrl,
    "client_id"                     -> clientId,
    "client_secret"                 -> clientSecret,
    "timeout"                       -> timeout.toMillis
  )
}

object NgIzanamiV1ProxyConfig {
  val format = new Format[NgIzanamiV1ProxyConfig] {
    override def writes(o: NgIzanamiV1ProxyConfig): JsValue             = o.json
    override def reads(json: JsValue): JsResult[NgIzanamiV1ProxyConfig] = Try {
      NgIzanamiV1ProxyConfig(
        path = json.select("path").asOpt[String].getOrElse("/api/izanami"),
        featurePattern = json.select("feature_pattern").asOpt[String].getOrElse("*"),
        configPattern = json.select("config_pattern").asOpt[String].getOrElse("*"),
        autoContext = json.select("auto_context").asOpt[Boolean].getOrElse(false),
        featuresEnabled = json.select("features_enabled").asOpt[Boolean].getOrElse(true),
        featuresWithContextEnabled = json.select("features_with_context_enabled").asOpt[Boolean].getOrElse(true),
        configurationEnabled = json.select("configuration_enabled").asOpt[Boolean].getOrElse(false),
        tls =
          json.select("tls").asOpt[JsValue].flatMap(NgTlsConfig.format.reads(_).asOpt).getOrElse(NgTlsConfig.default),
        izanamiUrl = json.select("izanami_url").asOpt[String].getOrElse("https://izanami.foo.bar"),
        clientId = json.select("client_id").asOpt[String].getOrElse("client"),
        clientSecret = json.select("client_secret").asOpt[String].getOrElse("secret"),
        timeout = json.select("timeout").asOpt[Long].getOrElse(5000L).millis
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
  }
}

class NgIzanamiV1Proxy extends NgRequestTransformer {

  override def name: String                                = "Izanami v1 APIs Proxy"
  override def description: Option[String]                 =
    "This plugin exposes routes to proxy Izanami configuration and features tree APIs".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgIzanamiV1ProxyConfig().some
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Integrations)
  override def steps: Seq[NgStep]                          = Seq(NgStep.TransformRequest)

  private def getFeatures(ctx: NgTransformerRequestContext, config: NgIzanamiV1ProxyConfig)(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): Future[Either[Result, NgPluginHttpRequest]] = {
    if (config.autoContext) {
      env.Ws
        .url(s"${config.izanamiUrl}/api/tree/features?pattern=${config.featurePattern}")
        .withRequestTimeout(config.timeout)
        .withHttpHeaders(
          "Izanami-Client-Id"     -> config.clientId,
          "Izanami-Client-Secret" -> config.clientSecret
        )
        .withAuth(config.clientId, config.clientSecret, WSAuthScheme.BASIC)
        .post(
          ByteString(
            Json.stringify(
              Json.obj(
                "user"   -> ctx.user.map(_.asJsonCleaned).getOrElse(JsNull).as[JsValue],
                "apikey" -> ctx.apikey.map(_.lightJson).getOrElse(JsNull).as[JsValue]
              )
            )
          )
        )
        .map { resp =>
          Results
            .Status(resp.status)(resp.json)
            .withHeaders(
              resp.headers
                .mapValues(_.last)
                .filterNot(v => v._1.toLowerCase == "content-type" || v._1.toLowerCase == "content-length")
                .toSeq: _*
            )
            .as(resp.header("Content-Type").getOrElse("application/json"))
            .left
        }
    } else {
      env.Ws
        .url(s"${config.izanamiUrl}/api/tree/features?pattern=${config.featurePattern}")
        .withRequestTimeout(config.timeout)
        .withHttpHeaders(
          "Izanami-Client-Id"     -> config.clientId,
          "Izanami-Client-Secret" -> config.clientSecret
        )
        .withAuth(config.clientId, config.clientSecret, WSAuthScheme.BASIC)
        .get()
        .map { resp =>
          Results
            .Status(resp.status)(resp.json)
            .withHeaders(
              resp.headers
                .mapValues(_.last)
                .filterNot(v => v._1.toLowerCase == "content-type" || v._1.toLowerCase == "content-length")
                .toSeq: _*
            )
            .as(resp.header("Content-Type").getOrElse("application/json"))
            .left
        }
    }
  }

  private def getFeaturesWithBody(
      ctx: NgTransformerRequestContext,
      config: NgIzanamiV1ProxyConfig,
      body: Source[ByteString, _]
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
      env.Ws
        .url(s"${config.izanamiUrl}/api/tree/features?pattern=${config.featurePattern}")
        .withRequestTimeout(config.timeout)
        .withHttpHeaders(
          "Content-Type"          -> "application/json",
          "Izanami-Client-Id"     -> config.clientId,
          "Izanami-Client-Secret" -> config.clientSecret
        )
        .withAuth(config.clientId, config.clientSecret, WSAuthScheme.BASIC)
        .post(bodyRaw)
        .map { resp =>
          Results
            .Status(resp.status)(resp.json)
            .withHeaders(
              resp.headers
                .mapValues(_.last)
                .filterNot(v => v._1.toLowerCase == "content-type" || v._1.toLowerCase == "content-length")
                .toSeq: _*
            )
            .as(resp.header("Content-Type").getOrElse("application/json"))
            .left
        }
    }
  }

  private def getConfig(ctx: NgTransformerRequestContext, config: NgIzanamiV1ProxyConfig)(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): Future[Either[Result, NgPluginHttpRequest]] = {
    env.Ws
      .url(s"${config.izanamiUrl}/api/tree/configs?pattern=${config.configPattern}")
      .withRequestTimeout(config.timeout)
      .withHttpHeaders(
        "Izanami-Client-Id"     -> config.clientId,
        "Izanami-Client-Secret" -> config.clientSecret
      )
      .withAuth(config.clientId, config.clientSecret, WSAuthScheme.BASIC)
      .get()
      .map { resp =>
        Results
          .Status(resp.status)(resp.json)
          .withHeaders(
            resp.headers
              .mapValues(_.last)
              .filterNot(v => v._1.toLowerCase == "content-type" || v._1.toLowerCase == "content-length")
              .toSeq: _*
          )
          .as(resp.header("Content-Type").getOrElse("application/json"))
          .left
      }
  }

  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val config = ctx.cachedConfig(internalName)(NgIzanamiV1ProxyConfig.format).getOrElse(NgIzanamiV1ProxyConfig())
    (ctx.request.method.toLowerCase, ctx.request.path) match {
      case ("get", path) if path == config.path + "/features" && config.featuresEnabled             => getFeatures(ctx, config)
      case ("post", path) if path == config.path + "/features" && config.featuresWithContextEnabled =>
        getFeaturesWithBody(ctx, config, ctx.otoroshiRequest.body)
      case ("get", path) if path == config.path + "/configs" && config.configurationEnabled         => getConfig(ctx, config)
      case _                                                                                        => ctx.otoroshiRequest.right.future
    }
  }
}

case class NgIzanamiV1CanaryConfig(
    experimentId: String = "foo:bar:qix",
    configId: String = "foo:bar:qix:config",
    izanamiUrl: String = "https://izanami.foo.bar",
    tls: NgTlsConfig = NgTlsConfig.default,
    clientId: String = "client",
    clientSecret: String = "secret",
    timeout: FiniteDuration = 5000.millis,
    routeConfig: Option[JsObject] = None
) extends NgPluginConfig {
  override def json: JsValue = Json.obj(
    "experiment_id" -> experimentId,
    "config_id"     -> configId,
    "izanami_url"   -> izanamiUrl,
    "tls"           -> tls.json,
    "client_id"     -> clientId,
    "client_secret" -> clientSecret,
    "timeout"       -> timeout.toMillis,
    "route_config"  -> routeConfig
  )
}

object NgIzanamiV1CanaryConfig {
  val format = new Format[NgIzanamiV1CanaryConfig] {
    override def writes(o: NgIzanamiV1CanaryConfig): JsValue             = o.json
    override def reads(json: JsValue): JsResult[NgIzanamiV1CanaryConfig] = Try {
      NgIzanamiV1CanaryConfig(
        experimentId = json.select("experimentId").asOpt[String].getOrElse("foo:bar:qix"),
        configId = json.select("configId").asOpt[String].getOrElse("foo:bar:qix:config"),
        izanamiUrl = json.select("izanamiUrl").asOpt[String].getOrElse("https://izanami.foo.bar"),
        tls =
          json.select("tls").asOpt[JsValue].flatMap(NgTlsConfig.format.reads(_).asOpt).getOrElse(NgTlsConfig.default),
        clientId = json.select("clientId").asOpt[String].getOrElse("client"),
        clientSecret = json.select("clientSecret").asOpt[String].getOrElse("secret"),
        timeout = json.select("timeout").asOpt[Long].getOrElse(5000L).millis,
        routeConfig = json.select("routeConfig").asOpt[JsObject]
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
  }
}

case class NgIzanamiV1CanaryRoutingConfigRoute(
    route: String,
    variants: Map[String, String],
    default: String,
    wildcard: Boolean,
    exact: Boolean,
    regex: Boolean
) {
  def json: JsValue = Json.obj(
    "route"    -> route,
    "variants" -> variants,
    "default"  -> default,
    "wildcard" -> wildcard,
    "exact"    -> exact,
    "regex"    -> regex
  )
}

object NgIzanamiV1CanaryRoutingConfigRoute {
  val format = new Format[NgIzanamiV1CanaryRoutingConfigRoute] {
    override def writes(o: NgIzanamiV1CanaryRoutingConfigRoute): JsValue             = o.json
    override def reads(json: JsValue): JsResult[NgIzanamiV1CanaryRoutingConfigRoute] = Try {
      NgIzanamiV1CanaryRoutingConfigRoute(
        route = json.select("route").asString,
        variants = json.select("variants").asOpt[Map[String, String]].getOrElse(Map.empty),
        default = json.select("default").asString,
        wildcard = json.select("wildcard").asOpt[Boolean].getOrElse(false),
        exact = json.select("exact").asOpt[Boolean].getOrElse(false),
        regex = json.select("regex").asOpt[Boolean].getOrElse(false)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
  }
}

case class NgIzanamiV1CanaryRoutingConfig(routes: Seq[NgIzanamiV1CanaryRoutingConfigRoute]) {
  def json: JsValue = Json.obj(
    "routes" -> JsArray(routes.map(_.json))
  )
}

object NgIzanamiV1CanaryRoutingConfig {
  val format = new Format[NgIzanamiV1CanaryRoutingConfig] {
    override def writes(o: NgIzanamiV1CanaryRoutingConfig): JsValue             = o.json
    override def reads(json: JsValue): JsResult[NgIzanamiV1CanaryRoutingConfig] = Try {
      NgIzanamiV1CanaryRoutingConfig(
        routes = json
          .select("routes")
          .asOpt[Seq[JsValue]]
          .map(_.map(NgIzanamiV1CanaryRoutingConfigRoute.format.reads).collect { case JsSuccess(e, _) => e })
          .getOrElse(Seq.empty)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
  }
}

class NgIzanamiV1Canary extends NgRequestTransformer {

  override def name: String                                = "Izanami V1 Canary Campaign"
  override def description: Option[String]                 =
    "This plugin allow you to perform canary testing based on an izanami experiment campaign (A/B test)".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgIzanamiV1CanaryConfig().some
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Integrations)
  override def steps: Seq[NgStep]                          = Seq(NgStep.TransformRequest, NgStep.TransformResponse)

  private val cookieJar = new LegitTrieMap[String, WSCookie]()

  private val cache: Cache[String, JsValue] = Scaffeine()
    .recordStats()
    .expireAfterWrite(10.minutes)
    .maximumSize(1000)
    .build()

  def canaryId(ctx: NgTransformerRequestContext)(implicit env: Env): String = {
    val attrs                         = ctx.attrs
    val reqNumber: Option[Int]        = attrs.get(otoroshi.plugins.Keys.RequestNumberKey)
    val maybeCanaryId: Option[String] = attrs.get(otoroshi.plugins.Keys.RequestCanaryIdKey)
    val canaryId: String              = maybeCanaryId.getOrElse(IdGenerator.uuid + "-" + reqNumber.get)
    canaryId
  }

  def canaryCookie(cid: String, ctx: NgTransformerRequestContext)(implicit env: Env): WSCookie = {
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
      f(key).andThen { case Success(v) =>
        cache.put(key, v)
      }
    }
  }

  def fetchIzanamiVariant(cid: String, config: NgIzanamiV1CanaryConfig, ctx: NgTransformerRequestContext)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[String] = {
    withCache(s"${config.izanamiUrl}/api/experiments/${config.experimentId}/displayed?clientId=$cid") { url =>
      env.MtlsWs
        .url(url, config.tls.legacy)
        .withRequestTimeout(config.timeout)
        .withHttpHeaders(
          "Content-Type"          -> "application/json",
          "Izanami-Client-Id"     -> config.clientId,
          "Izanami-Client-Secret" -> config.clientSecret
        )
        .withAuth(config.clientId, config.clientSecret, WSAuthScheme.BASIC)
        .post("")
        .map(_.json)
    }.map(r => r.asObject.select("variant").select("id").asOpt[String].getOrElse(IdGenerator.uuid))
  }

  def fetchIzanamiRoutingConfig(config: NgIzanamiV1CanaryConfig, ctx: NgTransformerRequestContext)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[NgIzanamiV1CanaryRoutingConfig] = {
    withCache(s"${config.izanamiUrl}/api/configs/${config.configId}") { url =>
      config.routeConfig match {
        case Some(c) => c.future
        case None    => {
          env.MtlsWs
            .url(url, config.tls.legacy)
            .withRequestTimeout(config.timeout)
            .withHttpHeaders(
              "Izanami-Client-Id"     -> config.clientId,
              "Izanami-Client-Secret" -> config.clientSecret
            )
            .withAuth(config.clientId, config.clientSecret, WSAuthScheme.BASIC)
            .get()
            .map(_.json)
        }
      }
    }.map { json =>
      val routing = json.asObject.select("value").as[JsObject]
      NgIzanamiV1CanaryRoutingConfig.format.reads(routing).get
    }
  }

  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val config = ctx.cachedConfig(internalName)(NgIzanamiV1CanaryConfig.format).getOrElse(NgIzanamiV1CanaryConfig())
    val cid    = canaryId(ctx)
    val cookie = canaryCookie(cid, ctx)
    for {
      routing <- fetchIzanamiRoutingConfig(config, ctx)
      variant <- fetchIzanamiVariant(cid, config, ctx)
    } yield {
      val uri               = ctx.otoroshiRequest.uri
      val path: Uri.Path    = uri.path
      val pathStr           = path.toString()
      val newPath: Uri.Path = routing.routes.find {
        case r if r.wildcard => RegexPool(r.route).matches(pathStr)
        case r if r.regex    => RegexPool.regex(r.route).matches(pathStr)
        case r if r.exact    => pathStr == r.route
        case r               => pathStr.startsWith(r.route)
      } match {
        case Some(route) if route.wildcard || route.regex || route.exact =>
          route.variants.get(variant) match {
            case Some(variantPath) => Uri.Path(variantPath)
            case None              => Uri.Path(route.default)
          }
        case Some(route)                                                 =>
          val strippedPath = pathStr.replaceFirst(route.route, "")
          route.variants.get(variant) match {
            case Some(variantPathBeginning) => Uri.Path(variantPathBeginning + strippedPath)
            case None                       => Uri.Path(route.default + strippedPath)
          }
        case None                                                        => path
      }
      val newUri            = uri.copy(path = newPath)
      val newUriStr         = newUri.toString()
      cookieJar.put(ctx.snowflake, cookie)
      Right(ctx.otoroshiRequest.copy(url = newUriStr))
    }
  }

  override def transformResponse(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    cookieJar.get(ctx.snowflake).map { cookie =>
      val allCookies = ctx.otoroshiResponse.cookies :+ cookie
      val cookies    = allCookies.distinct
      cookieJar.remove(ctx.snowflake)
      ctx.otoroshiResponse.copy(cookies = cookies).rightf
    } getOrElse {
      ctx.otoroshiResponse.rightf
    }
  }
}
