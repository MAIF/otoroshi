package otoroshi.next.plugins

import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.next.plugins.api.{
  NgAccess,
  NgAccessContext,
  NgAccessValidator,
  NgPluginCategory,
  NgPluginConfig,
  NgPluginVisibility,
  NgStep
}
import otoroshi.utils.syntax.implicits.{BetterJsValue, BetterSyntax}
import play.api.libs.json.{Format, JsError, JsResult, JsSuccess, JsValue, Json}
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class ReadOnlyCalls extends NgAccessValidator {

  private val methods = Seq("get", "head", "options")

  override def steps: Seq[NgStep]                = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl, NgPluginCategory.Standard)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Read only requests"
  override def description: Option[String]                 = "This plugin verifies the current request only reads data".some
  override def isAccessAsync: Boolean                      = true
  override def defaultConfigObject: Option[NgPluginConfig] = None

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

case class NgAllowedMethodsConfig(allowed: Seq[String] = Seq.empty, forbidden: Seq[String] = Seq.empty)
    extends NgPluginConfig {
  def json: JsValue = NgAllowedMethodsConfig.format.writes(this)
}

object NgAllowedMethodsConfig {
  val format = new Format[NgAllowedMethodsConfig] {
    override def reads(json: JsValue): JsResult[NgAllowedMethodsConfig] = Try {
      NgAllowedMethodsConfig(
        allowed = json.select("allowed").asOpt[Seq[String]].getOrElse(Seq.empty).map(_.toLowerCase),
        forbidden = json.select("forbidden").asOpt[Seq[String]].getOrElse(Seq.empty).map(_.toLowerCase)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: NgAllowedMethodsConfig): JsValue             =
      Json.obj("allowed" -> o.allowed, "forbidden" -> o.forbidden)
  }
}

class AllowHttpMethods extends NgAccessValidator {

  override def steps: Seq[NgStep]                = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl, NgPluginCategory.Standard)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Allowed HTTP methods"
  override def description: Option[String]                 =
    "This plugin verifies the current request only uses allowed http methods".some
  override def isAccessAsync: Boolean                      = true
  override def defaultConfigObject: Option[NgPluginConfig] = NgAllowedMethodsConfig().some

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val method                                                     = ctx.request.method.toLowerCase
    val NgAllowedMethodsConfig(allowed_methods, forbidden_methods) =
      ctx.cachedConfig(internalName)(NgAllowedMethodsConfig.format).getOrElse(NgAllowedMethodsConfig())
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
