package otoroshi.next.plugins

import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.utils.RegexPool
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits._
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}

class NgIncomingRequestValidatorAllowedDomainNames extends NgIncomingRequestValidator {

  override def steps: Seq[NgStep]                          = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.AccessControl)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Globally allowed domain names"
  override def description: Option[String]                 = "Globally allowed domain names plugin".some
  override def defaultConfigObject: Option[NgPluginConfig] = None

  override def access(ctx: NgIncomingRequestValidatorContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    ctx.config.select("domains").asOpt[Seq[String]] match {
      case None => NgAccess.NgAllowed.vfuture
      case Some(domains) => {
        val domain = ctx.request.theDomain
        val (wildcard, no_wildcard) = domains.partition(_.contains("*"))
        if (no_wildcard.contains(domain) || wildcard.exists(str => RegexPool(str).matches(domain))) {
          NgAccess.NgAllowed.vfuture
        } else {
          NgAccess.NgDenied(Results.Forbidden("")).vfuture
        }
      }
    }
  }
}

class NgIncomingRequestValidatorDeniedDomainNames extends NgIncomingRequestValidator {

  override def steps: Seq[NgStep]                          = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.AccessControl)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Globally denied domain names"
  override def description: Option[String]                 = "Globally denied domain names plugin".some
  override def defaultConfigObject: Option[NgPluginConfig] = None

  override def access(ctx: NgIncomingRequestValidatorContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    ctx.config.select("domains").asOpt[Seq[String]] match {
      case None => NgAccess.NgAllowed.vfuture
      case Some(domains) => {
        val domain = ctx.request.theDomain
        val (wildcard, no_wildcard) = domains.partition(_.contains("*"))
        if (no_wildcard.contains(domain) || wildcard.exists(str => RegexPool(str).matches(domain))) {
          NgAccess.NgDenied(Results.Forbidden("")).vfuture
        } else {
          NgAccess.NgAllowed.vfuture
        }
      }
    }
  }
}
