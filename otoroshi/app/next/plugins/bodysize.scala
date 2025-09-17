package otoroshi.next.plugins

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{Format, JsError, JsObject, JsResult, JsSuccess, JsValue, Json}
import play.api.mvc.{Result, Results}

import java.util.concurrent.atomic.AtomicLong
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
      "fail" -> Json.obj(
        "type"  -> "bool",
        "label" -> "Fail on bigger body",
        "props" -> Json.obj(
          "label"  -> "Fail on bigger body",
        )
      )
    )
  )
  val format = new Format[BodyLengthLimiterConfig] {
    override def reads(json: JsValue): JsResult[BodyLengthLimiterConfig] = Try {
      BodyLengthLimiterConfig(
        maxLength = json.select("max_length").asOpt[Long],
        fail = json.select("fail").asOpt[Boolean].getOrElse(false),
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }
    override def writes(o: BodyLengthLimiterConfig): JsValue = Json.obj(
      "max_length" -> o.maxLength,
      "fail" -> o.fail,
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
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = BodyLengthLimiterConfig.configFlow
  override def configSchema: Option[JsObject] = BodyLengthLimiterConfig.configSchema

  override def transformRequest(ctx: NgTransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val config = ctx.cachedConfig(internalName)(BodyLengthLimiterConfig.format).getOrElse(BodyLengthLimiterConfig())
    val max: Long = config.maxLength.getOrElse(128 * 1024 * 1024)
    ctx.otoroshiRequest.contentLength match {
      case Some(contentLength) if config.fail && contentLength > max =>  Errors
        .craftResponseResult(
          "Request entity too large",
          Results.EntityTooLarge,
          ctx.request,
          None,
          Some("errors.failed.request.entityTooLarge"),
          duration = ctx.report.getDurationNow(),
          overhead = ctx.report.getOverheadInNow(),
          attrs = ctx.attrs,
          maybeRoute = ctx.route.some
        )
        .map(_.left)
      case _ if config.fail => {
        Right(ctx.otoroshiRequest.copy(body = ctx.otoroshiRequest.body.limitWeighted(max)(_.size))).vfuture
      }
      case _ => {
        val counter = new AtomicLong(0L)
        Right(ctx.otoroshiRequest.copy(body = ctx.otoroshiRequest.body.takeWhile { chunk =>
          val size = counter.addAndGet(chunk.size)
          size < max
        })).vfuture
      }
    }
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
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = BodyLengthLimiterConfig.configFlow
  override def configSchema: Option[JsObject] = BodyLengthLimiterConfig.configSchema

  override def transformResponse(ctx: NgTransformerResponseContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val config = ctx.cachedConfig(internalName)(BodyLengthLimiterConfig.format).getOrElse(BodyLengthLimiterConfig())
    val max: Long = config.maxLength.getOrElse(4 * 1024 * 1024)
    ctx.otoroshiResponse.contentLength match {
      case Some(contentLength) if config.fail && contentLength > max =>  Errors
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
      case _ if config.fail => {
        Right(ctx.otoroshiResponse.copy(body = ctx.otoroshiResponse.body.limitWeighted(max)(_.size))).vfuture
      }
      case _ => {
        val counter = new AtomicLong(0L)
        Right(ctx.otoroshiResponse.copy(body = ctx.otoroshiResponse.body.takeWhile { chunk =>
          val size = counter.addAndGet(chunk.size)
          size < max
        })).vfuture
      }
    }
  }
}

