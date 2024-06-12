package otoroshi.next.plugins

import org.joda.time.DateTime
import otoroshi.el.GlobalExpressionLanguage
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.models.RemainingQuotas
import otoroshi.next.models.NgRoute
import otoroshi.next.plugins.api._
import otoroshi.utils.http.RequestImplicits._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.libs.typedmap.TypedKey
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class GlobalPerIpAddressThrottlingQuotas(within: Boolean, secCalls: Long, maybeQuota: Option[Long])

object GlobalPerIpAddressThrottlingQuotas {
  val key = TypedKey[GlobalPerIpAddressThrottlingQuotas]("otoroshi.next.plugins.GlobalPerIpAddressThrottlingQuotas")
}

class GlobalPerIpAddressThrottling extends NgAccessValidator {

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl, NgPluginCategory.Classic)
  override def steps: Seq[NgStep]                = Seq(NgStep.ValidateAccess)
  override def multiInstance: Boolean            = false
  override def core: Boolean                     = true

  override def name: String                = "Global per ip address throttling "
  override def description: Option[String] =
    "Enforce global per ip address throttling. Useful when 'legacy checks' are disabled on a service/globally".some

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

  def applyQuotas(ctx: NgAccessContext, quotas: GlobalPerIpAddressThrottlingQuotas)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[NgAccess] = {
    val globalConfig = env.datastores.globalConfigDataStore.latest()
    val quota        = quotas.maybeQuota.getOrElse(globalConfig.perIpThrottlingQuota)
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
      case None         =>
        env.datastores.globalConfigDataStore.quotasValidationFor(remoteAddress).flatMap {
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

  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.AccessControl)
  override def steps: Seq[NgStep]                          = Seq(NgStep.ValidateAccess)
  override def multiInstance: Boolean                      = false
  override def core: Boolean                               = true
  override def defaultConfigObject: Option[NgPluginConfig] = None

  override def name: String                = "Global throttling "
  override def description: Option[String] =
    "Enforce global throttling. Useful when 'legacy checks' are disabled on a service/globally".some

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

  def applyQuotas(ctx: NgAccessContext, quotas: GlobalPerIpAddressThrottlingQuotas)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[NgAccess] = {
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
      case None         =>
        env.datastores.globalConfigDataStore.quotasValidationFor(remoteAddress).flatMap {
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

  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.AccessControl)
  override def steps: Seq[NgStep]                          = Seq(NgStep.ValidateAccess)
  override def multiInstance: Boolean                      = false
  override def core: Boolean                               = true
  override def defaultConfigObject: Option[NgPluginConfig] = None

  override def name: String                = "Apikey quotas"
  override def description: Option[String] =
    "Increments quotas for the currents apikey. Useful when 'legacy checks' are disabled on a service/globally or when apikey are extracted in a custom fashion.".some

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

case class NgServiceQuotasConfig(
    throttlingQuota: Long = RemainingQuotas.MaxValue,
    dailyQuota: Long = RemainingQuotas.MaxValue,
    monthlyQuota: Long = RemainingQuotas.MaxValue
) extends NgPluginConfig {
  override def json: JsValue = Json.obj(
    "throttling_quota" -> throttlingQuota,
    "daily_quota"      -> dailyQuota,
    "monthly_quota"    -> monthlyQuota
  )
}

object NgServiceQuotasConfig {
  val format = new Format[NgServiceQuotasConfig] {
    override def writes(o: NgServiceQuotasConfig): JsValue             = o.json
    override def reads(json: JsValue): JsResult[NgServiceQuotasConfig] = Try {
      NgServiceQuotasConfig(
        throttlingQuota = json.select("throttling_quota").asOpt[Long].getOrElse(RemainingQuotas.MaxValue),
        dailyQuota = json.select("daily_quota").asOpt[Long].getOrElse(RemainingQuotas.MaxValue),
        monthlyQuota = json.select("monthly_quota").asOpt[Long].getOrElse(RemainingQuotas.MaxValue)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
  }
}

class NgServiceQuotas extends NgAccessValidator {

  override def name: String                                = "Public quotas"
  override def description: Option[String]                 = "This plugin will enforce public quotas on the current route".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgServiceQuotasConfig().some
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Other)
  override def steps: Seq[NgStep]                          = Seq(NgStep.ValidateAccess)

  private def totalCallsKey(name: String)(implicit env: Env): String =
    s"${env.storageRoot}:plugins:services-public-quotas:global:$name"

  private def dailyQuotaKey(name: String)(implicit env: Env): String =
    s"${env.storageRoot}:plugins:services-public-quotas:daily:$name"

  private def monthlyQuotaKey(name: String)(implicit env: Env): String =
    s"${env.storageRoot}:plugins:services-public-quotas:monthly:$name"

  private def throttlingKey(name: String)(implicit env: Env): String =
    s"${env.storageRoot}:plugins:services-public-quotas:second:$name"

  private def updateQuotas(route: NgRoute, qconf: NgServiceQuotasConfig, increment: Long = 1L)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Unit] = {
    val dayEnd     = DateTime.now().secondOfDay().withMaximumValue()
    val toDayEnd   = dayEnd.getMillis - DateTime.now().getMillis
    val monthEnd   = DateTime.now().dayOfMonth().withMaximumValue().secondOfDay().withMaximumValue()
    val toMonthEnd = monthEnd.getMillis - DateTime.now().getMillis
    env.clusterAgent.incrementApi(route.id, increment)
    for {
      _            <- env.datastores.rawDataStore.incrby(totalCallsKey(route.id), increment)
      secCalls     <- env.datastores.rawDataStore.incrby(throttlingKey(route.id), increment)
      secTtl       <- env.datastores.rawDataStore.pttl(throttlingKey(route.id)).filter(_ > -1).recoverWith { case _ =>
                        env.datastores.rawDataStore.pexpire(throttlingKey(route.id), env.throttlingWindow * 1000)
                      }
      dailyCalls   <- env.datastores.rawDataStore.incrby(dailyQuotaKey(route.id), increment)
      dailyTtl     <- env.datastores.rawDataStore.pttl(dailyQuotaKey(route.id)).filter(_ > -1).recoverWith { case _ =>
                        env.datastores.rawDataStore.pexpire(dailyQuotaKey(route.id), toDayEnd.toInt)
                      }
      monthlyCalls <- env.datastores.rawDataStore.incrby(monthlyQuotaKey(route.id), increment)
      monthlyTtl   <- env.datastores.rawDataStore.pttl(monthlyQuotaKey(route.id)).filter(_ > -1).recoverWith { case _ =>
                        env.datastores.rawDataStore.pexpire(monthlyQuotaKey(route.id), toMonthEnd.toInt)
                      }
    } yield ()
  }

  private def withingQuotas(route: NgRoute, qconf: NgServiceQuotasConfig)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Boolean] =
    for {
      sec <- withinThrottlingQuota(route, qconf)
      day <- withinDailyQuota(route, qconf)
      mon <- withinMonthlyQuota(route, qconf)
    } yield sec && day && mon

  private def withinThrottlingQuota(
      route: NgRoute,
      qconf: NgServiceQuotasConfig
  )(implicit ec: ExecutionContext, env: Env): Future[Boolean] =
    env.datastores.rawDataStore
      .get(throttlingKey(route.id))
      .map(_.map(_.utf8String.toLong).getOrElse(0L) <= (qconf.throttlingQuota * env.throttlingWindow))

  private def withinDailyQuota(route: NgRoute, qconf: NgServiceQuotasConfig)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Boolean] =
    env.datastores.rawDataStore
      .get(dailyQuotaKey(route.id))
      .map(_.map(_.utf8String.toLong).getOrElse(0L) < qconf.dailyQuota)

  private def withinMonthlyQuota(route: NgRoute, qconf: NgServiceQuotasConfig)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Boolean] =
    env.datastores.rawDataStore
      .get(monthlyQuotaKey(route.id))
      .map(_.map(_.utf8String.toLong).getOrElse(0L) < qconf.monthlyQuota)

  def forbidden(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    Errors
      .craftResponseResult(
        "forbidden",
        Results.Forbidden,
        ctx.request,
        None,
        None,
        duration = ctx.report.getDurationNow(),
        overhead = ctx.report.getOverheadInNow(),
        attrs = ctx.attrs,
        maybeRoute = ctx.route.some
      )
      .map(r => NgAccess.NgDenied(r))
  }

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config = ctx.cachedConfig(internalName)(NgServiceQuotasConfig.format).getOrElse(NgServiceQuotasConfig())
    withingQuotas(ctx.route, config).flatMap {
      case true  => updateQuotas(ctx.route, config).map(_ => NgAccess.NgAllowed)
      case false => forbidden(ctx)
    }
  }
}

