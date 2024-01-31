package otoroshi.next.plugins.api

import akka.Done
import akka.http.scaladsl.model.{StatusCodes, Uri, ws}
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Source}
import akka.util.ByteString
import com.github.blemale.scaffeine.{Cache, Scaffeine}
import otoroshi.auth.{AuthModule, BasicAuthModule, BasicAuthModuleConfig, SessionCookieValues}
import otoroshi.env.Env
import otoroshi.models.{ApiKey, BackOfficeUser, GlobalConfig, PrivateAppsUser, ServiceDescriptor}
import otoroshi.next.models.{NgMatchedRoute, NgPluginInstance, NgRoute, NgTarget}
import otoroshi.next.plugins.RejectStrategy
import otoroshi.next.proxy.{NgExecutionReport, NgProxyEngineError, NgReportPluginSequence, NgReportPluginSequenceItem}
import otoroshi.next.utils.{FEither, JsonHelpers}
import otoroshi.script.{InternalEventListener, NamedPlugin, PluginType, StartableAndStoppable}
import otoroshi.security.IdGenerator
import otoroshi.utils.TypedMap
import otoroshi.utils.http.WSCookieWithSameSite
import otoroshi.utils.syntax.implicits._
import play.api.http.HttpEntity
import play.api.http.websocket.{CloseMessage, Message, PingMessage, PongMessage}
import play.api.libs.json._
import play.api.libs.ws.{WSCookie, WSResponse}
import play.api.mvc.{AnyContent, Cookie, Request, RequestHeader, Result, Results}
import play.api.http.websocket.{CloseMessage, PingMessage, PongMessage, BinaryMessage => PlayWSBinaryMessage, Message => PlayWSMessage, TextMessage => PlayWSTextMessage}

import java.security.cert.X509Certificate
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.{Either, Failure, Success, Try}

object NgPluginHelper {
  def pluginId[A](implicit ct: ClassTag[A]): String = s"cp:${ct.runtimeClass.getName}"
}

object NgPluginHttpRequest {
  def fromRequest(req: RequestHeader): NgPluginHttpRequest = {
    NgPluginHttpRequest(
      url = req.uri,
      method = req.method,
      headers = req.headers.toSimpleMap,
      cookies = Seq.empty,
      version = req.version,
      clientCertificateChain = () => req.clientCertificateChain,
      body = Source.empty,
      backend = None
    )
  }
}

