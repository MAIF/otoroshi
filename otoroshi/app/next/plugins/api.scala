package otoroshi.next.plugins.api

import akka.Done
import akka.http.scaladsl.model.Uri
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Source}
import akka.util.ByteString
import com.github.blemale.scaffeine.{Cache, Scaffeine}
import otoroshi.env.Env
import otoroshi.models.{ApiKey, PrivateAppsUser}
import otoroshi.next.models.{NgTarget, PluginInstance, Route}
import otoroshi.next.proxy.ExecutionReport
import otoroshi.next.utils.JsonHelpers
import otoroshi.script.{InternalEventListener, NamedPlugin, PluginType, StartableAndStoppable}
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.{BetterJsReadable, BetterSyntax}
import play.api.http.HttpEntity
import play.api.http.websocket.Message
import play.api.libs.json._
import play.api.libs.ws.{WSCookie, WSResponse}
import play.api.mvc.{RequestHeader, Result, Results}

import java.security.cert.X509Certificate
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}
import play.api.mvc.Cookie
import otoroshi.utils.http.WSCookieWithSameSite

object NgPluginHelper {
  def pluginId[A](implicit ct: ClassTag[A]): String = s"cp:${ct.runtimeClass.getName}"
}

case class NgPluginHttpRequest(
  url: String,
  method: String,
  headers: Map[String, String],
  cookies: Seq[WSCookie] = Seq.empty[WSCookie],
  version: String,
  clientCertificateChain: Option[Seq[X509Certificate]],
  body: Source[ByteString, _],
  backend: Option[NgTarget]
) {
  lazy val contentType: Option[String] = headers.get("Content-Type").orElse(headers.get("content-type"))
  lazy val host: String                = headers.get("Host").orElse(headers.get("host")).getOrElse("")
  lazy val uri: Uri                    = Uri(url)
  lazy val scheme: String              = uri.scheme
  lazy val authority: Uri.Authority    = uri.authority
  lazy val fragment: Option[String]    = uri.fragment
  lazy val path: String                = uri.path.toString()
  lazy val queryString: Option[String] = uri.rawQueryString
  lazy val relativeUri: String         = uri.toRelative.toString()
  def json: JsValue                    =
    Json.obj(
      "url"     -> url,
      "method"  -> method,
      "headers" -> headers,
      "version" -> version,
      "client_cert_chain" -> JsonHelpers.clientCertChainToJson(clientCertificateChain),
      "backend" -> backend.map(_.json).getOrElse(JsNull).asValue,
      "cookies" -> JsArray(
        cookies.map(c =>
          Json.obj(
            "name"     -> c.name,
            "value"    -> c.value,
            "domain"   -> c.domain.map(JsString.apply).getOrElse(JsNull).as[JsValue],
            "path"     -> c.path.map(JsString.apply).getOrElse(JsNull).as[JsValue],
            "maxAge"   -> c.maxAge.map(v => JsNumber(BigDecimal(v))).getOrElse(JsNull).as[JsValue],
            "secure"   -> c.secure,
            "httpOnly" -> c.httpOnly
          )
        )
      )
    )
}

case class NgPluginHttpResponse(
  status: Int,
  headers: Map[String, String],
  cookies: Seq[WSCookie] = Seq.empty[WSCookie],
  body: Source[ByteString, _]
) {
  def asResult: Result = {
    val ctype = headers.get("Content-Type").orElse(headers.get("content-type"))
    val clength = headers.get("Content-Length").orElse(headers.get("content-length")).map(_.toLong)
    Results.Status(status)
      .sendEntity(HttpEntity.Streamed(body, clength, ctype))
      .withHeaders(headers.toSeq: _*)
      .withCookies(cookies.map { c =>
        Cookie(
          name = c.name,
          value = c.value,
          maxAge = c.maxAge.map(_.toInt),
          path = c.path.getOrElse("/"),
          domain = c.domain,
          secure = c.secure,
          httpOnly = c.httpOnly,
          sameSite = c.asInstanceOf[WSCookieWithSameSite].sameSite // this one is risky ;)
        )  
      }: _*)
      .applyOnWithOpt(ctype) {
        case (r, typ) => r.as(typ)
      }
  }
  def json: JsValue =
    Json.obj(
      "status"  -> status,
      "headers" -> headers,
      "cookies" -> JsArray(
        cookies.map(c =>
          Json.obj(
            "name"     -> c.name,
            "value"    -> c.value,
            "domain"   -> c.domain.map(JsString.apply).getOrElse(JsNull).as[JsValue],
            "path"     -> c.path.map(JsString.apply).getOrElse(JsNull).as[JsValue],
            "maxAge"   -> c.maxAge.map(v => JsNumber(BigDecimal(v))).getOrElse(JsNull).as[JsValue],
            "secure"   -> c.secure,
            "httpOnly" -> c.httpOnly
          )
        )
      )
    )
}

