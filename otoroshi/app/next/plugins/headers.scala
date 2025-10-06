package otoroshi.next.plugins

import akka.http.scaladsl.model.Uri
import akka.stream.Materializer
import org.joda.time.DateTime
import otoroshi.el.{GlobalExpressionLanguage, HeadersExpressionLanguage, TargetExpressionLanguage}
import otoroshi.env.Env
import otoroshi.events.AlertEvent
import otoroshi.gateway.Errors
import otoroshi.models.{ApiKey, RemainingQuotas}
import otoroshi.next.models.NgRoute
import otoroshi.next.plugins.api._
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.{Result, Results}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class NgHeaderNamesConfig(names: Seq[String] = Seq.empty) extends NgPluginConfig {
  def json: JsValue = NgHeaderNamesConfig.format.writes(this)
}

object NgHeaderNamesConfig {
  val format = new Format[NgHeaderNamesConfig] {
    override def reads(json: JsValue): JsResult[NgHeaderNamesConfig] = Try {
      NgHeaderNamesConfig(
        names = json
          .select("header_names")
          .asOpt[Seq[String]]
          .filter(_.nonEmpty)
          .orElse(json.select("headers").asOpt[Seq[String]].filter(_.nonEmpty))
          .orElse(json.select("names").asOpt[Seq[String]].filter(_.nonEmpty))
          .getOrElse(Seq.empty)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: NgHeaderNamesConfig): JsValue             = Json.obj("header_names" -> o.names)
  }
}

case class NgHeaderValuesConfig(headers: Map[String, String] = Map.empty) extends NgPluginConfig {
  def json: JsValue = NgHeaderValuesConfig.format.writes(this)
}

object NgHeaderValuesConfig {
  val format = new Format[NgHeaderValuesConfig] {
    override def reads(json: JsValue): JsResult[NgHeaderValuesConfig] = Try {
      NgHeaderValuesConfig(
        headers = json.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: NgHeaderValuesConfig): JsValue             = Json.obj("headers" -> o.headers)
  }
}

class OverrideHost extends NgRequestTransformer {

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Headers, NgPluginCategory.Classic)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = true
  override def transformsResponse: Boolean                 = false
  override def transformsError: Boolean                    = false
  override def isTransformRequestAsync: Boolean            = false
  override def isTransformResponseAsync: Boolean           = true
  override def name: String                                = "Override host header"
  override def description: Option[String]                 =
    "This plugin override the current Host header with the Host of the backend target".some
  override def defaultConfigObject: Option[NgPluginConfig] = None

  override def transformRequestSync(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpRequest] = {
    ctx.attrs.get(Keys.BackendKey) match {
      case None          => Right(ctx.otoroshiRequest)
      case Some(backend) =>
        val host = TargetExpressionLanguage(
          backend.hostname,
          Some(ctx.request),
          ctx.route.serviceDescriptor.some,
          ctx.route.some,
          ctx.attrs.get(otoroshi.plugins.Keys.ApiKeyKey),
          ctx.attrs.get(otoroshi.plugins.Keys.UserKey),
          ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).get,
          ctx.attrs,
          env
        )

        val headers = ctx.otoroshiRequest.headers.-("Host").-("host").+("Host" -> host)
        val request = ctx.otoroshiRequest.copy(headers = headers)
        Right(request)
    }
  }
}

