package otoroshi.next.plugins

import akka.stream.scaladsl.Source
import akka.stream.{Materializer, ThrottleMode}
import akka.util.ByteString
import otoroshi.el.GlobalExpressionLanguage
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.next.models.NgRoute
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgExecutionReport
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.{RequestHeader, Result, Results}

import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.DurationLong
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class BodyLengthLimiterConfig(maxLength: Option[Long] = None, fail: Boolean = false) extends NgPluginConfig {
  def json: JsValue = BodyLengthLimiterConfig.format.writes(this)
}

object BodyLengthLimiterConfig {
  val configFlow: Seq[String]        = Seq("max_length", "fail")
  val configSchema: Option[JsObject] = Some(
    Json.obj(
      "max_length" -> Json.obj(
        "type"  -> "number",
        "label" -> "Max length",
        "props" -> Json.obj(
          "label"  -> "Max Length",
          "suffix" -> "bytes"
        )
      ),
      "fail"       -> Json.obj(
        "type"  -> "bool",
        "label" -> "Fail on bigger body",
        "props" -> Json.obj(
          "label" -> "Fail on bigger body"
        )
      )
    )
  )
  val format                         = new Format[BodyLengthLimiterConfig] {
    override def reads(json: JsValue): JsResult[BodyLengthLimiterConfig] = Try {
      BodyLengthLimiterConfig(
        maxLength = json.select("max_length").asOpt[Long],
        fail = json.select("fail").asOpt[Boolean].getOrElse(false)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }
    override def writes(o: BodyLengthLimiterConfig): JsValue             = Json.obj(
      "max_length" -> o.maxLength,
      "fail"       -> o.fail
    )
  }
}

class RequestBodyLengthLimiter extends NgRequestTransformer {

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Transformations)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = true
  override def transformsResponse: Boolean                 = false
  override def name: String                                = "Request Body length limiter"
  override def description: Option[String]                 = "This plugin will limit request body length".some
  override def defaultConfigObject: Option[NgPluginConfig] = Some(BodyLengthLimiterConfig())
  override def noJsForm: Boolean                           = true
  override def configFlow: Seq[String]                     = BodyLengthLimiterConfig.configFlow
  override def configSchema: Option[JsObject]              = BodyLengthLimiterConfig.configSchema

  private def chunkResponse(ctx: NgTransformerRequestContext, max: Long) = {
    val counter = new AtomicLong(0L)

    val newBody = ctx.otoroshiRequest.body.prefixAndTail(1).flatMapConcat { case (headSeq, tail) =>
      val headChunk = headSeq.head
      val remaining = max - counter.get()
      val first     = if (headChunk.size > remaining) headChunk.take(remaining.toInt) else headChunk
      counter.addAndGet(first.size)
      Source.single(first) ++ tail.takeWhile { chunk =>
        counter.addAndGet(chunk.size) < max
      }
    }

    val updatedHeaders = ctx.otoroshiRequest.headers
      .removeAll(Seq("Content-Length"))

    Right(
      ctx.otoroshiRequest.copy(
        body = newBody,
        headers = updatedHeaders
      )
    ).vfuture
  }

  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val config    = ctx.cachedConfig(internalName)(BodyLengthLimiterConfig.format).getOrElse(BodyLengthLimiterConfig())
    val max: Long = config.maxLength.getOrElse(4 * 1024 * 1024)

    if (config.fail) {
      ctx.otoroshiRequest.contentLength match {
        case Some(contentLength) if contentLength > max =>
          Errors
            .craftResponseResult(
              "Response entity too large",
              Results.EntityTooLarge,
              ctx.request,
              None,
              Some("errors.failed.response.entityTooLarge"),
              duration = ctx.report.getDurationNow(),
              overhead = ctx.report.getOverheadInNow(),
              attrs = ctx.attrs,
              maybeRoute = ctx.route.some
            )
            .map(_.left)
        case _                                          =>
          Right(ctx.otoroshiRequest.copy(body = ctx.otoroshiRequest.body.limitWeighted(max)(_.size))).vfuture
      }
    } else
      chunkResponse(ctx, max)
  }
}

