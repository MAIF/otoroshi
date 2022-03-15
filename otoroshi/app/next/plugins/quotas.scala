package otoroshi.next.plugins

import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.models.RemainingQuotas
import otoroshi.next.plugins.api._
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.libs.typedmap.TypedKey
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}

case class GlobalPerIpAddressThrottlingQuotas(within: Boolean, secCalls: Long, maybeQuota: Option[Long])

object GlobalPerIpAddressThrottlingQuotas {
  val key = TypedKey[GlobalPerIpAddressThrottlingQuotas]("otoroshi.next.plugins.GlobalPerIpAddressThrottlingQuotas")
}

class GlobalPerIpAddressThrottling extends NgAccessValidator {

  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl)
  override def steps: Seq[NgStep] = Seq(NgStep.ValidateAccess)
  override def multiInstance: Boolean = false
  override def core: Boolean = true

  override def name: String                = "Global per ip address throttling "
  override def description: Option[String] = "Enforce global per ip address throttling. Useful when 'legacy checks' are disabled on a service/globally".some

  override def defaultConfigObject: Option[NgPluginConfig] = None

  def errorResult(
    ctx: NgAccessContext,
    status: Results.Status,
    message: String,
    code: String
  )(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    Errors
      .craftResponseResult(
        message,
        status,
        ctx.request,
        None,
        Some(code),
        duration = ctx.report.getDurationNow(),
        overhead = ctx.report.getOverheadInNow(),
        attrs = ctx.attrs,
        maybeRoute = ctx.route.some
      )
      .map(e => NgAccess.NgDenied(e))
  }

  def applyQuotas(ctx: NgAccessContext, quotas: GlobalPerIpAddressThrottlingQuotas)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val globalConfig = env.datastores.globalConfigDataStore.latest()
    val quota = quotas.maybeQuota.getOrElse(globalConfig.perIpThrottlingQuota)
    if (quotas.secCalls > (quota * 10L)) {
      errorResult(ctx, Results.TooManyRequests, "[IP] You performed too much requests", "errors.too.much.requests")
    } else {
      NgAccess.NgAllowed.vfuture
    }
  }

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val remoteAddress = ctx.request.theIpAddress
    ctx.attrs.get(GlobalPerIpAddressThrottlingQuotas.key) match {
      case Some(quotas) => applyQuotas(ctx, quotas)
      case None => env.datastores.globalConfigDataStore.quotasValidationFor(remoteAddress).flatMap {
        case (within, secCalls, maybeQuota) => {
          val quotas = GlobalPerIpAddressThrottlingQuotas(within, secCalls, maybeQuota)
          ctx.attrs.put(GlobalPerIpAddressThrottlingQuotas.key -> quotas)
          applyQuotas(ctx, quotas)
        }
      }
    }
  }
}

class GlobalThrottling extends NgAccessValidator {

  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl)
  override def steps: Seq[NgStep] = Seq(NgStep.ValidateAccess)
  override def multiInstance: Boolean = false
  override def core: Boolean = true
  override def defaultConfigObject: Option[NgPluginConfig] = None

  override def name: String                = "Global throttling "
  override def description: Option[String] = "Enforce global throttling. Useful when 'legacy checks' are disabled on a service/globally".some

  def errorResult(
                   ctx: NgAccessContext,
                   status: Results.Status,
                   message: String,
                   code: String
                 )(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    Errors
      .craftResponseResult(
        message,
        status,
        ctx.request,
        None,
        Some(code),
        duration = ctx.report.getDurationNow(),
        overhead = ctx.report.getOverheadInNow(),
        attrs = ctx.attrs,
        maybeRoute = ctx.route.some
      )
      .map(e => NgAccess.NgDenied(e))
  }

  def applyQuotas(ctx: NgAccessContext, quotas: GlobalPerIpAddressThrottlingQuotas)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    if (!quotas.within) {
      errorResult(ctx, Results.TooManyRequests, "[GLOBAL] You performed too much requests", "errors.too.much.requests")
    } else {
      NgAccess.NgAllowed.vfuture
    }
  }

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val remoteAddress = ctx.request.theIpAddress
    ctx.attrs.get(GlobalPerIpAddressThrottlingQuotas.key) match {
      case Some(quotas) => applyQuotas(ctx, quotas)
      case None => env.datastores.globalConfigDataStore.quotasValidationFor(remoteAddress).flatMap {
        case (within, secCalls, maybeQuota) => {
          val quotas = GlobalPerIpAddressThrottlingQuotas(within, secCalls, maybeQuota)
          ctx.attrs.put(GlobalPerIpAddressThrottlingQuotas.key -> quotas)
          applyQuotas(ctx, quotas)
        }
      }
    }
  }
}

class ApikeyQuotas extends NgAccessValidator {

  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl)
  override def steps: Seq[NgStep] = Seq(NgStep.ValidateAccess)
  override def multiInstance: Boolean = false
  override def core: Boolean = true
  override def defaultConfigObject: Option[NgPluginConfig] = None

  override def name: String                = "Apikey quotas"
  override def description: Option[String] = "Increments quotas for the currents apikey. Useful when 'legacy checks' are disabled on a service/globally or when apikey are extracted in a custom fashion.".some

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    // increments calls for apikey
    ctx.attrs
      .get(otoroshi.plugins.Keys.ApiKeyKey)
      .map(_.updateQuotas())
      .getOrElse(RemainingQuotas().vfuture)
      .map { value =>
      ctx.attrs.put(otoroshi.plugins.Keys.ApiKeyRemainingQuotasKey -> value)
        NgAccess.NgAllowed
    }
  }
}