class OverrideLocationHeader extends NgRequestTransformer {

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Headers)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = false
  override def transformsResponse: Boolean                 = false
  override def transformsError: Boolean                    = false
  override def isTransformRequestAsync: Boolean            = false
  override def isTransformResponseAsync: Boolean           = true
  override def name: String                                = "Override Location header"
  override def description: Option[String]                 =
    "This plugin override the current Location header with the Host of the backend target".some
  override def defaultConfigObject: Option[NgPluginConfig] = None
  override def noJsForm: Boolean                           = true

  override def transformResponseSync(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpResponse] = {
    ctx.attrs.get(Keys.BackendKey) match {
      case None          => ctx.otoroshiResponse.right
      case Some(backend) => {
        val status = ctx.otoroshiResponse.status
        if ((status > 299 && status < 400) || status == 201) {
          ctx.otoroshiResponse.header("Location") match {
            case None                                                                                   => ctx.otoroshiResponse.right
            case Some(location) if !(location.startsWith("http://") || location.startsWith("https://")) =>
              ctx.otoroshiResponse.right
            case Some(location)                                                                         => {
              val backendHost     = TargetExpressionLanguage(
                backend.hostname,
                Some(ctx.request),
                ctx.route.serviceDescriptor.some,
                ctx.route.some,
                ctx.attrs.get(otoroshi.plugins.Keys.ApiKeyKey),
                ctx.attrs.get(otoroshi.plugins.Keys.UserKey),
                ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).get,
                ctx.attrs,
                env
              )
              val oldLocation     = Uri(location)
              val oldLocationHost = oldLocation.authority.host.toString()
              if (oldLocationHost.equalsIgnoreCase(backendHost)) {
                val frontendHost =
                  Option(ctx.request.domain)
                    .filterNot(_.isBlank)
                    .getOrElse(ctx.route.frontend.domains.head.domainLowerCase)
                val newLocation  =
                  oldLocation.copy(authority = oldLocation.authority.copy(host = Uri.Host(frontendHost))).toString()
                val headers      = ctx.otoroshiResponse.headers.-("Location").-("location").+("Location" -> newLocation)
                ctx.otoroshiResponse.copy(headers = headers).right
              } else {
                ctx.otoroshiResponse.right
              }
            }
          }
        } else {
          ctx.otoroshiResponse.right
        }
      }
    }
  }
}

class HeadersValidation extends NgAccessValidator {

  private val configReads: Reads[NgHeaderValuesConfig] = NgHeaderValuesConfig.format

  override def steps: Seq[NgStep]                = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Headers, NgPluginCategory.Classic)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Headers validation"
  override def description: Option[String]                 = "This plugin validates the values of incoming request headers".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgHeaderValuesConfig().some
  override def isAccessAsync: Boolean                      = true

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val validationHeaders =
      ctx.cachedConfig(internalName)(configReads).getOrElse(NgHeaderValuesConfig()).headers.map { case (key, value) =>
        (
          key.toLowerCase,
          GlobalExpressionLanguage.apply(
            value,
            Some(ctx.request),
            ctx.route.legacy.some,
            ctx.route.some,
            ctx.apikey,
            ctx.user,
            ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty),
            ctx.attrs,
            env
          )
        )
      }
    val headers           = ctx.request.headers.toSimpleMap.map { case (key, value) =>
      (key.toLowerCase, value)
    }
    if (
      validationHeaders.forall { case (key, value) =>
        headers.get(key).contains(value)
      }
    ) {
      NgAccess.NgAllowed.vfuture
    } else {
      Errors
        .craftResponseResult(
          "bad request",
          Results.BadRequest,
          ctx.request,
          None,
          None,
          attrs = ctx.attrs,
          maybeRoute = ctx.route.some
        )
        .map(NgAccess.NgDenied.apply)
    }
  }
}

class OtoroshiHeadersIn extends NgRequestTransformer {

  override def steps: Seq[NgStep]                          = Seq(NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Headers, NgPluginCategory.Classic)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = true
  override def transformsResponse: Boolean                 = false
  override def transformsError: Boolean                    = false
  override def isTransformRequestAsync: Boolean            = false
  override def isTransformResponseAsync: Boolean           = true
  override def name: String                                = "Otoroshi headers in"
  override def description: Option[String]                 = "This plugin adds Otoroshi specific headers to the request".some
  override def defaultConfigObject: Option[NgPluginConfig] = None

