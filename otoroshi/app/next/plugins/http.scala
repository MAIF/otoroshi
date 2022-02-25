package otoroshi.next.plugins

import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.next.plugins.api.{NgAccess, NgAccessContext, NgAccessValidator, NgPluginCategory, NgPluginVisibility, NgStep}
import otoroshi.utils.syntax.implicits.{BetterJsValue, BetterSyntax}
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}

class ReadOnlyCalls extends NgAccessValidator {

  private val methods = Seq("get", "head", "options")

  override def steps: Seq[NgStep] = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl)
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand

  override def core: Boolean               = true
  override def name: String                = "Read only requests"
  override def description: Option[String] = "This plugin verifies the current request only reads data".some
  override def isAccessAsync: Boolean      = true

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val method = ctx.request.method.toLowerCase
    if (!methods.contains(method)) {
      Errors
        .craftResponseResult(
          s"Method not allowed",
          Results.MethodNotAllowed,
          ctx.request,
          None,
          Some("errors.method.not.allowed"),
          attrs = ctx.attrs,
          maybeRoute = ctx.route.some
        )
        .map(r => NgAccess.NgDenied(r))
    } else {
      NgAccess.NgAllowed.vfuture
    }
  }
}

class AllowHttpMethods extends NgAccessValidator {

  override def steps: Seq[NgStep] = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl)
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand

  override def core: Boolean               = true
  override def name: String                = "Allowed HTTP methods"
  override def description: Option[String] =
    "This plugin verifies the current request only uses allowed http methods".some
  override def isAccessAsync: Boolean      = true

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val method            = ctx.request.method.toLowerCase
    val allowed_methods   = ctx.config.select("allowed").asOpt[Seq[String]].getOrElse(Seq.empty).map(_.toLowerCase)
    val forbidden_methods = ctx.config.select("forbidden").asOpt[Seq[String]].getOrElse(Seq.empty).map(_.toLowerCase)
    if (!allowed_methods.contains(method) || forbidden_methods.contains(method)) {
      Errors
        .craftResponseResult(
          s"Method not allowed",
          Results.MethodNotAllowed,
          ctx.request,
          None,
          Some("errors.method.not.allowed"),
          attrs = ctx.attrs,
          maybeRoute = ctx.route.some
        )
        .map(r => NgAccess.NgDenied(r))
    } else {
      NgAccess.NgAllowed.vfuture
    }
  }
}
