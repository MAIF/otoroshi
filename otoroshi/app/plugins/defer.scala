package otoroshi.plugins.defer

import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import env.Env
import org.joda.time.DateTime
import otoroshi.script.{RequestTransformer, TransformerRequestContext, _}
import play.api.libs.json.{JsObject, Json}
import play.api.mvc.Result

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}

class DeferPlugin extends RequestTransformer {

  override def name: String = "Defer Responses"

  override def defaultConfig: Option[JsObject] =
    Some(
      Json.obj(
        "DeferPlugin" -> Json.obj(
          "defaultDefer" -> 0,
        )
      )
    )

  override def description: Option[String] =
    Some(
      """This plugin will expect a `X-Defer` header or a `defer` query param and defer the response according to the value in milliseconds.
        |This plugin is some kind of inside joke as one a our customer ask us to make slower apis.
        |
        |This plugin can accept the following configuration
        |
        |```json
        |{
        |  "DeferPlugin": {
        |    "defaultDefer": 0 // default defer in millis
        |  }
        |}
        |```
      """.stripMargin
    )

  override def transformRequestWithCtx(
      ctx: TransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    val config         = ctx.configFor("DeferPlugin")
    val defaultTimeout = (config \ "defaultDefer").asOpt[Long].getOrElse(0L)
    val headerTimeout  = ctx.request.headers.get("X-Defer").map(_.toLong)
    val queryTimeout   = ctx.request.getQueryString("defer").map(_.toLong)
    val timeout       = headerTimeout.orElse(queryTimeout).getOrElse(defaultTimeout)
    val elapsed = System.currentTimeMillis() - ctx.attrs
      .get(otoroshi.plugins.Keys.RequestTimestampKey)
      .getOrElse(DateTime.now())
      .getMillis
    if (timeout - elapsed <= 0L) {
      FastFuture.successful(Right(ctx.otoroshiRequest))
    } else {
      val promise = Promise[Either[Result, HttpRequest]]
      env.otoroshiScheduler.scheduleOnce((timeout - elapsed).millis) {
        promise.trySuccess(Right(ctx.otoroshiRequest))
      }
      promise.future
    }
  }
}
