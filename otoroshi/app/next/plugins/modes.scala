package otoroshi.next.plugins

import akka.Done
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.next.plugins.api.{NgPluginCategory, NgPluginConfig, NgPluginVisibility, NgPreRouting, NgPreRoutingContext, NgPreRoutingError, NgPreRoutingErrorWithResult, NgStep}
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}

class MaintenanceMode extends NgPreRouting {

  override def steps: Seq[NgStep] = Seq(NgStep.PreRoute)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl)
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean = true
  override def core: Boolean               = true
  override def name: String                = "Maintenance mode"
  override def description: Option[String] = "This plugin displays a maintenance page".some
  override def defaultConfigObject: Option[NgPluginConfig] = None
  override def isPreRouteAsync: Boolean    = true

  override def preRoute(
      ctx: NgPreRoutingContext
  )(implicit env: Env, ec: ExecutionContext): Future[Either[NgPreRoutingError, Done]] = {
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
        maybeRoute = ctx.route.some
      )
      .map(r => Left(NgPreRoutingErrorWithResult(r)))
  }
}

class GlobalMaintenanceMode extends NgPreRouting {

  override def steps: Seq[NgStep] = Seq(NgStep.PreRoute)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl)
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean = false
  override def core: Boolean = true
  override def name: String = "Global Maintenance mode"
  override def description: Option[String] = "This plugin displays a maintenance page for every services. Useful when 'legacy checks' are disabled on a service/globally".some
  override def isPreRouteAsync: Boolean = true
  override def defaultConfigObject: Option[NgPluginConfig] = None

  override def preRoute(
                         ctx: NgPreRoutingContext
                       )(implicit env: Env, ec: ExecutionContext): Future[Either[NgPreRoutingError, Done]] = {
    if (ctx.route.id != env.backOfficeServiceId && env.datastores.globalConfigDataStore.latest().maintenanceMode) {
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
          maybeRoute = ctx.route.some
        )
        .map(r => Left(NgPreRoutingErrorWithResult(r)))
    } else {
      Done.right.vfuture
    }
  }
}

class BuildMode extends NgPreRouting {

  override def steps: Seq[NgStep] = Seq(NgStep.PreRoute)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl)
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean = true
  override def core: Boolean               = true
  override def name: String                = "Build mode"
  override def description: Option[String] = "This plugin displays a build page".some
  override def isPreRouteAsync: Boolean    = true
  override def defaultConfigObject: Option[NgPluginConfig] = None

  override def preRoute(
      ctx: NgPreRoutingContext
  )(implicit env: Env, ec: ExecutionContext): Future[Either[NgPreRoutingError, Done]] = {
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
        maybeRoute = ctx.route.some
      )
      .map(r => Left(NgPreRoutingErrorWithResult(r)))
  }
}