class ResponseBodyLengthLimiter extends NgRequestTransformer {

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Transformations)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = false
  override def transformsResponse: Boolean                 = true
  override def name: String                                = "Response Body length limiter"
  override def description: Option[String]                 = "This plugin will limit response body length".some
  override def defaultConfigObject: Option[NgPluginConfig] = Some(BodyLengthLimiterConfig())
  override def noJsForm: Boolean                           = true
  override def configFlow: Seq[String]                     = BodyLengthLimiterConfig.configFlow
  override def configSchema: Option[JsObject]              = BodyLengthLimiterConfig.configSchema

  private def chunkResponse(ctx: NgTransformerResponseContext, max: Long) = {
    val counter = new AtomicLong(0L)

    val newBody = ctx.otoroshiResponse.body.prefixAndTail(1).flatMapConcat { case (headSeq, tail) =>
      val headChunk = headSeq.head
      val remaining = max - counter.get()
      val first     = if (headChunk.size > remaining) headChunk.take(remaining.toInt) else headChunk
      counter.addAndGet(first.size)
      Source.single(first) ++ tail.takeWhile { chunk =>
        counter.addAndGet(chunk.size) < max
      }
    }

    val updatedHeaders = ctx.otoroshiResponse.headers
      .removeAll(Seq("Content-Length"))

    Right(
      ctx.otoroshiResponse.copy(
        body = newBody,
        headers = updatedHeaders
      )
    ).vfuture
  }

  override def transformResponse(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val config    = ctx.cachedConfig(internalName)(BodyLengthLimiterConfig.format).getOrElse(BodyLengthLimiterConfig())
    val max: Long = config.maxLength.getOrElse(4 * 1024 * 1024)

    if (config.fail) {
      ctx.otoroshiResponse.contentLength match {
        case Some(contentLength) if contentLength > max =>
          Errors
            .craftResponseResult(
              "Response entity too large",
              Results.EntityTooLarge,
              ctx.request,
              None,
              Some("errors.failed.response.entityTooLarge"),
              duration = ctx.report.getDurationNow(),
              overhead = ctx.report.getOverheadInNow(),
              attrs = ctx.attrs,
              maybeRoute = ctx.route.some
            )
            .map(_.left)
        case _                                          =>
          Right(ctx.otoroshiResponse.copy(body = ctx.otoroshiResponse.body.limitWeighted(max)(_.size))).vfuture
      }
    } else
      chunkResponse(ctx, max)
  }
}

sealed trait BandwidthThrottlingConfigKind {
  def name: String
  def json: JsValue = name.json
}
object BandwidthThrottlingConfigKind       {
  case object PerRequest extends BandwidthThrottlingConfigKind { def name: String = "per_request" }
  case object PerNode    extends BandwidthThrottlingConfigKind { def name: String = "per_node"    }
  case object PerCluster extends BandwidthThrottlingConfigKind { def name: String = "per_cluster" }
}

case class BandwidthThrottlingConfig(
    windowMillis: String,
    groupExpr: String,
    _throttlingQuota: String,
    fail: Boolean,
    kind: BandwidthThrottlingConfigKind
) extends NgPluginConfig {
  def json: JsValue = BandwidthThrottlingConfig.format.writes(this)
  def throttlingQuota(attrs: TypedMap, env: Env): Long = {
    _throttlingQuota.trim match {
      case expr if expr.contains("${") && expr.contains("}") => {
        GlobalExpressionLanguage
          .apply(
            value = expr,
            attrs = attrs,
            env = env
          )
          .toLong
      }
      case value                                             => value.trim.toLong
    }
  }
}