case class NgPluginHttpRequest(
    url: String,
    method: String,
    headers: Map[String, String],
    cookies: Seq[WSCookie] = Seq.empty[WSCookie],
    version: String,
    clientCertificateChain: () => Option[Seq[X509Certificate]],
    body: Source[ByteString, _],
    backend: Option[NgTarget]
) {
  lazy val contentType: Option[String]              = header("Content-Type")
  lazy val contentLengthStr: Option[String]         = header("Content-Length")
  lazy val transferEncoding: Option[String]         = header("Transfer-Encoding")
  lazy val host: String                             = header("Host").getOrElse("")
  lazy val uri: Uri                                 = Uri(url)
  lazy val scheme: String                           = uri.scheme
  lazy val authority: Uri.Authority                 = uri.authority
  lazy val fragment: Option[String]                 = uri.fragment
  lazy val path: String                             = uri.path.toString()
  lazy val queryString: Option[String]              = uri.rawQueryString
  lazy val relativeUri: String                      = uri.toRelative.toString()
  lazy val hasBodyWithoutLength: (Boolean, Boolean) =
    otoroshi.utils.body.BodyUtils.hasBodyWithoutLengthGen(
      method.toUpperCase(),
      contentLengthStr,
      contentType,
      transferEncoding
    )
  lazy val hasBody: Boolean                         = hasBodyWithoutLength._1
  // val ctype = contentType
  // (method.toUpperCase(), header("Content-Length")) match {
  //   case ("GET", Some(_))    => true
  //   case ("GET", None) if ctype.isDefined => true
  //   case ("GET", None)       => false
  //   case ("HEAD", Some(_))   => true
  //   case ("HEAD", None) if ctype.isDefined => true
  //   case ("HEAD", None)      => false
  //   case ("PATCH", _)        => true
  //   case ("POST", _)         => true
  //   case ("PUT", _)          => true
  //   case ("QUERY", _)        => true
  //   case ("DELETE", Some(_)) => true
  //   case ("DELETE", None) if ctype.isDefined => true
  //   case ("DELETE", None)    => false
  //   case _                   => true
  // }
  // }

  def queryParam(name: String): Option[String] = uri.query().get(name).orElse(uri.query().get(name.toLowerCase()))

  def header(name: String): Option[String] = headers.get(name).orElse(headers.get(name.toLowerCase()))

  def json: JsValue = {
    val certs: Option[Seq[X509Certificate]] = clientCertificateChain()
    Json.obj(
      "url"               -> url,
      "method"            -> method,
      "headers"           -> headers,
      "query"             -> uri.query().toMultiMap,
      "version"           -> version,
      "client_cert_chain" -> JsonHelpers.clientCertChainToJson(certs),
      "backend"           -> backend.map(_.json).getOrElse(JsNull).asValue,
      "cookies"           -> JsArray(
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
}

case class NgPluginHttpResponse(
    status: Int,
    headers: Map[String, String],
    cookies: Seq[WSCookie] = Seq.empty[WSCookie],
    body: Source[ByteString, _]
) {
  lazy val statusText: String               = StatusCodes.getForKey(status).map(_.reason()).getOrElse("NONE")
  lazy val transferEncoding: Option[String] = header("Transfer-Encoding")
  lazy val isChunked: Boolean               = transferEncoding.exists(h => h.toLowerCase().contains("chunked"))
  lazy val contentType: Option[String]      = header("Content-Type")
  lazy val contentLengthStr: Option[String] = header("Content-Length")
  lazy val contentLength: Option[Long]      = contentLengthStr.map(_.toLong)
  lazy val hasLength: Boolean               = contentLengthStr.isDefined
  def header(name: String): Option[String]  = headers.get(name).orElse(headers.get(name.toLowerCase()))
  def asResult: Result = {
    val ctype   = header("Content-Type")
    val clength = header("Content-Length").map(_.toLong)
    Results
      .Status(status)
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
      .applyOnWithOpt(ctype) { case (r, typ) =>
        r.as(typ)
      }
  }
  def json: JsValue                         =
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

sealed trait NgPluginVisibility {
  def name: String
  def json: JsValue = name.json
}
object NgPluginVisibility       {
  case object NgInternal extends NgPluginVisibility { def name: String = "internal" }
  case object NgUserLand extends NgPluginVisibility { def name: String = "userland" }
}

sealed trait NgPluginCategory {
  def name: String
  def json: JsValue = name.json
}

object NgPluginCategory {

  case object Custom           extends NgPluginCategory { def name: String = "Custom"           }
  case object Other            extends NgPluginCategory { def name: String = "Other"            }
  case object Security         extends NgPluginCategory { def name: String = "Security"         }
  case object Authentication   extends NgPluginCategory { def name: String = "Authentication"   }
  case object AccessControl    extends NgPluginCategory { def name: String = "AccessControl"    }
  case object Logging          extends NgPluginCategory { def name: String = "Logging"          }
  case object TrafficControl   extends NgPluginCategory { def name: String = "TrafficControl"   }
  case object Monitoring       extends NgPluginCategory { def name: String = "Monitoring"       }
  case object Transformations  extends NgPluginCategory { def name: String = "Transformations"  }
  case object Headers          extends NgPluginCategory { def name: String = "Headers"          }
  case object Experimental     extends NgPluginCategory { def name: String = "Experimental"     }
  case object Integrations     extends NgPluginCategory { def name: String = "Integrations"     }
  case object Tunnel           extends NgPluginCategory { def name: String = "Tunnel"           }
  case object Wasm             extends NgPluginCategory { def name: String = "Wasm"             }
  case object Classic          extends NgPluginCategory { def name: String = "Classic"          }
  case object ServiceDiscovery extends NgPluginCategory { def name: String = "ServiceDiscovery" }
  case object Websocket        extends NgPluginCategory { def name: String = "Websocket"        }

  val all = Seq(
    Classic,
    AccessControl,
    Authentication,
    Custom,
    Experimental,
    Headers,
    Integrations,
    Logging,
    Monitoring,
    Other,
    Security,
    ServiceDiscovery,
    TrafficControl,
    Transformations,
    Tunnel,
    Wasm,
    Websocket
  )
}

sealed trait NgStep {
  def name: String
  def json: JsValue = name.json
}
object NgStep       {
  case object Router            extends NgStep { def name: String = "Router"            }
  case object Sink              extends NgStep { def name: String = "Sink"              }
  case object PreRoute          extends NgStep { def name: String = "PreRoute"          }
  case object ValidateAccess    extends NgStep { def name: String = "ValidateAccess"    }
  case object TransformRequest  extends NgStep { def name: String = "TransformRequest"  }
  case object TransformResponse extends NgStep { def name: String = "TransformResponse" }
  case object MatchRoute        extends NgStep { def name: String = "MatchRoute"        }
  case object HandlesTunnel     extends NgStep { def name: String = "HandlesTunnel"     }
  case object HandlesRequest    extends NgStep { def name: String = "HandlesRequest"    }
  case object CallBackend       extends NgStep { def name: String = "CallBackend"       }
  case object Job               extends NgStep { def name: String = "Job"               }

  val all = Seq(
    Router,
    Sink,
    PreRoute,
    ValidateAccess,
    TransformRequest,
    TransformResponse,
    MatchRoute,
    HandlesTunnel,
    HandlesRequest,
    CallBackend
  )

  def apply(value: String): Option[NgStep] = value match {
    case "Router"            => Router.some
    case "Sink"              => Sink.some
    case "PreRoute"          => PreRoute.some
    case "ValidateAccess"    => ValidateAccess.some
    case "TransformRequest"  => TransformRequest.some
    case "TransformResponse" => TransformResponse.some
    case "MatchRoute"        => MatchRoute.some
    case "HandlesTunnel"     => HandlesTunnel.some
    case "HandlesRequest"    => HandlesRequest.some
    case "CallBackend"       => CallBackend.some
    case "Job"               => Job.some
    case _                   => None
  }
}

trait NgPluginConfig {
  def json: JsValue
}

trait NgNamedPlugin extends NamedPlugin { self =>
  def visibility: NgPluginVisibility
  def categories: Seq[NgPluginCategory]
  def tags: Seq[String]                              = Seq.empty
  def steps: Seq[NgStep]
  def multiInstance: Boolean = true
  def defaultConfigObject: Option[NgPluginConfig]
  override final def defaultConfig: Option[JsObject] =
    defaultConfigObject.map(_.json.asOpt[JsObject].getOrElse(Json.obj()))
  override def pluginType: PluginType                = PluginType.CompositeType
  override def configRoot: Option[String]            = None
  override def jsonDescription(): JsObject           =
    Try {
      Json.obj(
        "name"          -> name,
        "description"   -> description.map(JsString.apply).getOrElse(JsNull).as[JsValue],
        "defaultConfig" -> defaultConfig.getOrElse(JsNull).as[JsValue]
        // "configSchema"  -> configSchema.getOrElse(JsNull).as[JsValue],
        // "configFlow"    -> JsArray(configFlow.map(JsString.apply))
      )
    } match {
      case Failure(_) => Json.obj()
      case Success(s) => s
    }
}

object NgCachedConfigContext {
  private val cache: Cache[String, Any] = Scaffeine()
    .expireAfterWrite(5.seconds)
    .maximumSize(1000)
    .build()
}

trait NgCachedConfigContext {
  def idx: Int
  def route: NgRoute
  def config: JsValue
  def cachedConfig[A](plugin: String)(reads: Reads[A]): Option[A] = Try {
    val key = s"${route.cacheableId}::$plugin::$idx"
    NgCachedConfigContext.cache.getIfPresent(key) match {
      case None    =>
        reads.reads(config) match {
          case JsError(_)          => None
          case JsSuccess(value, _) =>
            NgCachedConfigContext.cache.put(key, value)
            Some(value)
        }
      case Some(v) => Some(v.asInstanceOf[A])
    }
  }.toOption.flatten

  def cachedConfigFn[A](plugin: String)(reads: JsValue => Option[A]): Option[A] = Try {
    val key = s"${route.cacheableId}::${plugin}::$idx"
    NgCachedConfigContext.cache.getIfPresent(key) match {
      case None    =>
        reads(config) match {
          case None            => None
          case s @ Some(value) =>
            NgCachedConfigContext.cache.put(key, value)
            s
        }
      case Some(v) => Some(v.asInstanceOf[A])
    }
  }.toOption.flatten
  def rawConfig[A](reads: Reads[A]): Option[A]                                  = reads.reads(config).asOpt
  def rawConfigFn[A](reads: JsValue => Option[A]): Option[A]                    = reads(config)
}

trait NgPlugin extends StartableAndStoppable with NgNamedPlugin with InternalEventListener

case class NgPreRoutingContext(
    snowflake: String,
    request: RequestHeader,
    route: NgRoute,
    config: JsValue,
    globalConfig: JsValue,
    attrs: TypedMap,
    report: NgExecutionReport,
    sequence: NgReportPluginSequence,
    markPluginItem: Function4[NgReportPluginSequenceItem, NgPreRoutingContext, Boolean, JsValue, Unit],
    idx: Int = 0
) extends NgCachedConfigContext {
  def wasmJson: JsValue = json.asObject ++ Json.obj("route" -> route.json)
  def json: JsValue     = Json.obj(
    "snowflake"     -> snowflake,
    // "route" -> route.json,
    "request"       -> JsonHelpers.requestToJson(request),
    "config"        -> config,
    "global_config" -> globalConfig,
    "attrs"         -> attrs.json
  )
}

sealed trait NgPluginWrapper[A <: NgNamedPlugin] {
  def instance: NgPluginInstance
  def plugin: A
}
object NgPluginWrapper                           {
  case class NgSimplePluginWrapper[A <: NgNamedPlugin](instance: NgPluginInstance, plugin: A) extends NgPluginWrapper[A]
  case class NgMergedRequestTransformerPluginWrapper(plugins: Seq[NgSimplePluginWrapper[NgRequestTransformer]])
      extends NgPluginWrapper[NgRequestTransformer] {
    private val reqTransformer                = new NgMergedRequestTransformer(plugins)
    private val inst                          = NgPluginInstance("transform-request-merged")
    override def instance: NgPluginInstance   = inst
    override def plugin: NgRequestTransformer = reqTransformer
  }
  case class NgMergedResponseTransformerPluginWrapper(plugins: Seq[NgSimplePluginWrapper[NgRequestTransformer]])
      extends NgPluginWrapper[NgRequestTransformer] {
    private val respTransformer               = new NgMergedResponseTransformer(plugins)
    private val inst                          = NgPluginInstance("transform-response-merged")
    override def instance: NgPluginInstance   = inst
    override def plugin: NgRequestTransformer = respTransformer
  }
  case class NgMergedPreRoutingPluginWrapper(plugins: Seq[NgSimplePluginWrapper[NgPreRouting]])
      extends NgPluginWrapper[NgPreRouting]         {
    private val preRouting                  = new NgMergedPreRouting(plugins)
    private val inst                        = NgPluginInstance("pre-routing-merged")
    override def instance: NgPluginInstance = inst
    override def plugin: NgPreRouting       = preRouting
  }
  case class NgMergedAccessValidatorPluginWrapper(plugins: Seq[NgSimplePluginWrapper[NgAccessValidator]])
      extends NgPluginWrapper[NgAccessValidator]    {
    private val accessValidator             = new NgMergedAccessValidator(plugins)
    private val inst                        = NgPluginInstance("access-validator-merged")
    override def instance: NgPluginInstance = inst
    override def plugin: NgAccessValidator  = accessValidator
  }
}

trait NgPreRoutingError {
  def result: Result
}
case class NgPreRoutingErrorRaw(
    body: ByteString,
    code: Int = 500,
    contentType: String,
    headers: Map[String, String] = Map.empty
)                                                      extends NgPreRoutingError {
  def result: Result = {
    Results.Status(code).apply(body).as(contentType).withHeaders(headers.toSeq: _*)
  }
}
case class NgPreRoutingErrorWithResult(result: Result) extends NgPreRoutingError

object NgPreRouting {
  val done: Either[NgPreRoutingError, Done]               = Right(Done)
  val futureDone: Future[Either[NgPreRoutingError, Done]] = done.vfuture
}

trait NgPreRouting extends NgPlugin {
  def isPreRouteAsync: Boolean                                                                                         = true
  def preRouteSync(ctx: NgPreRoutingContext)(implicit env: Env, ec: ExecutionContext): Either[NgPreRoutingError, Done] =
    NgPreRouting.done
  def preRoute(
      ctx: NgPreRoutingContext
  )(implicit env: Env, ec: ExecutionContext): Future[Either[NgPreRoutingError, Done]]                                  = preRouteSync(ctx).vfuture
}

case class NgRouterContext(
    request: RequestHeader,
    config: JsValue,
    attrs: TypedMap
) {
  def json: JsValue = Json.obj(
    "request" -> JsonHelpers.requestToJson(request),
    "config"  -> config,
    "attrs"   -> attrs.json
  )
}

trait NgRouter extends NgPlugin {
  def findRoute(ctx: NgRouterContext)(implicit env: Env, ec: ExecutionContext): Option[NgMatchedRoute] = None
}

case class NgBeforeRequestContext(
    snowflake: String,
    route: NgRoute,
    request: RequestHeader,
    config: JsValue,
    attrs: TypedMap,
    globalConfig: JsValue = Json.obj(),
    idx: Int = 0
) extends NgCachedConfigContext {
  def json: JsValue = Json.obj(
    "snowflake"     -> snowflake,
    // "route" -> route.json,
    "request"       -> JsonHelpers.requestToJson(request),
    "config"        -> config,
    "global_config" -> globalConfig,
    "attrs"         -> attrs.json
  )
}

case class NgAfterRequestContext(
    snowflake: String,
    route: NgRoute,
    request: RequestHeader,
    config: JsValue,
    attrs: TypedMap,
    globalConfig: JsValue = Json.obj(),
    idx: Int = 0
) extends NgCachedConfigContext {
  def json: JsValue = Json.obj(
    "snowflake"     -> snowflake,
    // "route" -> route.json,
    "request"       -> JsonHelpers.requestToJson(request),
    "config"        -> config,
    "global_config" -> globalConfig,
    "attrs"         -> attrs.json
  )
}

case class NgTransformerRequestContext(
    rawRequest: NgPluginHttpRequest,
    otoroshiRequest: NgPluginHttpRequest,
    snowflake: String,
    route: NgRoute,
    apikey: Option[ApiKey],
    user: Option[PrivateAppsUser],
    request: RequestHeader,
    config: JsValue,
    attrs: TypedMap,
    globalConfig: JsValue = Json.obj(),
    report: NgExecutionReport,
    sequence: NgReportPluginSequence,
    markPluginItem: Function4[NgReportPluginSequenceItem, NgTransformerRequestContext, Boolean, JsValue, Unit],
    idx: Int = 0
) extends NgCachedConfigContext {
  def json: JsValue = Json.obj(
    "snowflake"        -> snowflake,
    "raw_request"      -> rawRequest.json,
    "otoroshi_request" -> otoroshiRequest.json,
    "apikey"           -> apikey.map(_.lightJson).getOrElse(JsNull).as[JsValue],
    "user"             -> user.map(_.lightJson).getOrElse(JsNull).as[JsValue],
    // "route" -> route.json,
    "request"          -> JsonHelpers.requestToJson(request),
    "config"           -> config,
    "global_config"    -> globalConfig,
    "attrs"            -> attrs.json
  )

  def wasmJson(implicit env: Env, ec: ExecutionContext): Future[JsValue] = {
    implicit val mat = env.otoroshiMaterializer
    JsonHelpers.requestBody(otoroshiRequest).map { body =>
      json.asObject ++ Json.obj(
        "route"              -> route.json,
        "request_body_bytes" -> body
      )
    }
  }
}

case class NgTransformerResponseContext(
    response: Option[WSResponse],
    rawResponse: NgPluginHttpResponse,
    otoroshiResponse: NgPluginHttpResponse,
    snowflake: String,
    route: NgRoute,
    apikey: Option[ApiKey],
    user: Option[PrivateAppsUser],
    request: RequestHeader,
    config: JsValue,
    attrs: TypedMap,
    globalConfig: JsValue = Json.obj(),
    report: NgExecutionReport,
    sequence: NgReportPluginSequence,
    markPluginItem: Function4[NgReportPluginSequenceItem, NgTransformerResponseContext, Boolean, JsValue, Unit],
    idx: Int = 0
) extends NgCachedConfigContext {
  def json: JsValue = Json.obj(
    "snowflake"         -> snowflake,
    "raw_response"      -> rawResponse.json,
    "otoroshi_response" -> otoroshiResponse.json,
    "apikey"            -> apikey.map(_.lightJson).getOrElse(JsNull).as[JsValue],
    "user"              -> user.map(_.lightJson).getOrElse(JsNull).as[JsValue],
    // "route" -> route.json,
    "request"           -> JsonHelpers.requestToJson(request),
    "config"            -> config,
    "global_config"     -> globalConfig,
    "attrs"             -> attrs.json
  )

  def wasmJson(implicit env: Env, ec: ExecutionContext): Future[JsValue] = {
    implicit val mat = env.otoroshiMaterializer
    JsonHelpers.responseBody(otoroshiResponse).map { bodyOut =>
      json.asObject ++ Json.obj(
        "route"               -> route.json,
        "response_body_bytes" -> bodyOut
      )
    }
  }
}

case class NgTransformerErrorContext(
    snowflake: String,
    message: String,
    otoroshiResponse: NgPluginHttpResponse,
    request: RequestHeader,
    maybeCauseId: Option[String],
    callAttempts: Int,
    route: NgRoute,
    apikey: Option[ApiKey],
    user: Option[PrivateAppsUser],
    config: JsValue,
    globalConfig: JsValue = Json.obj(),
    attrs: TypedMap,
    report: NgExecutionReport,
    idx: Int = 0
) extends NgCachedConfigContext {
  def json: JsValue = Json.obj(
    "snowflake"         -> snowflake,
    "maybe_cause_id"    -> maybeCauseId.map(JsString.apply).getOrElse(JsNull).as[JsValue],
    "call_attempts"     -> callAttempts,
    "otoroshi_response" -> otoroshiResponse.json,
    // "otoroshi_result" -> Json.obj("status" -> otoroshiResult.header.status, "headers" -> otoroshiResult.header.headers),
    "apikey"            -> apikey.map(_.lightJson).getOrElse(JsNull).as[JsValue],
    "user"              -> user.map(_.lightJson).getOrElse(JsNull).as[JsValue],
    // "route" -> route.json,
    "request"           -> JsonHelpers.requestToJson(request),
    "config"            -> config,
    "global_config"     -> globalConfig,
    "attrs"             -> attrs.json
  )
}

trait NgFakePlugin        extends NgPlugin {}
trait NgFakePluginContext extends NgCachedConfigContext

trait NgRequestTransformer extends NgPlugin {

  def usesCallbacks: Boolean            = true
  def transformsRequest: Boolean        = true
  def transformsResponse: Boolean       = true
  def transformsError: Boolean          = true
  def isTransformRequestAsync: Boolean  = true
  def isTransformResponseAsync: Boolean = true

  def beforeRequest(
      ctx: NgBeforeRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = ().vfuture

  def afterRequest(
      ctx: NgAfterRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = ().vfuture

  def transformError(
      ctx: NgTransformerErrorContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[NgPluginHttpResponse] = {
    ctx.otoroshiResponse.vfuture
  }

  def transformRequestSync(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpRequest] = {
    Right(ctx.otoroshiRequest)
  }

  def transformRequest(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    transformRequestSync(ctx).vfuture
  }

  def transformResponseSync(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpResponse] = {
    Right(ctx.otoroshiResponse)
  }

  def transformResponse(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    transformResponseSync(ctx).vfuture
  }
}

case class NgAccessContext(
    snowflake: String,
    request: RequestHeader,
    route: NgRoute,
    user: Option[PrivateAppsUser],
    apikey: Option[ApiKey],
    config: JsValue,
    attrs: TypedMap,
    globalConfig: JsValue,
    report: NgExecutionReport,
    sequence: NgReportPluginSequence,
    markPluginItem: Function4[NgReportPluginSequenceItem, NgAccessContext, Boolean, JsValue, Unit],
    idx: Int = 0
) extends NgCachedConfigContext {
  def json: JsValue = Json.obj(
    "snowflake"     -> snowflake,
    "apikey"        -> apikey.map(_.lightJson).getOrElse(JsNull).as[JsValue],
    "user"          -> user.map(_.lightJson).getOrElse(JsNull).as[JsValue],
    // "route" -> route.json,
    "request"       -> JsonHelpers.requestToJson(request),
    "config"        -> config,
    "global_config" -> globalConfig,
    "attrs"         -> attrs.json
  )

  def wasmJson(implicit env: Env, ec: ExecutionContext): JsObject = {
    (json.asObject ++ Json.obj(
      "route" -> route.json
    ))
  }
}

sealed trait NgAccess
object NgAccess {
  case object NgAllowed               extends NgAccess
  case class NgDenied(result: Result) extends NgAccess
}

trait NgAccessValidator extends NgNamedPlugin {
  def isAccessAsync: Boolean                                                                  = true
  def accessSync(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): NgAccess     = NgAccess.NgAllowed
  def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = accessSync(ctx).vfuture
}

sealed trait NgRequestOrigin {
  def name: String
}
object NgRequestOrigin       {
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
  def wasmJson: JsValue = json
  def json: JsValue     = Json.obj(
    "snowflake" -> snowflake,
    "request"   -> JsonHelpers.requestToJson(request),
    "config"    -> config,
    "attrs"     -> attrs.json,
    "origin"    -> origin.name,
    "status"    -> status,
    "message"   -> message
  )
}

trait NgRequestSink extends NgNamedPlugin {
  def isSinkAsync: Boolean                                                                       = true
  def matches(ctx: NgRequestSinkContext)(implicit env: Env, ec: ExecutionContext): Boolean       = false
  def handleSync(ctx: NgRequestSinkContext)(implicit env: Env, ec: ExecutionContext): Result     =
    Results.NotImplemented(Json.obj("error" -> "not implemented yet"))
  def handle(ctx: NgRequestSinkContext)(implicit env: Env, ec: ExecutionContext): Future[Result] =
    handleSync(ctx).vfuture
}

case class NgRouteMatcherContext(
    snowflake: String,
    request: RequestHeader,
    route: NgRoute,
    config: JsValue,
    attrs: TypedMap,
    idx: Int = 0
) extends NgCachedConfigContext {
  def json: JsValue = Json.obj(
    "snowflake" -> snowflake,
    // "route" -> route.json,
    "request"   -> JsonHelpers.requestToJson(request),
    "config"    -> config,
    "attrs"     -> attrs.json
  )

  def wasmJson: JsValue = Json.obj(
    "snowflake" -> snowflake,
    "route"     -> route.json,
    "request"   -> JsonHelpers.requestToJson(request),
    "config"    -> config,
    "attrs"     -> attrs.json
  )
}

trait NgRouteMatcher extends NgNamedPlugin {
  def matches(ctx: NgRouteMatcherContext)(implicit env: Env): Boolean
}

case class NgTunnelHandlerContext(
    snowflake: String,
    request: RequestHeader,
    route: NgRoute,
    config: JsValue,
    attrs: TypedMap
) {
  def json: JsValue = Json.obj(
    "snowflake" -> snowflake,
    // "route" -> route.json,
    "request"   -> JsonHelpers.requestToJson(request),
    "config"    -> config,
    "attrs"     -> attrs.json
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

case class NgbBackendCallContext(
    snowflake: String,
    rawRequest: RequestHeader,
    request: NgPluginHttpRequest,
    route: NgRoute,
    backend: NgTarget,
    user: Option[PrivateAppsUser],
    apikey: Option[ApiKey],
    config: JsValue,
    globalConfig: JsValue,
    attrs: TypedMap,
    idx: Int = 0
) extends NgCachedConfigContext {
  def json: JsValue = Json.obj(
    "snowflake"     -> snowflake,
    // "route" -> route.json,
    "backend"       -> backend.json,
    "apikey"        -> apikey.map(_.lightJson).getOrElse(JsNull).as[JsValue],
    "user"          -> user.map(_.lightJson).getOrElse(JsNull).as[JsValue],
    "raw_request"   -> JsonHelpers.requestToJson(rawRequest),
    "config"        -> config,
    "global_config" -> globalConfig,
    "attrs"         -> attrs.json
  )

  def wasmJson(implicit env: Env, ec: ExecutionContext): Future[JsValue] = {
    implicit val mat = env.otoroshiMaterializer
    JsonHelpers.requestBody(request).map { body =>
      (json.asObject ++ Json.obj(
        "route"              -> route.json,
        "request_body_bytes" -> body,
        "request"            -> request.json
      ))
    }
  }
}

case class BackendCallResponse(response: NgPluginHttpResponse, rawResponse: Option[WSResponse]) {

  import otoroshi.utils.http.Implicits._

  def status: Int                          = rawResponse.map(_.status).getOrElse(response.status)
  def contentLengthStr: Option[String]     = rawResponse.flatMap(_.contentLengthStr).orElse(response.contentLengthStr)
  def contentLength: Option[Long]          = rawResponse.map(_.contentLength).getOrElse(response.contentLength)
  def headers: Map[String, Seq[String]]    = rawResponse.map(_.headers).getOrElse(response.headers.mapValues(v => Seq(v)))
  def header(name: String): Option[String] =
    rawResponse.map(_.header(name)).getOrElse(response.headers.getIgnoreCase(name))
  def isChunked(): Option[Boolean]         = rawResponse.map(_.isChunked()).getOrElse(response.isChunked.some)
}

trait NgBackendCall extends NgNamedPlugin {
  def useDelegates: Boolean
  def sourceBodyResponse(
      status: Int,
      headers: Map[String, String],
      body: Source[ByteString, _]
  ): Either[NgProxyEngineError, BackendCallResponse] = {
    val finalHeaders = headers.getIgnoreCase("Transfer-Encoding") match {
      case None => headers.getIgnoreCase("Content-Length") match {
        case None => headers ++ Map("Transfer-Encoding" -> s"chunked")
        case Some(_) => headers ++ Map("Transfer-Encoding" -> s"chunked") - "Content-Length" - "content-length"
      }
      case Some(_) => headers
    }
    BackendCallResponse(
      NgPluginHttpResponse(status, finalHeaders, Seq.empty, body),
      None
    ).right[NgProxyEngineError]
  }
  def inMemoryBodyResponse(
    status: Int,
    headers: Map[String, String],
    body: ByteString
  ): Either[NgProxyEngineError, BackendCallResponse] = {
    val finalHeaders = headers.getIgnoreCase("Transfer-Encoding") match {
      case None => headers.getIgnoreCase("Content-Length") match {
        case None => headers ++ Map("Content-Length" -> s"${body.length}")
        case Some(_) => headers
      }
      case Some(_) => headers
    }
    BackendCallResponse(
      NgPluginHttpResponse(status, finalHeaders, Seq.empty, body.chunks(16 * 1024)),
      None
    ).right[NgProxyEngineError]
  }

  def emptyBodyResponse(
    status: Int,
    headers: Map[String, String],
  ): Either[NgProxyEngineError, BackendCallResponse] = {
    val finalHeaders = headers ++ Map("Content-Length" -> s"0")
    BackendCallResponse(
      NgPluginHttpResponse(status, finalHeaders, Seq.empty, Source.empty),
      None
    ).right[NgProxyEngineError]
  }
  def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(
      implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    delegates()
  }
}

class NgMergedRequestTransformer(plugins: Seq[NgPluginWrapper.NgSimplePluginWrapper[NgRequestTransformer]])
    extends NgRequestTransformer {

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Other)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgInternal

  override def defaultConfigObject: Option[NgPluginConfig] = None
  override def multiInstance: Boolean                      = true
  override def transformsRequest: Boolean                  = true
  override def isTransformRequestAsync: Boolean            = true
  override def isTransformResponseAsync: Boolean           = true
  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    def next(
        _ctx: NgTransformerRequestContext,
        plugins: Seq[NgPluginWrapper[NgRequestTransformer]],
        pluginIndex: Int
    ): Future[Either[Result, NgPluginHttpRequest]] = {
      plugins.headOption match {
        case None          => Right(_ctx.otoroshiRequest).vfuture
        case Some(wrapper) => {
          val pluginConfig: JsValue = wrapper.plugin.defaultConfig
            .map(dc => dc ++ wrapper.instance.config.raw)
            .getOrElse(wrapper.instance.config.raw)
          val ctx                   = _ctx.copy(config = pluginConfig)
          val debug                 = ctx.route.debugFlow || wrapper.instance.debug
          val in: JsValue           = if (debug) Json.obj("ctx" -> ctx.json) else JsNull
          val item                  = NgReportPluginSequenceItem(
            wrapper.instance.plugin,
            wrapper.plugin.name,
            System.currentTimeMillis(),
            System.nanoTime(),
            -1L,
            -1L,
            in,
            JsNull
          )
          Try(wrapper.plugin.transformRequestSync(ctx.copy(idx = pluginIndex))) match {
            case Failure(exception)                            =>
              ctx.markPluginItem(
                item,
                ctx,
                debug,
                Json.obj("kind" -> "failure", "error" -> JsonHelpers.errToJson(exception))
              )
              ctx.report.setContext(ctx.sequence.stopSequence().json)
              Left(
                Results.InternalServerError(
                  Json.obj(
                    "error"             -> "internal_server_error",
                    "error_description" -> "an error happened during request-transformation plugins phase",
                    "error"             -> JsonHelpers.errToJson(exception)
                  )
                )
              ).vfuture
            case Success(Left(result))                         =>
              ctx.markPluginItem(
                item,
                ctx,
                debug,
                Json.obj(
                  "kind"    -> "short-circuit",
                  "status"  -> result.header.status,
                  "headers" -> result.header.headers
                )
              )
              ctx.report.setContext(ctx.sequence.stopSequence().json)
              Left(result).vfuture
            case Success(Right(req_next)) if plugins.size == 1 =>
              ctx.markPluginItem(item, ctx.copy(otoroshiRequest = req_next), debug, Json.obj("kind" -> "successful"))
              ctx.report.setContext(ctx.sequence.stopSequence().json)
              Right(req_next).vfuture
            case Success(Right(req_next))                      =>
              ctx.markPluginItem(item, ctx.copy(otoroshiRequest = req_next), debug, Json.obj("kind" -> "successful"))
              next(_ctx.copy(otoroshiRequest = req_next), plugins.tail, pluginIndex - 1)
          }
        }
      }
    }
    next(ctx, plugins, plugins.size)
  }
}

class NgMergedResponseTransformer(plugins: Seq[NgPluginWrapper.NgSimplePluginWrapper[NgRequestTransformer]])
    extends NgRequestTransformer {

  override def steps: Seq[NgStep]                          = Seq(NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Other)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgInternal
  override def defaultConfigObject: Option[NgPluginConfig] = None

  override def multiInstance: Boolean            = true
  override def transformsResponse: Boolean       = true
  override def isTransformRequestAsync: Boolean  = true
  override def isTransformResponseAsync: Boolean = true
  override def transformResponse(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    def next(
        _ctx: NgTransformerResponseContext,
        plugins: Seq[NgPluginWrapper[NgRequestTransformer]],
        pluginIndex: Int
    ): Future[Either[Result, NgPluginHttpResponse]] = {
      plugins.headOption match {
        case None          => Right(_ctx.otoroshiResponse).vfuture
        case Some(wrapper) => {
          val pluginConfig: JsValue = wrapper.plugin.defaultConfig
            .map(dc => dc ++ wrapper.instance.config.raw)
            .getOrElse(wrapper.instance.config.raw)
          val ctx                   = _ctx.copy(config = pluginConfig, idx = pluginIndex)
          val debug                 = ctx.route.debugFlow || wrapper.instance.debug
          val in: JsValue           = if (debug) Json.obj("ctx" -> ctx.json) else JsNull
          val item                  = NgReportPluginSequenceItem(
            wrapper.instance.plugin,
            wrapper.plugin.name,
            System.currentTimeMillis(),
            System.nanoTime(),
            -1L,
            -1L,
            in,
            JsNull
          )
          Try(wrapper.plugin.transformResponseSync(ctx)) match {
            case Failure(exception)                             =>
              ctx.markPluginItem(
                item,
                ctx,
                debug,
                Json.obj("kind" -> "failure", "error" -> JsonHelpers.errToJson(exception))
              )
              ctx.report.setContext(ctx.sequence.stopSequence().json)
              Left(
                Results.InternalServerError(
                  Json.obj(
                    "error"             -> "internal_server_error",
                    "error_description" -> "an error happened during response-transformation plugins phase",
                    "error"             -> JsonHelpers.errToJson(exception)
                  )
                )
              ).vfuture
            case Success(Left(result))                          =>
              ctx.markPluginItem(
                item,
                ctx,
                debug,
                Json.obj(
                  "kind"    -> "short-circuit",
                  "status"  -> result.header.status,
                  "headers" -> result.header.headers
                )
              )
              ctx.report.setContext(ctx.sequence.stopSequence().json)
              Left(result).vfuture
            case Success(Right(resp_next)) if plugins.size == 1 =>
              ctx.markPluginItem(item, ctx.copy(otoroshiResponse = resp_next), debug, Json.obj("kind" -> "successful"))
              ctx.report.setContext(ctx.sequence.stopSequence().json)
              Right(resp_next).vfuture
            case Success(Right(resp_next))                      =>
              ctx.markPluginItem(item, ctx.copy(otoroshiResponse = resp_next), debug, Json.obj("kind" -> "successful"))
              next(_ctx.copy(otoroshiResponse = resp_next), plugins.tail, pluginIndex - 1)
          }
        }
      }
    }
    next(ctx, plugins, plugins.size)
  }
}

class NgMergedPreRouting(plugins: Seq[NgPluginWrapper.NgSimplePluginWrapper[NgPreRouting]]) extends NgPreRouting {

  override def steps: Seq[NgStep]                          = Seq(NgStep.PreRoute)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Other)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgInternal
  override def defaultConfigObject: Option[NgPluginConfig] = None

  override def multiInstance: Boolean   = true
  override def isPreRouteAsync: Boolean = true
  override def preRoute(
      _ctx: NgPreRoutingContext
  )(implicit env: Env, ec: ExecutionContext): Future[Either[NgPreRoutingError, Done]] = {
    def next(plugins: Seq[NgPluginWrapper[NgPreRouting]], pluginIndex: Int): Future[Either[NgPreRoutingError, Done]] = {
      plugins.headOption match {
        case None          => Right(Done).vfuture
        case Some(wrapper) => {
          val pluginConfig: JsValue = wrapper.plugin.defaultConfig
            .map(dc => dc ++ wrapper.instance.config.raw)
            .getOrElse(wrapper.instance.config.raw)
          val ctx                   = _ctx.copy(config = pluginConfig, idx = pluginIndex)
          val debug                 = ctx.route.debugFlow || wrapper.instance.debug
          val in: JsValue           = if (debug) Json.obj("ctx" -> ctx.json) else JsNull
          val item                  = NgReportPluginSequenceItem(
            wrapper.instance.plugin,
            wrapper.plugin.name,
            System.currentTimeMillis(),
            System.nanoTime(),
            -1L,
            -1L,
            in,
            JsNull
          )
          Try(wrapper.plugin.preRouteSync(ctx)) match {
            case Failure(exception)                     =>
              ctx.markPluginItem(
                item,
                ctx,
                debug,
                Json.obj("kind" -> "failure", "error" -> JsonHelpers.errToJson(exception))
              )
              ctx.report.setContext(ctx.sequence.stopSequence().json)
              Left(
                NgPreRoutingErrorWithResult(
                  Results.InternalServerError(
                    Json.obj(
                      "error"             -> "internal_server_error",
                      "error_description" -> "an error happened during pre-routing plugins phase",
                      "error"             -> JsonHelpers.errToJson(exception)
                    )
                  )
                )
              ).vfuture
            case Success(Left(err))                     =>
              val result = err.result
              ctx.markPluginItem(
                item,
                ctx,
                debug,
                Json.obj(
                  "kind"    -> "short-circuit",
                  "status"  -> result.header.status,
                  "headers" -> result.header.headers
                )
              )
              ctx.report.setContext(ctx.sequence.stopSequence().json)
              Left(NgPreRoutingErrorWithResult(result)).vfuture
            case Success(Right(_)) if plugins.size == 1 =>
              ctx.markPluginItem(item, ctx, debug, Json.obj("kind" -> "successful"))
              ctx.report.setContext(ctx.sequence.stopSequence().json)
              Right(Done).vfuture
            case Success(Right(_))                      =>
              ctx.markPluginItem(item, ctx, debug, Json.obj("kind" -> "successful"))
              next(plugins.tail, pluginIndex - 1)
          }
        }
      }
    }
    next(plugins, plugins.size)
  }
}

class NgMergedAccessValidator(plugins: Seq[NgPluginWrapper.NgSimplePluginWrapper[NgAccessValidator]])
    extends NgAccessValidator {

  override def steps: Seq[NgStep]                          = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Other)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgInternal
  override def defaultConfigObject: Option[NgPluginConfig] = None

  override def multiInstance: Boolean = true
  override def isAccessAsync: Boolean = true
  override def access(_ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    def next(plugins: Seq[NgPluginWrapper[NgAccessValidator]], pluginIndex: Int): Future[NgAccess] = {
      plugins.headOption match {
        case None          => NgAccess.NgAllowed.vfuture
        case Some(wrapper) => {
          val pluginConfig: JsValue = wrapper.plugin.defaultConfig
            .map(dc => dc ++ wrapper.instance.config.raw)
            .getOrElse(wrapper.instance.config.raw)
          val ctx                   = _ctx.copy(config = pluginConfig, idx = pluginIndex)
          val debug                 = ctx.route.debugFlow || wrapper.instance.debug
          val in: JsValue           = if (debug) Json.obj("ctx" -> ctx.json) else JsNull
          val item                  = NgReportPluginSequenceItem(
            wrapper.instance.plugin,
            wrapper.plugin.name,
            System.currentTimeMillis(),
            System.nanoTime(),
            -1L,
            -1L,
            in,
            JsNull
          )
          Try(wrapper.plugin.accessSync(ctx)) match {
            case Failure(exception)                               =>
              ctx.markPluginItem(
                item,
                ctx,
                debug,
                Json.obj("kind" -> "failure", "error" -> JsonHelpers.errToJson(exception))
              )
              ctx.report.setContext(ctx.sequence.stopSequence().json)
              NgAccess
                .NgDenied(
                  Results.InternalServerError(
                    Json.obj(
                      "error"             -> "internal_server_error",
                      "error_description" -> "an error happened during pre-routing plugins phase",
                      "error"             -> JsonHelpers.errToJson(exception)
                    )
                  )
                )
                .vfuture
            case Success(NgAccess.NgDenied(result))               =>
              ctx.markPluginItem(item, ctx, debug, Json.obj("kind" -> "denied", "status" -> result.header.status))
              ctx.report.setContext(ctx.sequence.stopSequence().json)
              NgAccess.NgDenied(result).vfuture
            case Success(NgAccess.NgAllowed) if plugins.size == 1 =>
              ctx.markPluginItem(item, ctx, debug, Json.obj("kind" -> "allowed"))
              ctx.report.setContext(ctx.sequence.stopSequence().json)
              NgAccess.NgAllowed.vfuture
            case Success(NgAccess.NgAllowed)                      =>
              ctx.markPluginItem(item, ctx, debug, Json.obj("kind" -> "allowed"))
              next(plugins.tail, pluginIndex - 1)
          }
        }
      }
    }
    next(plugins, plugins.size)
  }
}

case class NgWebsocketPluginContext(
                            config: JsValue,
                            idx: Int = 0,
                            request: RequestHeader,
                            route: NgRoute,
                            attrs: TypedMap,
                          ) extends NgCachedConfigContext {
  def json: JsValue = Json.obj(
    "config"        -> config
  )

  def wasmJson(implicit env: Env, ec: ExecutionContext): JsObject = {
    (json.asObject ++ Json.obj(
      "route" -> route.json
    ))
  }
}

sealed trait WebsocketMessage[A] {
  def data: A
  def str()(implicit m: Materializer, ec: ExecutionContext): Future[String]
  def isBinary: Boolean
  def isText: Boolean = !isBinary
}

object WebsocketMessage {
  case class AkkaMessage(override val data: akka.http.scaladsl.model.ws.Message) extends WebsocketMessage[akka.http.scaladsl.model.ws.Message] {
      override def str()(implicit m: Materializer, ec: ExecutionContext): Future[String] = data match {
      case akka.http.scaladsl.model.ws.TextMessage.Strict(text) => text.future
      case akka.http.scaladsl.model.ws.TextMessage.Streamed(source) =>
        source.runFold("")((concat, str) => concat + str)
      case akka.http.scaladsl.model.ws.BinaryMessage.Strict(data) => data.utf8String.future
      case akka.http.scaladsl.model.ws.BinaryMessage.Streamed(source) =>
        source
          .runFold(ByteString.empty)((concat, str) => concat ++ str).map(_.utf8String)
      case _ => "".future
    }

    override def isBinary: Boolean = !data.isText
  }
  case class PlayMessage(override val data: play.api.http.websocket.Message) extends WebsocketMessage[play.api.http.websocket.Message] {
    override def str()(implicit m: Materializer, ec: ExecutionContext): Future[String] = (data match {
      case PlayWSTextMessage(data) => data
      case PlayWSBinaryMessage(data) => data.utf8String
      case CloseMessage(_, _) => ""
      case PingMessage(data) => data.utf8String
      case PongMessage(data) => data.utf8String
    }).future

    override def isBinary: Boolean = data.isInstanceOf[play.api.http.websocket.BinaryMessage]
  }
}


trait NgWebsocketPlugin extends NgNamedPlugin {
  def onRequestFlow: Boolean = true
  def onResponseFlow: Boolean = true

  def accessSync[A](ctx: NgWebsocketPluginContext, message: WebsocketMessage[A]): NgAccess = NgAccess.NgAllowed

  def access[A](ctx: NgWebsocketPluginContext, message: WebsocketMessage[A])
               (implicit env: Env, ec: ExecutionContext): Future[NgAccess] = accessSync(ctx, message).vfuture
}

trait NgWebsocketValidatorPlugin extends NgWebsocketPlugin {
  def rejectStrategy(ctx: NgWebsocketPluginContext): RejectStrategy
}