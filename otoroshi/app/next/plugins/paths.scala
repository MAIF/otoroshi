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
import otoroshi.utils.http.RequestImplicits._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class NgPublicPrivatePathsConfig(
    strict: Boolean = false,
    publicPatterns: Seq[String] = Seq.empty,
    privatePatterns: Seq[String] = Seq.empty
) extends NgPluginConfig {
  def json: JsValue = NgPublicPrivatePathsConfig.format.writes(this)
}

object NgPublicPrivatePathsConfig {
  val format = new Format[NgPublicPrivatePathsConfig] {
    override def reads(json: JsValue): JsResult[NgPublicPrivatePathsConfig] = Try {
      NgPublicPrivatePathsConfig(
        privatePatterns = json.select("private_patterns").asOpt[Seq[String]].getOrElse(Seq.empty),
        publicPatterns = json.select("public_patterns").asOpt[Seq[String]].getOrElse(Seq.empty),
        strict = json.select("strict").asOpt[Boolean].getOrElse(false)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: NgPublicPrivatePathsConfig): JsValue             = Json.obj(
      "strict"           -> o.strict,
      "private_patterns" -> o.privatePatterns,
      "public_patterns"  -> o.publicPatterns
    )
  }
}

class PublicPrivatePaths extends NgAccessValidator {

  private val configReads: Reads[NgPublicPrivatePathsConfig] = NgPublicPrivatePathsConfig.format

  override def steps: Seq[NgStep]                = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean                      = false
  override def core: Boolean                               = true
  override def name: String                                = "Public/Private paths"
  override def description: Option[String]                 = "This plugin allows or forbid request based on path patterns".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgPublicPrivatePathsConfig().some
  override def isAccessAsync: Boolean                      = true

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val uri                                                                 = ctx.request.thePath
    val NgPublicPrivatePathsConfig(strict, publicPatterns, privatePatterns) =
      ctx.cachedConfig(internalName)(configReads).getOrElse(NgPublicPrivatePathsConfig())
    val isPublic                                                            =
      !privatePatterns.exists(p => otoroshi.utils.RegexPool.regex(p).matches(uri)) && publicPatterns.exists(p =>
        otoroshi.utils.RegexPool.regex(p).matches(uri)
      )
    if (isPublic) {
      NgAccess.NgAllowed.vfuture
    } else if (!isPublic && !strict && (ctx.apikey.isDefined || ctx.user.isDefined)) {
      NgAccess.NgAllowed.vfuture
    } else if (!isPublic && strict && ctx.apikey.isDefined) {
      NgAccess.NgAllowed.vfuture
    } else {
      Errors
        .craftResponseResult(
          "Not authorized",
          Results.Unauthorized,
          ctx.request,
          None,
          Some("errors.unauthorized"),
          attrs = ctx.attrs,
          maybeRoute = ctx.route.some
        )
        .map(NgAccess.NgDenied.apply)
    }
  }
}