object BandwidthThrottlingConfig {
  val default                                   =
    BandwidthThrottlingConfig("60000", "${route.id}", "10485760", fail = true, BandwidthThrottlingConfigKind.PerRequest)
  val format: Format[BandwidthThrottlingConfig] = new Format[BandwidthThrottlingConfig] {
    override def reads(json: JsValue): JsResult[BandwidthThrottlingConfig] = Try {
      BandwidthThrottlingConfig(
        windowMillis = json
          .select("window_millis")
          .asOpt[String]
          .filterNot(_.isBlank)
          .getOrElse(BandwidthThrottlingConfig.default.windowMillis),
        _throttlingQuota = json
          .select("throttling_quota")
          .asOpt[String]
          .filterNot(_.isBlank)
          .getOrElse(BandwidthThrottlingConfig.default._throttlingQuota),
        groupExpr = json
          .select("group_expr")
          .asOpt[String]
          .filterNot(_.isBlank)
          .getOrElse(BandwidthThrottlingConfig.default.groupExpr),
        fail = json.select("fail").asOptBoolean.getOrElse(true),
        kind = json
          .select("kind")
          .asOptString
          .map(_.toLowerCase)
          .map {
            case "per_request" => BandwidthThrottlingConfigKind.PerRequest
            case "per_node"    => BandwidthThrottlingConfigKind.PerNode
            case "per_cluster" => BandwidthThrottlingConfigKind.PerCluster
          }
          .getOrElse(BandwidthThrottlingConfig.default.kind)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }
    override def writes(o: BandwidthThrottlingConfig): JsValue             = Json.obj(
      "window_millis"    -> o.windowMillis,
      "throttling_quota" -> o._throttlingQuota,
      "group_expr"       -> o.groupExpr,
      "fail"             -> o.fail,
      "kind"             -> o.kind.json
    )
  }

  val configFlow   = Seq(
    "window_millis",
    "throttling_quota",
    "group_expr",
    "fail",
    "kind"
  )
  val configSchema = Some(
    Json.obj(
      "window_millis"    -> Json.obj(
        "type"   -> "string",
        "suffix" -> "millis.",
        "label"  -> "Time window"
      ),
      "throttling_quota" -> Json.obj(
        "type"   -> "string",
        "suffix" -> "tokens",
        "label"  -> "Max consumption"
      ),
      "group_expr"       -> Json.obj(
        "type"  -> "string",
        "label" -> "Group by"
      ),
      "fail"             -> Json.obj(
        "type"  -> "bool",
        "label" -> "Fail request"
      ),
      "kind"             -> Json.obj(
        "type"  -> "select",
        "label" -> "Type",
        "props" -> Json.obj(
          "label"     -> "Type",
          "ngOptions" -> Json.obj(
            "spread" -> true
          ),
          "options"   -> Json.arr(
            Json.obj("value" -> "per_request", "label" -> "Per Request"),
            Json.obj("value" -> "per_node", "label"    -> "Per Node"),
            Json.obj("value" -> "per_cluster", "label" -> "Per cluster")
          )
        )
      )
    )
  )
}

object BandwidthThrottling {
  val RequestBandwidthThrottlingAccKey  = play.api.libs.typedmap.TypedKey[Boolean]("RequestBandwidthThrottlingAccKey")
  val ResponseBandwidthThrottlingAccKey = play.api.libs.typedmap.TypedKey[Boolean]("ResponseBandwidthThrottlingAccKey")
}

class RequestBandwidthThrottling extends NgAccessValidator with NgRequestTransformer {

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Transformations)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = true
  override def transformsResponse: Boolean                 = false
  override def name: String                                = "Request bandwidth throttling"
  override def description: Option[String]                 = "This plugin will limit request body bandwidth".some
  override def defaultConfigObject: Option[NgPluginConfig] = Some(BandwidthThrottlingConfig.default)
  override def noJsForm: Boolean                           = true
  override def configFlow: Seq[String]                     = BandwidthThrottlingConfig.configFlow
  override def configSchema: Option[JsObject]              = BandwidthThrottlingConfig.configSchema

  private val defaultExpr = "RequestBandwidthThrottling-bytes"

