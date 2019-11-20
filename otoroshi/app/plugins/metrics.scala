package otoroshi.plugins.metrics

import akka.stream.Materializer
import env.Env
import otoroshi.script._
import play.api.mvc.Result

import otoroshi.utils.string.Implicits._
import utils.RequestImplicits._
import utils.future.Implicits._

import scala.concurrent.{ExecutionContext, Future}

class ServiceMetrics extends RequestTransformer {

  override def transformResponseWithCtx(ctx: TransformerResponseContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpResponse]] = {
    val start: Long = ctx.attrs.get(otoroshi.plugins.Keys.RequestStartKey).getOrElse(0L)
    val duration: Long = System.currentTimeMillis() - start

    env.metrics.counter(s"otoroshi.service.requests.total.${ctx.descriptor.name.slug}.${ctx.request.theProtocol}.${ctx.request.method.toLowerCase()}.${ctx.rawResponse.status}").inc()
    env.metrics.counter(s"otoroshi.requests.total.${ctx.request.theProtocol}.${ctx.request.method.toLowerCase()}.${ctx.rawResponse.status}").inc()
    env.metrics.counter(s"otoroshi.all.requests.total").inc()

    env.metrics.histogram(s"otoroshi.service.requests.duration.seconds.${ctx.descriptor.name.slug}.${ctx.request.theProtocol}.${ctx.request.method.toLowerCase()}.${ctx.rawResponse.status}").update(duration)
    env.metrics.histogram(s"otoroshi.requests.duration.seconds.${ctx.request.theProtocol}.${ctx.request.method.toLowerCase()}.${ctx.rawResponse.status}").update(duration)
    env.metrics.histogram(s"otoroshi.all.requests.duration.seconds").update(duration)

    Right(ctx.otoroshiResponse).future
  }
}