  override def transformRequestSync(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpRequest] = {
    val additionalHeaders = Map(
      env.Headers.OtoroshiProxiedHost      -> ctx.request.theHost,
      env.Headers.OtoroshiRequestId        -> ctx.attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).get,
      env.Headers.OtoroshiRequestTimestamp -> ctx.attrs
        .get(otoroshi.plugins.Keys.RequestTimestampKey)
        .get
        .toString("yyyy-MM-dd'T'HH:mm:ss.SSSZZ")
    )
    val context           = ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty)
    val newHeaders        = ctx.otoroshiRequest.headers
      .removeAllArgs(
        env.Headers.OtoroshiProxiedHost,
        env.Headers.OtoroshiRequestId,
        env.Headers.OtoroshiRequestTimestamp,
        env.Headers.OtoroshiGatewayParentRequest
      )
      .appendAll(additionalHeaders)
      .mapValues(v =>
        otoroshi.el.GlobalExpressionLanguage(
          value = v,
          req = ctx.request.some,
          service = ctx.route.legacy.some,
          route = ctx.route.some,
          apiKey = ctx.apikey,
          user = ctx.user,
          context = context,
          attrs = ctx.attrs,
          env = env
        )
      )
    Right(ctx.otoroshiRequest.copy(headers = newHeaders))
  }
}

class AdditionalHeadersOut extends NgRequestTransformer {

  private val configReads: Reads[NgHeaderValuesConfig] = NgHeaderValuesConfig.format

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Headers, NgPluginCategory.Classic)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = false
  override def transformsResponse: Boolean                 = true
  override def transformsError: Boolean                    = false
  override def isTransformRequestAsync: Boolean            = false
  override def isTransformResponseAsync: Boolean           = false
  override def name: String                                = "Additional headers out"
  override def description: Option[String]                 = "This plugin adds headers in the otoroshi response".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgHeaderValuesConfig().some

  override def transformResponseSync(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpResponse] = {
    val config = ctx.cachedConfig(internalName)(configReads).getOrElse(NgHeaderValuesConfig())
    val additionalHeaders = {
      config.headers.mapValues { value =>
        HeadersExpressionLanguage(
          value,
          ctx.request.some,
          ctx.route.serviceDescriptor.some,
          ctx.route.some,
          ctx.apikey,
          ctx.user,
          ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty),
          ctx.attrs,
          env
        )
      }
    }
    Right(ctx.otoroshiResponse.copy(headers = ctx.otoroshiResponse.headers ++ additionalHeaders))
  }
}

class AdditionalHeadersIn extends NgRequestTransformer {

  private val configReads: Reads[NgHeaderValuesConfig] = NgHeaderValuesConfig.format

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Headers, NgPluginCategory.Classic)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = true
  override def transformsResponse: Boolean                 = false
  override def transformsError: Boolean                    = false
  override def isTransformRequestAsync: Boolean            = false
  override def isTransformResponseAsync: Boolean           = true
  override def name: String                                = "Additional headers in"
  override def description: Option[String]                 = "This plugin adds headers in the incoming otoroshi request".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgHeaderValuesConfig().some

  override def transformRequestSync(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpRequest] = {
    val additionalHeaders =
      ctx.cachedConfig(internalName)(configReads).getOrElse(NgHeaderValuesConfig()).headers.mapValues { value =>
        HeadersExpressionLanguage(
          value,
          ctx.request.some,
          ctx.route.serviceDescriptor.some,
          ctx.route.some,
          ctx.apikey,
          ctx.user,
          ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty),
          ctx.attrs,
          env
        )
      }
    Right(ctx.otoroshiRequest.copy(headers = ctx.otoroshiRequest.headers ++ additionalHeaders))
  }
}

class MissingHeadersIn extends NgRequestTransformer {

  private val configReads: Reads[NgHeaderValuesConfig] = NgHeaderValuesConfig.format

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Headers, NgPluginCategory.Classic)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = true
  override def transformsResponse: Boolean                 = false
  override def transformsError: Boolean                    = false
  override def isTransformRequestAsync: Boolean            = false
  override def isTransformResponseAsync: Boolean           = true
  override def name: String                                = "Missing headers in"
  override def description: Option[String]                 =
    "This plugin adds headers (if missing) in the incoming otoroshi request".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgHeaderValuesConfig().some

  override def transformRequestSync(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpRequest] = {
    val additionalHeaders = ctx
      .cachedConfig(internalName)(configReads)
      .getOrElse(NgHeaderValuesConfig())
      .headers
      .filter { case (key, _) =>
        !ctx.otoroshiRequest.headers.contains(key) && !ctx.otoroshiRequest.headers.contains(key.toLowerCase)
      }
      .mapValues { value =>
        HeadersExpressionLanguage(
          value,
          ctx.request.some,
          ctx.route.serviceDescriptor.some,
          ctx.route.some,
          ctx.apikey,
          ctx.user,
          ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty),
          ctx.attrs,
          env
        )
      }
    Right(ctx.otoroshiRequest.copy(headers = ctx.otoroshiRequest.headers ++ additionalHeaders))
  }
}

