package otoroshi.next.plugins

import otoroshi.env.Env
import otoroshi.models.Restrictions
import otoroshi.next.plugins.api.{NgAccess, NgAccessContext, NgAccessValidator}
import otoroshi.utils.syntax.implicits.BetterSyntax

import scala.concurrent.{ExecutionContext, Future}

class RoutingRestrictions extends NgAccessValidator {
  // TODO: add name and config
  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val restrictions = Restrictions.format.reads(ctx.config).getOrElse(Restrictions(enabled = true))
    val (restrictionsNotPassing, restrictionsResponse) = restrictions.handleRestrictions(ctx.route.serviceDescriptor.id, ctx.route.serviceDescriptor.some, None, ctx.request, ctx.attrs)
    if (restrictionsNotPassing) {
      restrictionsResponse.map(r => NgAccess.NgDenied(r))
    } else {
      NgAccess.NgAllowed.vfuture
    }
  }
}
