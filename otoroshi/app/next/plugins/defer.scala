package otoroshi.next.plugins

import akka.stream.Materializer
import ch.qos.logback.core.util.TimeUtil
import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.Result

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util._

case class NgDeferPluginConfig(
    duration: FiniteDuration = FiniteDuration(0, TimeUnit.MILLISECONDS)
) extends NgPluginConfig {
  override def json: JsValue = Json.obj(
    "duration" -> duration.toMillis
  )
}

object NgDeferPluginConfig {
  val format = new Format[NgDeferPluginConfig] {
    override def writes(o: NgDeferPluginConfig): JsValue             = o.json
    override def reads(json: JsValue): JsResult[NgDeferPluginConfig] = Try {
      NgDeferPluginConfig(
        duration = json.select("duration").asOpt[Long].getOrElse(0L).millis
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
  }
}

class NgDeferPlugin extends NgRequestTransformer {

  override def name: String                                = "Defer Responses"
  override def description: Option[String]                 =
    "This plugin will expect a `X-Defer` header or a `defer` query param and defer the response according to the value in milliseconds.\nThis plugin is some kind of inside joke as one a our customer ask us to make slower apis.".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgDeferPluginConfig().some
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Other)
  override def steps: Seq[NgStep]                          = Seq(NgStep.TransformRequest)

  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val config        = ctx.cachedConfig(internalName)(NgDeferPluginConfig.format).getOrElse(NgDeferPluginConfig())
    val headerTimeout = ctx.request.headers.get("X-Defer").map(_.toLong)
    val queryTimeout  = ctx.request.getQueryString("defer").map(_.toLong)
    val timeout       = headerTimeout.orElse(queryTimeout).getOrElse(config.duration.toMillis)
    val elapsed       = System.currentTimeMillis() - ctx.attrs
      .get(otoroshi.plugins.Keys.RequestTimestampKey)
      .getOrElse(DateTime.now())
      .getMillis
    if (timeout - elapsed <= 0L) {
      ctx.otoroshiRequest.rightf
    } else {
      val promise = Promise[Either[Result, NgPluginHttpRequest]]
      env.otoroshiScheduler.scheduleOnce((timeout - elapsed).millis) {
        promise.trySuccess(Right(ctx.otoroshiRequest))
      }
      promise.future
    }
  }
}