class MissingHeadersOut extends NgRequestTransformer {

  private val configReads: Reads[NgHeaderValuesConfig] = NgHeaderValuesConfig.format

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Headers, NgPluginCategory.Classic)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = false
  override def transformsResponse: Boolean                 = true
  override def transformsError: Boolean                    = false
  override def isTransformRequestAsync: Boolean            = true
  override def isTransformResponseAsync: Boolean           = false
  override def name: String                                = "Missing headers out"
  override def description: Option[String]                 = "This plugin adds headers (if missing) in the otoroshi response".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgHeaderValuesConfig().some

  override def transformResponseSync(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpResponse] = {
    val additionalHeaders = ctx
      .cachedConfig(internalName)(configReads)
      .getOrElse(NgHeaderValuesConfig())
      .headers
      .filter { case (key, _) =>
        !ctx.otoroshiResponse.headers.contains(key) && !ctx.otoroshiResponse.headers.contains(key.toLowerCase)
      }
      .mapValues { value =>
        HeadersExpressionLanguage(
          value,
          ctx.request.some,
          ctx.route.serviceDescriptor.some,
          ctx.route.some,
          ctx.apikey,
          ctx.user,
          ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty),
          ctx.attrs,
          env
        )
      }
    Right(ctx.otoroshiResponse.copy(headers = ctx.otoroshiResponse.headers ++ additionalHeaders))
  }
}

class RemoveHeadersOut extends NgRequestTransformer {

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Headers, NgPluginCategory.Classic)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  private val configReads: Reads[NgHeaderNamesConfig] = NgHeaderNamesConfig.format

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = false
  override def transformsResponse: Boolean                 = true
  override def transformsError: Boolean                    = false
  override def isTransformRequestAsync: Boolean            = true
  override def isTransformResponseAsync: Boolean           = false
  override def name: String                                = "Remove headers out"
  override def description: Option[String]                 = "This plugin removes headers in the otoroshi response".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgHeaderNamesConfig().some

  override def transformResponseSync(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpResponse] = {
    val headers = ctx.cachedConfig(internalName)(configReads).getOrElse(NgHeaderNamesConfig()).names.map(_.toLowerCase)
    Right(ctx.otoroshiResponse.copy(headers = ctx.otoroshiResponse.headers.filterNot { case (key, _) =>
      headers.contains(key.toLowerCase)
    }))
  }
}

class RemoveHeadersIn extends NgRequestTransformer {

  private val configReads: Reads[NgHeaderNamesConfig] = NgHeaderNamesConfig.format

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Headers, NgPluginCategory.Classic)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = true
  override def transformsResponse: Boolean                 = false
  override def transformsError: Boolean                    = false
  override def isTransformRequestAsync: Boolean            = false
  override def isTransformResponseAsync: Boolean           = true
  override def name: String                                = "Remove headers in"
  override def description: Option[String]                 = "This plugin removes headers in the incoming otoroshi request".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgHeaderNamesConfig().some

  override def transformRequestSync(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpRequest] = {
    val headers = ctx.cachedConfig(internalName)(configReads).getOrElse(NgHeaderNamesConfig()).names.map(_.toLowerCase)
    Right(ctx.otoroshiRequest.copy(headers = ctx.otoroshiRequest.headers.filterNot { case (key, _) =>
      headers.contains(key.toLowerCase)
    }))
  }
}