  private def throttlingKey(name: String, group: String, attrs: TypedMap, local: Boolean)(implicit env: Env): String = {
    if (local) {
      NgCustomThrottling.localThrottlingKey(computeExpr(name, attrs, env), computeExpr(group, attrs, env))
    } else {
      NgCustomThrottling.throttlingKey(computeExpr(name, attrs, env), computeExpr(group, attrs, env))
    }
  }

  private def computeExpr(expr: String, attrs: TypedMap, env: Env): String = {
    GlobalExpressionLanguage.apply(
      value = expr,
      attrs = attrs,
      env = env
    )
  }

  private def withingQuotas(
      attrs: TypedMap,
      qconf: BandwidthThrottlingConfig,
      local: Boolean
  )(implicit ec: ExecutionContext, env: Env): Future[Boolean] = {
    val value = qconf.throttlingQuota(attrs, env)
    val group = computeExpr(qconf.groupExpr, attrs, env)
    val key   = throttlingKey(computeExpr(defaultExpr, attrs, env), group, attrs, local)
    env.datastores.rawDataStore
      .get(key)
      .map { opt =>
        val current = opt.map(_.utf8String.toLong).getOrElse(0L)
        current <= value
      }
  }

  private def updateQuotas(increment: Long, attrs: TypedMap, qconf: BandwidthThrottlingConfig, local: Boolean)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Unit] = {
    val group        = computeExpr(qconf.groupExpr, attrs, env)
    val expr         = computeExpr(defaultExpr, attrs, env)
    val windowMillis = computeExpr(qconf.windowMillis, attrs, env).trim.toLong
    if (!local) {
      env.clusterAgent.incrementCustomThrottling(expr, group, increment, windowMillis)
      NgCustomThrottling.updateQuotas(expr, group, increment, windowMillis)
    } else {
      NgCustomThrottling.localUpdateQuotas(expr, group, increment, windowMillis)
    }
  }

  private def error(request: RequestHeader, report: NgExecutionReport, attrs: TypedMap, route: NgRoute)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Result] = {
    Errors
      .craftResponseResult(
        "Bandwidth limit exceeded",
        Results.EntityTooLarge,
        request,
        None,
        None,
        duration = report.getDurationNow(),
        overhead = report.getOverheadInNow(),
        attrs = attrs,
        maybeRoute = route.some
      )
  }

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config =
      ctx.cachedConfig(internalName)(BandwidthThrottlingConfig.format).getOrElse(BandwidthThrottlingConfig.default)
    config.kind match {
      case BandwidthThrottlingConfigKind.PerRequest => NgAccess.NgAllowed.vfuture
      case BandwidthThrottlingConfigKind.PerNode    =>
        withingQuotas(ctx.attrs, config, local = true) flatMap {
          case true  => NgAccess.NgAllowed.vfuture
          case false => error(ctx.request, ctx.report, ctx.attrs, ctx.route).map(r => NgAccess.NgDenied(r))
        }
      case BandwidthThrottlingConfigKind.PerCluster =>
        withingQuotas(ctx.attrs, config, local = false) flatMap {
          case true  => NgAccess.NgAllowed.vfuture
          case false => error(ctx.request, ctx.report, ctx.attrs, ctx.route).map(r => NgAccess.NgDenied(r))
        }
    }
  }

  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val config       =
      ctx.cachedConfig(internalName)(BandwidthThrottlingConfig.format).getOrElse(BandwidthThrottlingConfig.default)
    val windowMillis = computeExpr(config.windowMillis, ctx.attrs, env).trim.toLong.millis
    config.kind match {
      case BandwidthThrottlingConfigKind.PerRequest => {
        ctx.otoroshiRequest
          .copy(body =
            ctx.otoroshiRequest.body.throttle(
              cost = config.throttlingQuota(ctx.attrs, env).toInt,
              maximumBurst = -1,
              per = windowMillis,
              costCalculation = (chunk: ByteString) => chunk.size,
              mode = if (config.fail) ThrottleMode.enforcing else ThrottleMode.shaping
            )
          )
          .rightf
      }
      case BandwidthThrottlingConfigKind.PerNode    => {
        // TODO: handle !fail
        ctx.otoroshiRequest
          .copy(body = ctx.otoroshiRequest.body.flatMapConcat { chunk =>
            updateQuotas(chunk.size, ctx.attrs, config, local = true)
            withingQuotas(ctx.attrs, config, local = true).map { within =>
              ctx.attrs.put(BandwidthThrottling.RequestBandwidthThrottlingAccKey -> within)
            }
            val within = ctx.attrs.get(BandwidthThrottling.RequestBandwidthThrottlingAccKey).getOrElse(true)
            if (within) {
              Source.single(chunk)
            } else {
              Source.failed(new RuntimeException("Bandwidth limit exceeded"))
            }
          })
          .rightf
      }
      case BandwidthThrottlingConfigKind.PerCluster => {
        // TODO: handle !fail
        ctx.otoroshiRequest
          .copy(body = ctx.otoroshiRequest.body.flatMapConcat { chunk =>
            updateQuotas(chunk.size, ctx.attrs, config, local = false)
            withingQuotas(ctx.attrs, config, local = false).map { within =>
              ctx.attrs.put(BandwidthThrottling.RequestBandwidthThrottlingAccKey -> within)
            }
            val within = ctx.attrs.get(BandwidthThrottling.RequestBandwidthThrottlingAccKey).getOrElse(true)
            if (within) {
              Source.single(chunk)
            } else {
              Source.failed(new RuntimeException("Bandwidth limit exceeded"))
            }
          })
          .rightf
      }
    }
  }
}

