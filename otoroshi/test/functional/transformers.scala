import akka.stream.Materializer
import env.Env
import otoroshi.script._
import play.api.mvc.Result

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}

class RequestTimeout extends RequestTransformer {

  override def transformResponseWithCtx(ctx: TransformerResponseContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpResponse]] = {
    val promise = Promise[Either[Result, HttpResponse]]
    val timeout = (ctx.config \ "RequestTimeout" \ "timeout").asOpt[Long].getOrElse(60L * 1000L).millis
    env.otoroshiScheduler.scheduleOnce(timeout) {
      promise.trySuccess(Right(ctx.otoroshiResponse))
    }
    promise.future
  }
}

new RequestTimeout