case class NgCustomQuotasConfig(
    dailyQuota: Long = RemainingQuotas.MaxValue,
    monthlyQuota: Long = RemainingQuotas.MaxValue,
    perRoute: Boolean = true,
    global: Boolean = false,
    group: Option[String] = None,
    expression: String = "${req.ip}"
) extends NgPluginConfig {
  override def json: JsValue = NgCustomQuotasConfig.format.writes(this)
  def computeExpression(ctx: NgAccessContext, env: Env): String = {
    GlobalExpressionLanguage.apply(
      value = expression,
      req = ctx.request.some,
      service = None,
      route = ctx.route.some,
      apiKey = ctx.apikey,
      user = ctx.user,
      context = Map.empty,
      attrs = ctx.attrs,
      env = env
    )
  }
  def computeGroup(ctx: NgAccessContext, env: Env): String = {
    group match {
      case Some(g)          =>
        GlobalExpressionLanguage.apply(
          value = g,
          req = ctx.request.some,
          service = None,
          route = ctx.route.some,
          apiKey = ctx.apikey,
          user = ctx.user,
          context = Map.empty,
          attrs = ctx.attrs,
          env = env
        )
      case None if perRoute => ctx.route.id
      case _                => "global"
    }
  }
}

object NgCustomQuotasConfig {
  val format = new Format[NgCustomQuotasConfig] {
    override def writes(o: NgCustomQuotasConfig): JsValue             = Json.obj(
      "per_route"     -> o.perRoute,
      "global"        -> o.global,
      "group"         -> o.group.map(JsString.apply).getOrElse(JsNull).asValue,
      "expression"    -> o.expression,
      "daily_quota"   -> o.dailyQuota,
      "monthly_quota" -> o.monthlyQuota
    )
    override def reads(json: JsValue): JsResult[NgCustomQuotasConfig] = Try {
      NgCustomQuotasConfig(
        perRoute = json.select("perRoute").asOpt[Boolean].getOrElse(true),
        global = json.select("global").asOpt[Boolean].getOrElse(false),
        group = json.select("group").asOpt[String],
        expression = json.select("expression").asOpt[String].getOrElse("${req.ip}"),
        dailyQuota = json.select("daily_quota").asOpt[Long].getOrElse(RemainingQuotas.MaxValue),
        monthlyQuota = json.select("monthly_quota").asOpt[Long].getOrElse(RemainingQuotas.MaxValue)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
  }
}

object NgCustomQuotas {

  private def dailyQuotaKey(name: String, group: String)(implicit env: Env): String =
    s"${env.storageRoot}:plugins:custom-quotas:${group}:daily:$name"

  private def monthlyQuotaKey(name: String, group: String)(implicit env: Env): String =
    s"${env.storageRoot}:plugins:custom-quotas:${group}:monthly:$name"

  def updateQuotas(expr: String, group: String, increment: Long = 1L)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Unit] = {
    val dayEnd     = DateTime.now().secondOfDay().withMaximumValue()
    val toDayEnd   = dayEnd.getMillis - DateTime.now().getMillis
    val monthEnd   = DateTime.now().dayOfMonth().withMaximumValue().secondOfDay().withMaximumValue()
    val toMonthEnd = monthEnd.getMillis - DateTime.now().getMillis
    for {
      dailyCalls   <- env.datastores.rawDataStore.incrby(dailyQuotaKey(expr, group), increment)
      dailyTtl     <- env.datastores.rawDataStore.pttl(dailyQuotaKey(expr, group)).filter(_ > -1).recoverWith { case _ =>
                        env.datastores.rawDataStore.pexpire(dailyQuotaKey(expr, group), toDayEnd.toInt)
                      }
      monthlyCalls <- env.datastores.rawDataStore.incrby(monthlyQuotaKey(expr, group), increment)
      monthlyTtl   <-
        env.datastores.rawDataStore.pttl(monthlyQuotaKey(expr, group)).filter(_ > -1).recoverWith { case _ =>
          env.datastores.rawDataStore.pexpire(monthlyQuotaKey(expr, group), toMonthEnd.toInt)
        }
    } yield ()
  }
}

class NgCustomQuotas extends NgAccessValidator {