class SendOtoroshiHeadersBack extends NgRequestTransformer {

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Headers, NgPluginCategory.Classic)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = false
  override def transformsResponse: Boolean                 = true
  override def transformsError: Boolean                    = false
  override def isTransformRequestAsync: Boolean            = true
  override def isTransformResponseAsync: Boolean           = false
  override def name: String                                = "Send otoroshi headers back"
  override def description: Option[String]                 =
    "This plugin adds response header containing useful informations about the current call".some
  override def defaultConfigObject: Option[NgPluginConfig] = None

  override def transformResponseSync(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpResponse] = {
    val headers          = ctx.otoroshiResponse.headers.toSeq
    val snowflake        = ctx.attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).get
    val requestTimestamp =
      ctx.attrs.get(otoroshi.plugins.Keys.RequestTimestampKey).get.toString("yyyy-MM-dd'T'HH:mm:ss.SSSZZ")
    val remainingQuotas  = ctx.attrs.get(otoroshi.plugins.Keys.ApiKeyRemainingQuotasKey).getOrElse(RemainingQuotas())
    val upstreamLatency  = ctx.report.getStep("call-backend").map(_.duration).getOrElse(-1L).toString
    val overhead         = ctx.report.overheadIn.toString
    val newHeaders       = headers
      .removeAllArgs(
        env.Headers.OtoroshiRequestId,
        env.Headers.OtoroshiRequestTimestamp,
        env.Headers.OtoroshiProxyLatency,
        env.Headers.OtoroshiUpstreamLatency,
        env.Headers.OtoroshiDailyCallsRemaining,
        env.Headers.OtoroshiMonthlyCallsRemaining,
        "Otoroshi-ApiKey-Rotation-At",
        "Otoroshi-ApiKey-Rotation-Remaining"
      )
      .appendAllArgs(
        env.Headers.OtoroshiRequestId        -> snowflake,
        env.Headers.OtoroshiRequestTimestamp -> requestTimestamp,
        env.Headers.OtoroshiProxyLatency     -> s"$overhead",
        env.Headers.OtoroshiUpstreamLatency  -> s"$upstreamLatency"
      )
      .appendAllArgsIf(ctx.apikey.isDefined)(
        env.Headers.OtoroshiDailyCallsRemaining   -> remainingQuotas.remainingCallsPerDay.toString,
        env.Headers.OtoroshiMonthlyCallsRemaining -> remainingQuotas.remainingCallsPerMonth.toString
      )
      .lazyAppendAllArgsIf(
        ctx.apikey.isDefined && ctx.apikey.get.rotation.enabled && ctx.attrs
          .get(otoroshi.plugins.Keys.ApiKeyRotationKey)
          .isDefined
      )(
        Seq(
          "Otoroshi-ApiKey-Rotation-At"        -> ctx.attrs
            .get(otoroshi.plugins.Keys.ApiKeyRotationKey)
            .get
            .rotationAt
            .toString(),
          "Otoroshi-ApiKey-Rotation-Remaining" -> ctx.attrs
            .get(otoroshi.plugins.Keys.ApiKeyRotationKey)
            .get
            .remaining
            .toString
        )
      )
    Right(ctx.otoroshiResponse.copy(headers = newHeaders.toMap))
  }
}

