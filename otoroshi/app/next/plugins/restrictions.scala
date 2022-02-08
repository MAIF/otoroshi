package otoroshi.next.plugins

import otoroshi.env.Env
import otoroshi.models.Restrictions
import otoroshi.next.plugins.api.{NgAccess, NgAccessContext, NgAccessValidator}
import otoroshi.utils.syntax.implicits.{BetterJsReadable, BetterSyntax}
import play.api.libs.json.{JsObject, Reads}

import scala.concurrent.{ExecutionContext, Future}

class RoutingRestrictions extends NgAccessValidator {

  private val configReads: Reads[Restrictions] = Restrictions.format

  override def core: Boolean = true
  override def name: String = "Routing Restrictions"
  override def description: Option[String] = "This plugin apply routing restriction `method domain/path` on the current request/route".some
  override def defaultConfig: Option[JsObject] = Restrictions(enabled = true).json.asObject.-("enabled").some

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val restrictions = ctx.cachedConfig(internalName)(configReads).getOrElse(Restrictions(enabled = true))
    val (restrictionsNotPassing, restrictionsResponse) = restrictions.handleRestrictions(ctx.route.serviceDescriptor.id, ctx.route.serviceDescriptor.some, None, ctx.request, ctx.attrs)
    if (restrictionsNotPassing) {
      restrictionsResponse.map(r => NgAccess.NgDenied(r))
    } else {
      NgAccess.NgAllowed.vfuture
    }
  }
}