  override def name: String                                = "Custom quotas"
  override def description: Option[String]                 =
    "This plugin will enforce quotas on the current route based on whatever you want".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgCustomQuotasConfig().some
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Other)
  override def steps: Seq[NgStep]                          = Seq(NgStep.ValidateAccess)

  private def updateQuotas(ctx: NgAccessContext, qconf: NgCustomQuotasConfig, increment: Long = 1L)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Unit] = {
    val group = qconf.computeGroup(ctx, env)
    val expr  = qconf.computeExpression(ctx, env)
    env.clusterAgent.incrementCustomQuota(expr, group, increment)
    NgCustomQuotas.updateQuotas(expr, group, increment)
  }

  private def withingQuotas(ctx: NgAccessContext, qconf: NgCustomQuotasConfig)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Boolean] = {
    for {
      day <- withinDailyQuota(ctx, qconf)
      mon <- withinMonthlyQuota(ctx, qconf)
    } yield day && mon
  }

  private def withinDailyQuota(ctx: NgAccessContext, qconf: NgCustomQuotasConfig)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Boolean] = {
    env.datastores.rawDataStore
      .get(NgCustomQuotas.dailyQuotaKey(qconf.computeExpression(ctx, env), qconf.computeGroup(ctx, env)))
      .map(_.map(_.utf8String.toLong).getOrElse(0L) < qconf.dailyQuota)
  }

  private def withinMonthlyQuota(ctx: NgAccessContext, qconf: NgCustomQuotasConfig)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Boolean] = {
    env.datastores.rawDataStore
      .get(NgCustomQuotas.monthlyQuotaKey(qconf.computeExpression(ctx, env), qconf.computeGroup(ctx, env)))
      .map(_.map(_.utf8String.toLong).getOrElse(0L) < qconf.monthlyQuota)
  }

  def forbidden(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    Errors
      .craftResponseResult(
        "forbidden",
        Results.Forbidden,
        ctx.request,
        None,
        None,
        duration = ctx.report.getDurationNow(),
        overhead = ctx.report.getOverheadInNow(),
        attrs = ctx.attrs,
        maybeRoute = ctx.route.some
      )
      .map(r => NgAccess.NgDenied(r))
  }

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config = ctx.cachedConfig(internalName)(NgCustomQuotasConfig.format).getOrElse(NgCustomQuotasConfig())
    withingQuotas(ctx, config).flatMap {
      case true  => updateQuotas(ctx, config).map(_ => NgAccess.NgAllowed)
      case false => forbidden(ctx)
    }
  }
}

