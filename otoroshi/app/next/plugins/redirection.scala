package otoroshi.next.plugins

import akka.Done
import akka.http.scaladsl.util.FastFuture
import otoroshi.env.Env
import otoroshi.models.RedirectionSettings
import otoroshi.next.plugins.api.{NgPreRouting, NgPreRoutingContext, NgPreRoutingError, NgPreRoutingErrorWithResult}
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}

class Redirection extends NgPreRouting {
  // TODO: add name and config
  override def preRoute(ctx: NgPreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Either[NgPreRoutingError, Done]] = {
    val config = RedirectionSettings.format.reads(ctx.config).getOrElse(RedirectionSettings()).copy(enabled = true)
    if (config.enabled && config.hasValidCode) {
      FastFuture
        .successful(Left(NgPreRoutingErrorWithResult(
          Results
            .Status(config.code)
            .withHeaders("Location" -> config.to)))) // TODO: use EL here : config.formattedTo(req, rawDesc, elCtx, attrs, env)
    } else {
      FastFuture.successful(Right(Done))
    }
  }
}