trait NgNamedPlugin extends NamedPlugin { self =>
  override def pluginType: PluginType = PluginType.CompositeType
  override def configRoot: Option[String] = None
  override def configSchema: Option[JsObject] =
    defaultConfig match {
      case None         => None
      case Some(config) => {
        def genSchema(jsobj: JsObject, prefix: String): JsObject = {
          jsobj.value.toSeq
            .map {
              case (key, JsString(_))              =>
                Json.obj(prefix + key -> Json.obj("type" -> "string", "props" -> Json.obj("label" -> (prefix + key))))
              case (key, JsNumber(_))              =>
                Json.obj(prefix + key -> Json.obj("type" -> "number", "props" -> Json.obj("label" -> (prefix + key))))
              case (key, JsBoolean(_))             =>
                Json.obj(prefix + key -> Json.obj("type" -> "bool", "props" -> Json.obj("label" -> (prefix + key))))
              case (key, JsArray(values))          => {
                if (values.isEmpty) {
                  Json.obj(prefix + key -> Json.obj("type" -> "array", "props" -> Json.obj("label" -> (prefix + key))))
                } else {
                  values.head match {
                    case JsNumber(_) =>
                      Json.obj(
                        prefix + key -> Json.obj(
                          "type"  -> "array",
                          "props" -> Json.obj("label" -> (prefix + key), "inputType" -> "number")
                        )
                      )
                    case _           =>
                      Json.obj(
                        prefix + key -> Json.obj("type" -> "array", "props" -> Json.obj("label" -> (prefix + key)))
                      )
                  }
                }
              }
              case ("mtlsConfig", a @ JsObject(_)) => genSchema(a, prefix + "mtlsConfig.")
              case ("mtls", a @ JsObject(_))       => genSchema(a, prefix + "mtls.")
              case ("filter", a @ JsObject(_))     => genSchema(a, prefix + "filter.")
              case ("not", a @ JsObject(_))        => genSchema(a, prefix + "not.")
              case (key, JsObject(_))              =>
                Json.obj(prefix + key -> Json.obj("type" -> "object", "props" -> Json.obj("label" -> (prefix + key))))
              case (key, JsNull)                   => Json.obj()
            }
            .foldLeft(Json.obj())(_ ++ _)
        }
        Some(genSchema(config, ""))
      }
    }
  override def configFlow: Seq[String]        =
    defaultConfig match {
      case None         => Seq.empty
      case Some(config) => {
        def genFlow(jsobj: JsObject, prefix: String): Seq[String] = {
          jsobj.value.toSeq.flatMap {
            case ("mtlsConfig", a @ JsObject(_)) => genFlow(a, prefix + "mtlsConfig.")
            case ("mtls", a @ JsObject(_))       => genFlow(a, prefix + "mtls.")
            case ("filter", a @ JsObject(_))     => genFlow(a, prefix + "filter.")
            case ("not", a @ JsObject(_))        => genFlow(a, prefix + "not.")
            case (key, value)                    => Seq(prefix + key)
          }
        }
        genFlow(config, "")
      }
    }
  override def jsonDescription(): JsObject =
    Try {
      Json.obj(
        "name"          -> name,
        "description"   -> description.map(JsString.apply).getOrElse(JsNull).as[JsValue],
        "defaultConfig" -> defaultConfig.getOrElse(JsNull).as[JsValue],
        "configSchema"  -> configSchema.getOrElse(JsNull).as[JsValue],
        "configFlow"    -> JsArray(configFlow.map(JsString.apply))
      )
    } match {
      case Failure(ex) => Json.obj()
      case Success(s)  => s
    }
}