case class NgCustomThrottlingConfig(
    throttlingQuota: Long = 100L,
    perRoute: Boolean = true,
    global: Boolean = false,
    group: Option[String] = None,
    expression: String = "${req.ip}"
) extends NgPluginConfig {
  override def json: JsValue = NgCustomThrottlingConfig.format.writes(this)
  def computeExpression(ctx: NgAccessContext, env: Env): String = {
    GlobalExpressionLanguage.apply(
      value = expression,
      req = ctx.request.some,
      service = None,
      route = ctx.route.some,
      apiKey = ctx.apikey,
      user = ctx.user,
      context = Map.empty,
      attrs = ctx.attrs,
      env = env
    )
  }
  def computeGroup(ctx: NgAccessContext, env: Env): String = {
    group match {
      case Some(g)          =>
        GlobalExpressionLanguage.apply(
          value = g,
          req = ctx.request.some,
          service = None,
          route = ctx.route.some,
          apiKey = ctx.apikey,
          user = ctx.user,
          context = Map.empty,
          attrs = ctx.attrs,
          env = env
        )
      case None if perRoute => ctx.route.id
      case _                => "global"
    }
  }
}

object NgCustomThrottlingConfig {
  val format = new Format[NgCustomThrottlingConfig] {
    override def writes(o: NgCustomThrottlingConfig): JsValue             = Json.obj(
      "per_route"        -> o.perRoute,
      "global"           -> o.global,
      "group"            -> o.group.map(JsString.apply).getOrElse(JsNull).asValue,
      "expression"       -> o.expression,
      "throttling_quota" -> o.throttlingQuota
    )
    override def reads(json: JsValue): JsResult[NgCustomThrottlingConfig] = Try {
      NgCustomThrottlingConfig(
        perRoute = json.select("perRoute").asOpt[Boolean].getOrElse(true),
        global = json.select("global").asOpt[Boolean].getOrElse(false),
        group = json.select("group").asOpt[String],
        expression = json.select("expression").asOpt[String].getOrElse("${req.ip}"),
        throttlingQuota = json.select("throttling_quota").asOpt[Long].getOrElse(100L)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
  }
}

object NgCustomThrottling {

  def throttlingKey(name: String, group: String)(implicit env: Env): String =
    s"${env.storageRoot}:plugins:custom-throttling:${group}:second:$name"

  def updateQuotas(expr: String, group: String, increment: Long = 1L)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Unit] = {
    for {
      secCalls <- env.datastores.rawDataStore.incrby(throttlingKey(expr, group), increment)
      secTtl   <- env.datastores.rawDataStore.pttl(throttlingKey(expr, group)).filter(_ > -1).recoverWith { case _ =>
                    env.datastores.rawDataStore.pexpire(throttlingKey(expr, group), env.throttlingWindow * 1000)
                  }
    } yield ()
  }
}

class NgCustomThrottling extends NgAccessValidator {

