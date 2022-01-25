package otoroshi.next.plugins

import akka.Done
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.next.plugins.api.{NgPreRouting, NgPreRoutingContext, NgPreRoutingError, NgPreRoutingErrorWithResult}
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}

class MaintenanceMode extends NgPreRouting {

  override def core: Boolean = true
  override def name: String = "Maintenance mode"
  override def description: Option[String] = "This plugin displays a maintenance page".some

  override def preRoute(ctx: NgPreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Either[NgPreRoutingError, Done]] = {
    Errors
      .craftResponseResult(
        "Service in maintenance mode",
        Results.ServiceUnavailable,
        ctx.request,
        None,
        Some("errors.service.in.maintenance"),
        duration = ctx.report.getDurationNow(),
        overhead = ctx.report.getOverheadInNow(),
        attrs = ctx.attrs,
        maybeRoute = ctx.route.some,
      ).map(r => Left(NgPreRoutingErrorWithResult(r)))
  }
}

class BuildMode extends NgPreRouting {

  override def core: Boolean = true
  override def name: String = "Build mode"
  override def description: Option[String] = "This plugin displays a build page".some

  override def preRoute(ctx: NgPreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Either[NgPreRoutingError, Done]] = {
    Errors
      .craftResponseResult(
        "Service under construction",
        Results.ServiceUnavailable,
        ctx.request,
        None,
        Some("errors.service.under.construction"),
        duration = ctx.report.getDurationNow(),
        overhead = ctx.report.getOverheadInNow(),
        attrs = ctx.attrs,
        maybeRoute = ctx.route.some,
      ).map(r => Left(NgPreRoutingErrorWithResult(r)))
  }
}
