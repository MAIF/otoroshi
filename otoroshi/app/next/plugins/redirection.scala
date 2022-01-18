package otoroshi.next.plugins

import akka.Done
import otoroshi.el.RedirectionExpressionLanguage
import otoroshi.env.Env
import otoroshi.models.{RedirectionSettings, Restrictions}
import otoroshi.next.plugins.api.{NgPreRouting, NgPreRoutingContext, NgPreRoutingError, NgPreRoutingErrorWithResult}
import otoroshi.utils.syntax.implicits.{BetterJsReadable, BetterSyntax}
import play.api.libs.json.{JsObject, Reads}
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}

class Redirection extends NgPreRouting {

  private val configReads: Reads[RedirectionSettings] = RedirectionSettings.format

  override def core: Boolean = true
  override def name: String = "Redirection"
  override def description: Option[String] = "This plugin redirects the current request elsewhere".some
  override def defaultConfig: Option[JsObject] = RedirectionSettings(enabled = true).toJson.asObject.-("enabled").some

  override def preRoute(ctx: NgPreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Either[NgPreRoutingError, Done]] = {
    val config = ctx.cachedConfig(internalName)(configReads).getOrElse(RedirectionSettings(enabled = true))
    if (config.enabled && config.hasValidCode) {
      val to = RedirectionExpressionLanguage(config.to, ctx.request.some, ctx.route.serviceDescriptor.some, None, None, ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).get, ctx.attrs, env)
      Left(NgPreRoutingErrorWithResult(
        Results
          .Status(config.code)
          .withHeaders("Location" -> to))).vfuture
    } else {
      Right(Done).vfuture
    }
  }
}