  override def name: String                                = "Custom throttling"
  override def description: Option[String]                 =
    "This plugin will enforce throttling on the current route based on whatever you want".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgCustomThrottlingConfig().some
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Other)
  override def steps: Seq[NgStep]                          = Seq(NgStep.ValidateAccess)

  private def updateQuotas(ctx: NgAccessContext, qconf: NgCustomThrottlingConfig, increment: Long = 1L)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Unit] = {
    val group = qconf.computeGroup(ctx, env)
    val expr  = qconf.computeExpression(ctx, env)
    env.clusterAgent.incrementCustomThrottling(expr, group, increment)
    NgCustomThrottling.updateQuotas(expr, group, increment)
  }

  private def withingQuotas(
      ctx: NgAccessContext,
      qconf: NgCustomThrottlingConfig
  )(implicit ec: ExecutionContext, env: Env): Future[Boolean] = {
    env.datastores.rawDataStore
      .get(NgCustomThrottling.throttlingKey(qconf.computeExpression(ctx, env), qconf.computeGroup(ctx, env)))
      .map(_.map(_.utf8String.toLong).getOrElse(0L) <= (qconf.throttlingQuota * env.throttlingWindow))
  }

  def forbidden(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    Errors
      .craftResponseResult(
        "forbidden",
        Results.Forbidden,
        ctx.request,
        None,
        None,
        duration = ctx.report.getDurationNow(),
        overhead = ctx.report.getOverheadInNow(),
        attrs = ctx.attrs,
        maybeRoute = ctx.route.some
      )
      .map(r => NgAccess.NgDenied(r))
  }

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config = ctx.cachedConfig(internalName)(NgCustomThrottlingConfig.format).getOrElse(NgCustomThrottlingConfig())
    withingQuotas(ctx, config).flatMap {
      case true  => updateQuotas(ctx, config).map(_ => NgAccess.NgAllowed)
      case false => forbidden(ctx)
    }
  }
}