object CachedConfigContext {
  private val cache: Cache[String, Any] = Scaffeine()
    .expireAfterWrite(5.seconds)
    .maximumSize(1000)
    .build()
}

trait CachedConfigContext {
  def route: Route
  def config: JsValue
  def cachedConfig[A](plugin: String)(reads: Reads[A]): Option[A] = Try {
    val key = s"${route.id}::${plugin}"
    CachedConfigContext.cache.getIfPresent(key) match {
      case None => reads.reads(config) match {
        case JsError(_) => None
        case JsSuccess(value, _) =>
          CachedConfigContext.cache.put(key, value)
          Some(value)
      }
      case Some(v) => Some(v.asInstanceOf[A])
    }
  }.toOption.flatten

  def cachedConfigFn[A](plugin: String)(reads: JsValue => Option[A]): Option[A] =Try {
    val key = s"${route.id}::${plugin}"
    CachedConfigContext.cache.getIfPresent(key) match {
      case None => reads(config) match {
        case None => None
        case s @ Some(value) =>
          CachedConfigContext.cache.put(key, value)
          s
      }
      case Some(v) => Some(v.asInstanceOf[A])
    }
  }.toOption.flatten
  def rawConfig[A](reads: Reads[A]): Option[A] = reads.reads(config).asOpt
  def rawConfigFn[A](reads: JsValue => Option[A]): Option[A] = reads(config)
}

trait NgPlugin extends StartableAndStoppable with NgNamedPlugin with InternalEventListener

case class NgPreRoutingContext(
  snowflake: String,
  request: RequestHeader,
  route: Route,
  config: JsValue,
  globalConfig: JsValue,
  attrs: TypedMap,
  report: ExecutionReport,
) extends CachedConfigContext {
  def json: JsValue = Json.obj(
    "snowflake" -> snowflake,
    // "route" -> route.json,
    "route" -> "omitted_for_brevity",
    "request" -> JsonHelpers.requestToJson(request),
    "config" -> config,
    "global_config" -> globalConfig,
    "attrs" -> attrs.json
  )
}

case class PluginWrapper[A <: NgNamedPlugin](instance: PluginInstance, plugin: A)

trait NgPreRoutingError {
  def result: Result
}
case class NgPreRoutingErrorRaw(
  body: ByteString,
  code: Int = 500,
  contentType: String,
  headers: Map[String, String] = Map.empty
) extends NgPreRoutingError {
  def result: Result = {
    Results.Status(code).apply(body).as(contentType).withHeaders(headers.toSeq: _*)
  }
}
case class NgPreRoutingErrorWithResult(result: Result) extends NgPreRoutingError

object NgPreRouting {
  val futureDone: Future[Either[NgPreRoutingError, Done]] = Right(Done).vfuture
}

trait NgPreRouting extends NgPlugin {
  def preRoute(ctx: NgPreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Either[NgPreRoutingError, Done]] = NgPreRouting.futureDone
}

case class NgBeforeRequestContext(
  snowflake: String,
  route: Route,
  request: RequestHeader,
  config: JsValue,
  attrs: TypedMap,
  globalConfig: JsValue = Json.obj()
) extends CachedConfigContext {
  def json: JsValue = Json.obj(
    "snowflake" -> snowflake,
    // "route" -> route.json,
    "route" -> "omitted_for_brevity",
    "request" -> JsonHelpers.requestToJson(request),
    "config" -> config,
    "global_config" -> globalConfig,
    "attrs" -> attrs.json
  )
}

case class NgAfterRequestContext(
  snowflake: String,
  route: Route,
  request: RequestHeader,
  config: JsValue,
  attrs: TypedMap,
  globalConfig: JsValue = Json.obj()
) extends CachedConfigContext {
  def json: JsValue = Json.obj(
    "snowflake" -> snowflake,
    // "route" -> route.json,
    "route" -> "omitted_for_brevity",
    "request" -> JsonHelpers.requestToJson(request),
    "config" -> config,
    "global_config" -> globalConfig,
    "attrs" -> attrs.json
  )
}

