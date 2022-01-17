package otoroshi.next.plugins

import akka.Done
import otoroshi.env.Env
import otoroshi.models.RedirectionSettings
import otoroshi.next.plugins.api.{NgPreRouting, NgPreRoutingContext, NgPreRoutingError, NgPreRoutingErrorWithResult}
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}

class Redirection extends NgPreRouting {
  // TODO: add name and config
  override def preRoute(ctx: NgPreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Either[NgPreRoutingError, Done]] = {
    val config = RedirectionSettings.format.reads(ctx.config).getOrElse(RedirectionSettings()).copy(enabled = true)
    if (config.enabled && config.hasValidCode) {
      Left(NgPreRoutingErrorWithResult(
          Results
            .Status(config.code)
            .withHeaders("Location" -> config.to))).vfuture // TODO: use EL here : config.formattedTo(req, rawDesc, elCtx, attrs, env)
    } else {
      Right(Done).vfuture
    }
  }
}
