package otoroshi.plugins.metrics

import akka.stream.Materializer
import env.Env
import otoroshi.script._
import play.api.mvc.{Result, Results}
import otoroshi.utils.string.Implicits._
import play.api.libs.json.{JsObject, Json}
import utils.RequestImplicits._
import utils.future.Implicits._

import scala.concurrent.{ExecutionContext, Future}

class ServiceMetrics extends RequestTransformer {

  override def name: String = "Service Metrics"

  override def defaultConfig: Option[JsObject] =
    Some(
      Json.obj(
        "ServiceMetrics" -> Json.obj(
          "accessKeyValue" -> "${config.app.health.accessKey}",
          "accessKeyQuery" -> "access_key"
        )
      )
    )

  override def description: Option[String] =
    Some(
      """This plugin expose service metrics in Otoroshi global metrics or on a special URL of the service `/.well-known/otoroshi/metrics`.
      |Metrics are exposed in json or prometheus format depending on the accept header. You can protect it with an access key defined in the configuration
      |
      |This plugin can accept the following configuration
      |
      |```json
      |{
      |  "ServiceMetrics": {
      |    "accessKeyValue": "secret", // if not defined, public access. Can be ${config.app.health.accessKey}
      |    "accessKeyQuery": "access_key"
      |  }
      |}
      |```
    """.stripMargin
    )

  override def transformRequestWithCtx(
      ctx: TransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    (ctx.rawRequest.method, ctx.rawRequest.path) match {
      case ("GET", "/.well-known/otoroshi/plugins/metrics") => {

        def result(): Future[Either[Result, HttpRequest]] = {
          val filter = Some(s"*otoroshi.service.requests.*.*.${ctx.descriptor.name.slug}*")
          if (ctx.request.accepts("application/json")) {
            Left(Results.Ok(env.metrics.jsonExport(filter)).withHeaders("Content-Type" -> "application/json")).future
          } else if (ctx.request.accepts("application/prometheus")) {
            Left(Results.Ok(env.metrics.prometheusExport(filter)).withHeaders("Content-Type" -> "text/plain")).future
          } else {
            Left(Results.Ok(env.metrics.defaultHttpFormat(filter))).future
          }
        }

        val queryName = (ctx.config \ "ServiceMetrics" \ "accessKeyQuery").asOpt[String].getOrElse("access_key")
        (ctx.config \ "ServiceMetrics" \ "accessKeyValue").asOpt[String] match {
          case None => result()
          case Some("${config.app.health.accessKey}")
              if env.healthAccessKey.isDefined && ctx.request
                .getQueryString(queryName)
                .contains(env.healthAccessKey.get) =>
            result()
          case Some(value) if ctx.request.getQueryString(queryName).contains(value) => result()
          case _                                                                    => Left(Results.Unauthorized(Json.obj("error" -> "not authorized !"))).future
        }
      }
      case _ => Right(ctx.otoroshiRequest).future
    }
  }

  override def transformResponseWithCtx(
      ctx: TransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpResponse]] = {
    val start: Long    = ctx.attrs.get(otoroshi.plugins.Keys.RequestStartKey).getOrElse(0L)
    val duration: Long = System.currentTimeMillis() - start

    env.metrics
      .counter(
        s"otoroshi.service.requests.count.total.${ctx.descriptor.name.slug}.${ctx.request.theProtocol}.${ctx.request.method
          .toLowerCase()}.${ctx.rawResponse.status}"
      )
      .inc()
    env.metrics.counter(s"otoroshi.service.requests.count.total.${ctx.descriptor.name.slug}").inc()
    env.metrics
      .counter(
        s"otoroshi.requests.count.total.${ctx.request.theProtocol}.${ctx.request.method.toLowerCase()}.${ctx.rawResponse.status}"
      )
      .inc()
    env.metrics.counter(s"otoroshi.requests.count.total").inc()

    env.metrics
      .histogram(
        s"otoroshi.service.requests.duration.seconds.${ctx.descriptor.name.slug}.${ctx.request.theProtocol}.${ctx.request.method
          .toLowerCase()}.${ctx.rawResponse.status}"
      )
      .update(duration)
    env.metrics.histogram(s"otoroshi.service.requests.duration.seconds.${ctx.descriptor.name.slug}").update(duration)
    env.metrics
      .histogram(
        s"otoroshi.requests.duration.seconds.${ctx.request.theProtocol}.${ctx.request.method.toLowerCase()}.${ctx.rawResponse.status}"
      )
      .update(duration)
    env.metrics.histogram(s"otoroshi.requests.duration.seconds").update(duration)

    Right(ctx.otoroshiResponse).future
  }

  override def transformErrorWithCtx(
      ctx: TransformerErrorContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Result] = {
    val start: Long    = ctx.attrs.get(otoroshi.plugins.Keys.RequestStartKey).getOrElse(0L)
    val duration: Long = System.currentTimeMillis() - start
    env.metrics
      .counter(
        s"otoroshi.service.requests.errors.count.total.${ctx.descriptor.name.slug}.${ctx.request.theProtocol}.${ctx.request.method
          .toLowerCase()}.${ctx.otoroshiResponse.status}"
      )
      .inc()
    env.metrics.counter(s"otoroshi.service.requests.errors.count.total.${ctx.descriptor.name.slug}").inc()
    env.metrics
      .counter(
        s"otoroshi.requests.errors.count.total.${ctx.request.theProtocol}.${ctx.request.method.toLowerCase()}.${ctx.otoroshiResponse.status}"
      )
      .inc()
    env.metrics.counter(s"otoroshi.requests.errors.count.total").inc()

    env.metrics
      .histogram(
        s"otoroshi.service.requests.errors.duration.seconds.${ctx.descriptor.name.slug}.${ctx.request.theProtocol}.${ctx.request.method
          .toLowerCase()}.${ctx.otoroshiResponse.status}"
      )
      .update(duration)
    env.metrics
      .histogram(s"otoroshi.service.requests.errors.duration.seconds.${ctx.descriptor.name.slug}")
      .update(duration)
    env.metrics
      .histogram(
        s"otoroshi.requests.errors.duration.seconds.${ctx.request.theProtocol}.${ctx.request.method.toLowerCase()}.${ctx.otoroshiResponse.status}"
      )
      .update(duration)
    env.metrics.histogram(s"otoroshi.requests.errors.duration.seconds").update(duration)
    ctx.otoroshiResult.future
  }
}
