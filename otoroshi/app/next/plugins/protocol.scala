package otoroshi.next.plugins

import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.next.plugins.api.{NgAccess, NgAccessContext, NgAccessValidator}
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}

class DisableHttp10 extends NgAccessValidator {
  // TODO: add name and config
  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    if (ctx.request.version == "HTTP/1.0") {
      Errors
        .craftResponseResult(
          "HTTP/1.0 not allowed",
          Results.ServiceUnavailable,
          ctx.request,
          None,
          Some("errors.http.1_0.not.allowed"),
          duration = ctx.report.getDurationNow(), // TODO: checks if it's the rights move
          overhead = ctx.report.getOverheadInNow(), // TODO: checks if it's the rights move
          attrs = ctx.attrs,
          maybeRoute = ctx.route.some,
        ).map(r => NgAccess.NgDenied(r))
    } else {
      NgAccess.NgAllowed.vfuture
    }
  }
}
