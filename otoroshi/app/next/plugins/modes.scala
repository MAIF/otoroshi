package otoroshi.next.plugins

import akka.Done
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.next.plugins.api.{NgPreRouting, NgPreRoutingContext, NgPreRoutingError, NgPreRoutingErrorWithResult}
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}

class MaintenanceMode extends NgPreRouting {
  // TODO: add name and config
  override def preRoute(ctx: NgPreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Either[NgPreRoutingError, Done]] = {
    Errors
      .craftResponseResult(
        "Service in maintenance mode",
        Results.ServiceUnavailable,
        ctx.request,
        ctx.route.serviceDescriptor.some,
        Some("errors.service.in.maintenance"),
        duration = ctx.report.getDurationNow(), // TODO: checks if it's the rights move
        overhead = ctx.report.getOverheadInNow(), // TODO: checks if it's the rights move
        attrs = ctx.attrs
      ).map(r => Left(NgPreRoutingErrorWithResult(r)))
  }
}

class BuildMode extends NgPreRouting {
  // TODO: add name and config
  override def preRoute(ctx: NgPreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Either[NgPreRoutingError, Done]] = {
    Errors
      .craftResponseResult(
        "Service under construction",
        Results.ServiceUnavailable,
        ctx.request,
        ctx.route.serviceDescriptor.some,
        Some("errors.service.under.construction"),
        duration = ctx.report.getDurationNow(), // TODO: checks if it's the rights move
        overhead = ctx.report.getOverheadInNow(), // TODO: checks if it's the rights move
        attrs = ctx.attrs
      ).map(r => Left(NgPreRoutingErrorWithResult(r)))
  }
}