class ResponseBandwidthThrottling extends NgAccessValidator with NgRequestTransformer {

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Transformations)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = false
  override def transformsResponse: Boolean                 = true
  override def name: String                                = "Response bandwidth throttling"
  override def description: Option[String]                 = "This plugin will limit response body bandwidth".some
  override def defaultConfigObject: Option[NgPluginConfig] = Some(BandwidthThrottlingConfig.default)
  override def noJsForm: Boolean                           = true
  override def configFlow: Seq[String]                     = BandwidthThrottlingConfig.configFlow
  override def configSchema: Option[JsObject]              = BandwidthThrottlingConfig.configSchema

  private val defaultExpr = "ResponseBandwidthThrottling-bytes"

  private def throttlingKey(name: String, group: String, attrs: TypedMap, local: Boolean)(implicit env: Env): String = {
    if (local) {
      NgCustomThrottling.localThrottlingKey(computeExpr(name, attrs, env), computeExpr(group, attrs, env))
    } else {
      NgCustomThrottling.throttlingKey(computeExpr(name, attrs, env), computeExpr(group, attrs, env))
    }
  }

  private def computeExpr(expr: String, attrs: TypedMap, env: Env): String = {
    GlobalExpressionLanguage.apply(
      value = expr,
      attrs = attrs,
      env = env
    )
  }

  private def withingQuotas(
      attrs: TypedMap,
      qconf: BandwidthThrottlingConfig,
      local: Boolean
  )(implicit ec: ExecutionContext, env: Env): Future[Boolean] = {
    val value = qconf.throttlingQuota(attrs, env)
    val group = computeExpr(qconf.groupExpr, attrs, env)
    val key   = throttlingKey(computeExpr(defaultExpr, attrs, env), group, attrs, local)
    env.datastores.rawDataStore
      .get(key)
      .map { opt =>
        val current = opt.map(_.utf8String.toLong).getOrElse(0L)
        current <= value
      }
  }

  private def updateQuotas(increment: Long, attrs: TypedMap, qconf: BandwidthThrottlingConfig, local: Boolean)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Unit] = {
    val group        = computeExpr(qconf.groupExpr, attrs, env)
    val expr         = computeExpr(defaultExpr, attrs, env)
    val windowMillis = computeExpr(qconf.windowMillis, attrs, env).trim.toLong
    if (!local) {
      env.clusterAgent.incrementCustomThrottling(expr, group, increment, windowMillis)
      NgCustomThrottling.updateQuotas(expr, group, increment, windowMillis)
    } else {
      NgCustomThrottling.localUpdateQuotas(expr, group, increment, windowMillis)
    }
  }

  private def error(request: RequestHeader, report: NgExecutionReport, attrs: TypedMap, route: NgRoute)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Result] = {
    Errors
      .craftResponseResult(
        "Bandwidth limit exceeded",
        Results.EntityTooLarge,
        request,
        None,
        None,
        duration = report.getDurationNow(),
        overhead = report.getOverheadInNow(),
        attrs = attrs,
        maybeRoute = route.some
      )
  }

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config =
      ctx.cachedConfig(internalName)(BandwidthThrottlingConfig.format).getOrElse(BandwidthThrottlingConfig.default)
    config.kind match {
      case BandwidthThrottlingConfigKind.PerRequest => NgAccess.NgAllowed.vfuture
      case BandwidthThrottlingConfigKind.PerNode    =>
        withingQuotas(ctx.attrs, config, local = true) flatMap {
          case true  => NgAccess.NgAllowed.vfuture
          case false => error(ctx.request, ctx.report, ctx.attrs, ctx.route).map(r => NgAccess.NgDenied(r))
        }
      case BandwidthThrottlingConfigKind.PerCluster =>
        withingQuotas(ctx.attrs, config, local = false) flatMap {
          case true  => NgAccess.NgAllowed.vfuture
          case false => error(ctx.request, ctx.report, ctx.attrs, ctx.route).map(r => NgAccess.NgDenied(r))
        }
    }
  }

  override def transformResponse(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val config       =
      ctx.cachedConfig(internalName)(BandwidthThrottlingConfig.format).getOrElse(BandwidthThrottlingConfig.default)
    val windowMillis = computeExpr(config.windowMillis, ctx.attrs, env).trim.toLong.millis
    config.kind match {
      case BandwidthThrottlingConfigKind.PerRequest => {
        ctx.otoroshiResponse
          .copy(body =
            ctx.otoroshiResponse.body.throttle(
              cost = config.throttlingQuota(ctx.attrs, env).toInt,
              maximumBurst = -1,
              per = windowMillis,
              costCalculation = (chunk: ByteString) => chunk.size,
              mode = if (config.fail) ThrottleMode.enforcing else ThrottleMode.shaping
            )
          )
          .rightf
      }
      case BandwidthThrottlingConfigKind.PerNode    => {
        // TODO: handle !fail
        ctx.otoroshiResponse
          .copy(body = ctx.otoroshiResponse.body.flatMapConcat { chunk =>
            updateQuotas(chunk.size, ctx.attrs, config, local = true)
            withingQuotas(ctx.attrs, config, local = true).map { within =>
              ctx.attrs.put(BandwidthThrottling.ResponseBandwidthThrottlingAccKey -> within)
            }
            val within = ctx.attrs.get(BandwidthThrottling.ResponseBandwidthThrottlingAccKey).getOrElse(true)
            if (within) {
              Source.single(chunk)
            } else {
              Source.failed(new RuntimeException("Bandwidth limit exceeded"))
            }
          })
          .rightf
      }
      case BandwidthThrottlingConfigKind.PerCluster => {
        // TODO: handle !fail
        ctx.otoroshiResponse
          .copy(body = ctx.otoroshiResponse.body.flatMapConcat { chunk =>
            updateQuotas(chunk.size, ctx.attrs, config, local = false)
            withingQuotas(ctx.attrs, config, local = false).map { within =>
              ctx.attrs.put(BandwidthThrottling.ResponseBandwidthThrottlingAccKey -> within)
            }
            val within = ctx.attrs.get(BandwidthThrottling.ResponseBandwidthThrottlingAccKey).getOrElse(true)
            if (within) {
              Source.single(chunk)
            } else {
              Source.failed(new RuntimeException("Bandwidth limit exceeded"))
            }
          })
          .rightf
      }
    }
  }
}