case class NgTransformerRequestContext(
                                        rawRequest: NgPluginHttpRequest,
                                        otoroshiRequest: NgPluginHttpRequest,
                                        snowflake: String,
                                        route: Route,
                                        apikey: Option[ApiKey],
                                        user: Option[PrivateAppsUser],
                                        request: RequestHeader,
                                        config: JsValue,
                                        attrs: TypedMap,
                                        globalConfig: JsValue = Json.obj(),
                                        report: ExecutionReport
) extends CachedConfigContext {
  def json: JsValue = Json.obj(
    "snowflake" -> snowflake,
    "raw_request" -> rawRequest.json,
    "otoroshi_request" -> otoroshiRequest.json,
    "apikey" -> apikey.map(_.lightJson).getOrElse(JsNull).as[JsValue],
    "user" -> user.map(_.lightJson).getOrElse(JsNull).as[JsValue],
    // "route" -> route.json,
    "route" -> "omitted_for_brevity",
    "request" -> JsonHelpers.requestToJson(request),
    "config" -> config,
    "global_config" -> globalConfig,
    "attrs" -> attrs.json
  )
}

case class NgTransformerResponseContext(
                                         response: WSResponse,
                                         rawResponse: NgPluginHttpResponse,
                                         otoroshiResponse: NgPluginHttpResponse,
                                         snowflake: String,
                                         route: Route,
                                         apikey: Option[ApiKey],
                                         user: Option[PrivateAppsUser],
                                         request: RequestHeader,
                                         config: JsValue,
                                         attrs: TypedMap,
                                         globalConfig: JsValue = Json.obj(),
                                         report: ExecutionReport
) extends CachedConfigContext {
  def json: JsValue = Json.obj(
    "snowflake" -> snowflake,
    "raw_response" -> rawResponse.json,
    "otoroshi_response" -> otoroshiResponse.json,
    "apikey" -> apikey.map(_.lightJson).getOrElse(JsNull).as[JsValue],
    "user" -> user.map(_.lightJson).getOrElse(JsNull).as[JsValue],
    // "route" -> route.json,
    "route" -> "omitted_for_brevity",
    "request" -> JsonHelpers.requestToJson(request),
    "config" -> config,
    "global_config" -> globalConfig,
    "attrs" -> attrs.json
  )
}

case class NgTransformerErrorContext(
                                      snowflake: String,
                                      message: String,
                                      otoroshiResponse: NgPluginHttpResponse,
                                      request: RequestHeader,
                                      maybeCauseId: Option[String],
                                      callAttempts: Int,
                                      route: Route,
                                      apikey: Option[ApiKey],
                                      user: Option[PrivateAppsUser],
                                      config: JsValue,
                                      globalConfig: JsValue = Json.obj(),
                                      attrs: TypedMap,
                                      report: ExecutionReport
) extends CachedConfigContext {
  def json: JsValue = Json.obj(
    "snowflake" -> snowflake,
    "maybe_cause_id" -> maybeCauseId.map(JsString.apply).getOrElse(JsNull).as[JsValue],
    "call_attempts" -> callAttempts,
    "otoroshi_response" -> otoroshiResponse.json,
    // "otoroshi_result" -> Json.obj("status" -> otoroshiResult.header.status, "headers" -> otoroshiResult.header.headers),
    "apikey" -> apikey.map(_.lightJson).getOrElse(JsNull).as[JsValue],
    "user" -> user.map(_.lightJson).getOrElse(JsNull).as[JsValue],
    // "route" -> route.json,
    "route" -> "omitted_for_brevity",
    "request" -> JsonHelpers.requestToJson(request),
    "config" -> config,
    "global_config" -> globalConfig,
    "attrs" -> attrs.json
  )
}

trait NgRequestTransformer extends NgPlugin {

