package otoroshi.next.plugins

import akka.stream.Materializer
import otoroshi.el.HeadersExpressionLanguage
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.models.RemainingQuotas
import otoroshi.next.plugins.api._
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits._
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
        names = json.select("header_names").asOpt[Seq[String]].getOrElse(Seq.empty)
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

  override def multiInstance: Boolean                      = false
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
        val host    = backend.hostname
        val headers = ctx.otoroshiRequest.headers.-("Host").-("host").+("Host" -> host)
        val request = ctx.otoroshiRequest.copy(headers = headers)
        Right(request)
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
    val validationHeaders = ctx.cachedConfig(internalName)(configReads).getOrElse(NgHeaderValuesConfig()).headers.map {
      case (key, value) => (key.toLowerCase, value)
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
  override def isTransformRequestAsync: Boolean            = true
  override def isTransformResponseAsync: Boolean           = false
  override def name: String                                = "Additional headers out"
  override def description: Option[String]                 = "This plugin adds headers in the otoroshi response".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgHeaderValuesConfig().some

  override def transformResponseSync(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpResponse] = {
    val additionalHeaders =
      ctx.cachedConfig(internalName)(configReads).getOrElse(NgHeaderValuesConfig()).headers.mapValues { value =>
        HeadersExpressionLanguage(
          value,
          ctx.request.some,
          ctx.route.serviceDescriptor.some,
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

  import otoroshi.utils.http.HeadersHelperImplicits._

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Headers, NgPluginCategory.Classic)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = false
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

  override def multiInstance: Boolean                      = false
  override def core: Boolean                               = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = true
  override def transformsResponse: Boolean                 = false
  override def transformsError: Boolean                    = false
  override def isTransformRequestAsync: Boolean            = false
  override def isTransformResponseAsync: Boolean           = true
  override def name: String                                = "X-Forwarded-* headers"
  override def description: Option[String]                 =
    "This plugin adds all the X-Forwarder-* headers to the request for the backend target".some
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
      )
    } else if (!env.datastores.globalConfigDataStore.latestSafe.exists(_.trustXForwarded)) {
      val xForwardedFor   = request.remoteAddress
      val xForwardedProto = request.theProtocol
      val xForwardedHost  = request.theHost
      Seq(
        "X-Forwarded-For"   -> xForwardedFor,
        "X-Forwarded-Host"  -> xForwardedHost,
        "X-Forwarded-Proto" -> xForwardedProto
      )
    } else {
      Seq.empty[(String, String)]
    }
    Right(ctx.otoroshiRequest.copy(headers = ctx.otoroshiRequest.headers ++ additionalHeaders.toMap))
  }
}
