package otoroshi.plugins.metrics

import akka.stream.Materializer
import env.Env
import otoroshi.script._
import play.api.mvc.{Result, Results}
import otoroshi.utils.string.Implicits._
import utils.RequestImplicits._
import utils.future.Implicits._

import scala.concurrent.{ExecutionContext, Future}

class ServiceMetrics extends RequestTransformer {

  override def transformRequestWithCtx(ctx: TransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    (ctx.rawRequest.method, ctx.rawRequest.path) match {
      case ("GET",  "/.well-known/otoroshi/metrics") => {
        val filter = Some(s"*otoroshi.service.requests.*.*.${ctx.descriptor.name.slug}*")
        if (ctx.request.accepts("application/json")) {
          Left(Results.Ok(env.metrics.jsonExport(filter)).withHeaders("Content-Type" -> "application/json")).future
        } else if (ctx.request.accepts("application/prometheus")) {
          Left(Results.Ok(env.metrics.prometheusExport(filter)).withHeaders("Content-Type" -> "text/plain")).future
        } else {
          Left(Results.Ok(env.metrics.defaultHttpFormat(filter))).future
        }
      }
      case _ => Right(ctx.otoroshiRequest).future
    }
  }

  override def transformResponseWithCtx(ctx: TransformerResponseContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpResponse]] = {
    val start: Long = ctx.attrs.get(otoroshi.plugins.Keys.RequestStartKey).getOrElse(0L)
    val duration: Long = System.currentTimeMillis() - start

    env.metrics.counter(s"otoroshi.service.requests.count.total.${ctx.descriptor.name.slug}.${ctx.request.theProtocol}.${ctx.request.method.toLowerCase()}.${ctx.rawResponse.status}").inc()
    env.metrics.counter(s"otoroshi.service.requests.count.total.${ctx.descriptor.name.slug}").inc()
    env.metrics.counter(s"otoroshi.requests.count.total.${ctx.request.theProtocol}.${ctx.request.method.toLowerCase()}.${ctx.rawResponse.status}").inc()
    env.metrics.counter(s"otoroshi.requests.count.total").inc()

    env.metrics.histogram(s"otoroshi.service.requests.duration.seconds.${ctx.descriptor.name.slug}.${ctx.request.theProtocol}.${ctx.request.method.toLowerCase()}.${ctx.rawResponse.status}").update(duration)
    env.metrics.histogram(s"otoroshi.service.requests.duration.seconds.${ctx.descriptor.name.slug}").update(duration)
    env.metrics.histogram(s"otoroshi.requests.duration.seconds.${ctx.request.theProtocol}.${ctx.request.method.toLowerCase()}.${ctx.rawResponse.status}").update(duration)
    env.metrics.histogram(s"otoroshi.requests.duration.seconds").update(duration)

    Right(ctx.otoroshiResponse).future
  }
}