  def beforeRequest(ctx: NgBeforeRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = ().vfuture

  def afterRequest(ctx: NgAfterRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = ().vfuture

  def transformError(ctx: NgTransformerErrorContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[NgPluginHttpResponse] = {
    ctx.otoroshiResponse.vfuture
  }

  def transformRequest(ctx: NgTransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    Right(ctx.otoroshiRequest).vfuture
  }

  def transformResponse(ctx: NgTransformerResponseContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    Right(ctx.otoroshiResponse).vfuture
  }
}

case class NgAccessContext(
  snowflake: String,
  request: RequestHeader,
  route: Route,
  user: Option[PrivateAppsUser],
  apikey: Option[ApiKey],
  config: JsValue,
  attrs: TypedMap,
  globalConfig: JsValue,
  report: ExecutionReport,
) extends CachedConfigContext {
  def json: JsValue = Json.obj(
    "snowflake" -> snowflake,
    "apikey" -> apikey.map(_.lightJson).getOrElse(JsNull).as[JsValue],
    "user" -> user.map(_.lightJson).getOrElse(JsNull).as[JsValue],
    // "route" -> route.json,
    "route" -> "omitted_for_brevity",
    "request" -> JsonHelpers.requestToJson(request),
    "config" -> config,
    "global_config" -> globalConfig,
    "attrs" -> attrs.json
  )
}

sealed trait NgAccess
object NgAccess {
  case object NgAllowed extends NgAccess
  case class NgDenied(result: Result) extends NgAccess
}

trait NgAccessValidator extends NgNamedPlugin {
  def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = NgAccess.NgAllowed.vfuture
}

sealed trait NgRequestOrigin {
  def name: String
}
object NgRequestOrigin {
  case object NgErrorHandler extends NgRequestOrigin { def name: String = "NgErrorHandler" }
  case object NgReverseProxy extends NgRequestOrigin { def name: String = "NgReverseProxy" }
}

case class NgRequestSinkContext(
  snowflake: String,
  request: RequestHeader,
  config: JsValue,
  attrs: TypedMap,
  origin: NgRequestOrigin,
  status: Int,
  message: String,
  body: Source[ByteString, _]
) {
  def json: JsValue = Json.obj(
    "snowflake" -> snowflake,
    "request" -> JsonHelpers.requestToJson(request),
    "config" -> config,
    "attrs" -> attrs.json,
    "origin" -> origin.name,
    "status" -> status,
    "message" -> message
  )
}

trait NgRequestSink extends NgNamedPlugin {
  def matches(ctx: NgRequestSinkContext)(implicit env: Env, ec: ExecutionContext): Boolean       = false
  def handle(ctx: NgRequestSinkContext)(implicit env: Env, ec: ExecutionContext): Future[Result] =
    Results.NotImplemented(Json.obj("error" -> "not implemented yet")).vfuture
}

case class NgRouteMatcherContext(
  snowflake: String,
  request: RequestHeader,
  route: Route,
  config: JsValue,
  attrs: TypedMap,
) {
  def json: JsValue = Json.obj(
    "snowflake" -> snowflake,
    // "route" -> route.json,
    "route" -> "omitted_for_brevity",
    "request" -> JsonHelpers.requestToJson(request),
    "config" -> config,
    "attrs" -> attrs.json
  )
}

trait NgRouteMatcher extends NgNamedPlugin {
  def matches(ctx: NgRouteMatcherContext)(implicit env: Env): Boolean
}

case class NgTunnelHandlerContext(
  snowflake: String,
  request: RequestHeader,
  route: Route,
  config: JsValue,
  attrs: TypedMap,
) {
  def json: JsValue = Json.obj(
    "snowflake" -> snowflake,
    // "route" -> route.json,
    "route" -> "omitted_for_brevity",
    "request" -> JsonHelpers.requestToJson(request),
    "config" -> config,
    "attrs" -> attrs.json
  )
}

trait NgTunnelHandler extends NgNamedPlugin with NgAccessValidator {
  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val isWebsocket = ctx.request.headers.get("Sec-WebSocket-Version").isDefined
    if (isWebsocket) {
      NgAccess.NgAllowed.vfuture
    } else {
      NgAccess.NgDenied(Results.NotFound(Json.obj("error" -> "not_found"))).vfuture
    }
  }
  def handle(ctx: NgTunnelHandlerContext)(implicit env: Env, ec: ExecutionContext): Flow[Message, Message, _]
}
