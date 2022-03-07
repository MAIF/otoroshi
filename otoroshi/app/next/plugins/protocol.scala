package otoroshi.next.plugins

import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.next.plugins.api.{NgAccess, NgAccessContext, NgAccessValidator, NgPluginCategory, NgPluginVisibility, NgStep}
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}

class DisableHttp10 extends NgAccessValidator {

  override def steps: Seq[NgStep] = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.TrafficControl)
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean = false
  override def core: Boolean               = true
  override def name: String                = "Disable HTTP/1.0"
  override def description: Option[String] = "This plugin forbids HTTP/1.0 requests".some
  override def isAccessAsync: Boolean      = true

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    if (ctx.request.version == "HTTP/1.0") {
      Errors
        .craftResponseResult(
          "HTTP/1.0 not allowed",
          Results.ServiceUnavailable,
          ctx.request,
          None,
          Some("errors.http.1_0.not.allowed"),
          duration = ctx.report.getDurationNow(),
          overhead = ctx.report.getOverheadInNow(),
          attrs = ctx.attrs,
          maybeRoute = ctx.route.some
        )
        .map(r => NgAccess.NgDenied(r))
    } else {
      NgAccess.NgAllowed.vfuture
    }
  }
}