class XForwardedHeaders extends NgRequestTransformer {

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Headers, NgPluginCategory.Classic)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = true
  override def transformsResponse: Boolean                 = false
  override def transformsError: Boolean                    = false
  override def isTransformRequestAsync: Boolean            = false
  override def isTransformResponseAsync: Boolean           = true
  override def name: String                                = "X-Forwarded-* headers"
  override def description: Option[String]                 =
    "This plugin adds all the X-Forwarded-* headers to the request for the backend target".some
  override def defaultConfigObject: Option[NgPluginConfig] = None

  override def transformRequestSync(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpRequest] = {
    val request           = ctx.request
    val additionalHeaders = if (env.datastores.globalConfigDataStore.latestSafe.exists(_.trustXForwarded)) {
      val xForwardedFor   = request.headers
        .get("X-Forwarded-For")
        .map(v => v + ", " + request.remoteAddress)
        .getOrElse(request.remoteAddress)
      val xForwardedProto = request.theProtocol
      val xForwardedHost  = request.theHost
      Seq(
        "X-Forwarded-For"   -> xForwardedFor,
        "X-Forwarded-Host"  -> xForwardedHost,
        "X-Forwarded-Proto" -> xForwardedProto
      ).applyOnWithOpt(ctx.attrs.get(otoroshi.next.plugins.Keys.MatchedRouteKey)) {
        case (hdrs, route) if route.route.frontend.stripPath && !route.path.isBlank =>
          hdrs ++ Seq(
            "X-Forwarded-Prefix" -> route.path
          )
        case (hdrs, _)                                                              => hdrs
      }
    } else if (!env.datastores.globalConfigDataStore.latestSafe.exists(_.trustXForwarded)) {
      val xForwardedFor   = request.remoteAddress
      val xForwardedProto = request.theProtocol
      val xForwardedHost  = request.theHost
      Seq(
        "X-Forwarded-For"   -> xForwardedFor,
        "X-Forwarded-Host"  -> xForwardedHost,
        "X-Forwarded-Proto" -> xForwardedProto
      ).applyOnWithOpt(ctx.attrs.get(otoroshi.next.plugins.Keys.MatchedRouteKey)) {
        case (hdrs, route) if route.route.frontend.stripPath && !route.path.isBlank =>
          hdrs ++ Seq(
            "X-Forwarded-Prefix" -> route.path
          )
        case (hdrs, _)                                                              => hdrs
      }
    } else {
      Seq.empty[(String, String)]
    }
    Right(ctx.otoroshiRequest.copy(headers = ctx.otoroshiRequest.headers ++ additionalHeaders.toMap))
  }
}

class ForwardedHeader extends NgRequestTransformer {

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Headers, NgPluginCategory.Classic)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = true
  override def transformsResponse: Boolean                 = false
  override def transformsError: Boolean                    = false
  override def isTransformRequestAsync: Boolean            = false
  override def isTransformResponseAsync: Boolean           = true
  override def name: String                                = "Forwarded header"
  override def description: Option[String]                 =
    "This plugin adds all the Forwarded header to the request for the backend target".some
  override def defaultConfigObject: Option[NgPluginConfig] = None

  override def transformRequestSync(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpRequest] = {
    val request           = ctx.request
    val additionalHeaders = if (env.datastores.globalConfigDataStore.latestSafe.exists(_.trustXForwarded)) {
      val xForwardedFor   = request.headers
        .get("X-Forwarded-For")
        .map(v => v + ", for: " + request.remoteAddress.applyOnWithPredicate(_.contains(":")) { v => s""""$v"""" })
        .getOrElse(request.remoteAddress.applyOnWithPredicate(_.contains(":")) { v => s""""$v"""" })
      val xForwardedBy    = request.headers
        .get("X-Forwarded-By")
        .getOrElse(request.remoteAddress.applyOnWithPredicate(_.contains(":")) { v => s""""$v"""" })
      val xForwardedProto = request.theProtocol
      val xForwardedHost  = request.theHost
      Seq(
        "Forwarded" -> s"${xForwardedFor};proto: ${xForwardedProto};host: ${xForwardedHost};by: ${xForwardedBy}"
      )
    } else {
      val xForwardedFor   = request.remoteAddress.applyOnWithPredicate(_.contains(":")) { v => s""""$v"""" }
      val xForwardedProto = request.theProtocol
      val xForwardedHost  = request.theHost
      Seq(
        "Forwarded" -> s"for: ${xForwardedFor};proto: ${xForwardedProto};host: ${xForwardedHost};by: "
      )
    }
    Right(ctx.otoroshiRequest.copy(headers = ctx.otoroshiRequest.headers ++ additionalHeaders.toMap))
  }
}

case class RejectHeaderConfig(value: Long = 8 * 1024) extends NgPluginConfig {
  def json: JsValue = RejectHeaderConfig.format.writes(this)
}

object RejectHeaderConfig {
  val default                        = RejectHeaderConfig()
  val configFlow: Seq[String]        = Seq("value")
  val configSchema: Option[JsObject] = Some(
    Json.obj(
      "value" -> Json.obj(
        "type"  -> "number",
        "label" -> "Max length",
        "props" -> Json.obj(
          "label"  -> "Max Length",
          "suffix" -> "bytes"
        )
      )
    )
  )
  val format                         = new Format[RejectHeaderConfig] {

    override def reads(json: JsValue): JsResult[RejectHeaderConfig] = Try {
      RejectHeaderConfig(
        value = json.select("value").asOpt[Long].getOrElse(8 * 1024)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }

    override def writes(o: RejectHeaderConfig): JsValue = Json.obj(
      "value" -> o.value
    )
  }
}

class RejectHeaderInTooLong extends NgRequestTransformer {

  private val logger                             = Logger("otoroshi-plugin-reject-headers-in-too-long")
  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Headers)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = true
  override def transformsResponse: Boolean                 = false
  override def transformsError: Boolean                    = false
  override def isTransformRequestAsync: Boolean            = false
  override def isTransformResponseAsync: Boolean           = true
  override def name: String                                = "Reject headers in too long"
  override def description: Option[String]                 =
    "This plugin remove all headers to backend with a length above a max".some
  override def defaultConfigObject: Option[NgPluginConfig] = Some(RejectHeaderConfig())

  override def noJsForm: Boolean = true

  override def configFlow: Seq[String] = RejectHeaderConfig.configFlow

  override def configSchema: Option[JsObject] = RejectHeaderConfig.configSchema

  override def transformRequestSync(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpRequest] = {
    val config = ctx.cachedConfig(internalName)(RejectHeaderConfig.format).getOrElse(RejectHeaderConfig())
    Right(
      ctx.otoroshiRequest.copy(
        headers = ctx.otoroshiRequest.headers.filter {
          case (key, value) if value.length > config.value => {
            HeaderTooLongAlert(key, value, "", "remove", "client", "plugin", ctx.otoroshiRequest, ctx.route, env)
              .toAnalytics()
            logger.error(
              s"removing header '${key}' from request to backend because it's too long. route is ${ctx.route.name} / ${ctx.route.id}. header value length is '${value.length}' and value is '${value}'"
            )
            false
          }
          case _                                           => true
        }
      )
    )
  }
}

class RejectHeaderOutTooLong extends NgRequestTransformer {

  private val logger                             = Logger("otoroshi-plugin-reject-headers-out-too-long")
  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Headers)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = false
  override def transformsResponse: Boolean                 = true
  override def transformsError: Boolean                    = false
  override def isTransformRequestAsync: Boolean            = false
  override def isTransformResponseAsync: Boolean           = false
  override def name: String                                = "Reject headers out too long"
  override def description: Option[String]                 =
    "This plugin remove all headers from backend with a length above a max".some
  override def defaultConfigObject: Option[NgPluginConfig] = Some(RejectHeaderConfig())

  override def noJsForm: Boolean = true

  override def configFlow: Seq[String] = RejectHeaderConfig.configFlow

  override def configSchema: Option[JsObject] = RejectHeaderConfig.configSchema

  override def transformResponseSync(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpResponse] = {
    val config = ctx.cachedConfig(internalName)(RejectHeaderConfig.format).getOrElse(RejectHeaderConfig())
    Right(
      ctx.otoroshiResponse.copy(
        headers = ctx.otoroshiResponse.headers.filter {
          case (key, value) if value.length > config.value => {
            HeaderTooLongAlert(
              key,
              value,
              "",
              "remove",
              "client",
              "plugin",
              NgPluginHttpRequest.fromRequest(ctx.request),
              ctx.route,
              env
            ).toAnalytics()
            logger.error(
              s"removing header '${key}' from response to client because it's too long. route is ${ctx.route.name} / ${ctx.route.id}. header value length is '${value.length}' and value is '${value}'"
            )
            false
          }
          case _                                           => true
        }
      )
    )
  }
}

case class HeaderTooLongAlert(
    headerName: String,
    headerValue: String,
    newHeaderValue: String,
    action: String,
    to: String,
    from: String,
    request: NgPluginHttpRequest,
    route: NgRoute,
    env: Env
) extends AlertEvent {

  val `@id`: String          = env.snowflakeGenerator.nextIdStr()
  val `@timestamp`: DateTime = DateTime.now()

  override def `@service`: String   = "Otoroshi"
  override def `@serviceId`: String = "--"

  override def fromOrigin: Option[String]    = None
  override def fromUserAgent: Option[String] = None

  override def toJson(implicit _env: Env): JsValue =
    Json.obj(
      "@id"                 -> `@id`,
      "@timestamp"          -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
      "@type"               -> `@type`,
      "@product"            -> _env.eventsName,
      "@serviceId"          -> `@serviceId`,
      "@service"            -> `@service`,
      "@env"                -> env.env,
      "alert"               -> "HeaderTooLongAlert",
      "action"              -> action,
      "to"                  -> to,
      "from"                -> from,
      "header_name"         -> headerName,
      "header_value"        -> headerValue,
      "header_value_length" -> headerValue,
      "new_header_value"    -> headerValue,
      "request"             -> request.json
    )
}

class LimitHeaderInTooLong extends NgRequestTransformer {

  private val logger                             = Logger("otoroshi-plugin-limit-headers-in-too-long")
  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Headers)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = true
  override def transformsResponse: Boolean                 = false
  override def transformsError: Boolean                    = false
  override def isTransformRequestAsync: Boolean            = false
  override def isTransformResponseAsync: Boolean           = true
  override def name: String                                = "Limit headers in too long"
  override def description: Option[String]                 =
    "This plugin limit all headers to backend with a length above a max".some
  override def defaultConfigObject: Option[NgPluginConfig] = Some(RejectHeaderConfig())

  override def noJsForm: Boolean = true

  override def configFlow: Seq[String] = RejectHeaderConfig.configFlow

  override def configSchema: Option[JsObject] = RejectHeaderConfig.configSchema

  override def transformRequestSync(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpRequest] = {
    val config = ctx.cachedConfig(internalName)(RejectHeaderConfig.format).getOrElse(RejectHeaderConfig())
    Right(
      ctx.otoroshiRequest.copy(
        headers = ctx.otoroshiRequest.headers.map {
          case (key, value) if value.length > config.value => {
            val newValue = value.substring(0, config.value.toInt - 1)
            HeaderTooLongAlert(key, value, newValue, "limit", "backend", "plugin", ctx.otoroshiRequest, ctx.route, env)
              .toAnalytics()
            logger.error(
              s"limiting header '${key}' from request to backend because it's too long. route is ${ctx.route.name} / ${ctx.route.id}. header value length is '${value.length}' and value is '${value}', new value is '${newValue}'"
            )
            (key, newValue)
          }
          case (key, value)                                => (key, value)
        }
      )
    )
  }
}

class LimitHeaderOutTooLong extends NgRequestTransformer {

  private val logger                             = Logger("otoroshi-plugin-limit-headers-out-too-long")
  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Headers)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = false
  override def transformsResponse: Boolean                 = true
  override def transformsError: Boolean                    = false
  override def isTransformRequestAsync: Boolean            = false
  override def isTransformResponseAsync: Boolean           = false
  override def name: String                                = "Limit headers out too long"
  override def description: Option[String]                 =
    "This plugin limit all headers from backend with a length above a max".some
  override def defaultConfigObject: Option[NgPluginConfig] = Some(RejectHeaderConfig())

  override def noJsForm: Boolean = true

  override def configFlow: Seq[String] = RejectHeaderConfig.configFlow

  override def configSchema: Option[JsObject] = RejectHeaderConfig.configSchema

  override def transformResponseSync(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpResponse] = {
    val config = ctx.cachedConfig(internalName)(RejectHeaderConfig.format).getOrElse(RejectHeaderConfig())
    Right(
      ctx.otoroshiResponse.copy(
        headers = ctx.otoroshiResponse.headers.map {
          case (key, value) if value.length > config.value => {
            val newValue = value.substring(0, config.value.toInt - 1)
            HeaderTooLongAlert(
              key,
              value,
              newValue,
              "limit",
              "client",
              "plugin",
              NgPluginHttpRequest.fromRequest(ctx.request),
              ctx.route,
              env
            ).toAnalytics()
            logger.error(
              s"limiting header '${key}' from response to client because it's too long. route is ${ctx.route.name} / ${ctx.route.id}. header value length is '${value.length}' and value is '${value}', new value is '${newValue}'"
            )
            (key, newValue)
          }
          case (key, value)                                => (key, value)
        }
      )
    )
  }
}
