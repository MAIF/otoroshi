package otoroshi.next.plugins

import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.util.FastFuture.EnhancedFuture
import org.joda.time.DateTime
import otoroshi.el.GlobalExpressionLanguage
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.models.{ApiKey, PrivateAppsUser, RemainingQuotas}
import otoroshi.next.models.NgRoute
import otoroshi.next.plugins.api._
import otoroshi.security.IdGenerator
import otoroshi.utils.TypedMap
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.syntax.implicits.{BetterJsValue, BetterJsValueReader, BetterSyntax}
import play.api.libs.json._
import play.api.mvc.RequestHeader
import play.api.mvc.Results.TooManyRequests

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class LocalBucket(var tokens: Double = 0, var lastRefillMs: Long)

case class LocalTokensBucketStrategyConfig(
    bucketKey: String = IdGenerator.uuid,
    capacity: Long = 300,
    refillRequestIntervalMs: Long = 50,
    refillRequestedTokens: Long = 50,
    quota: AllowedQuota = AllowedQuota()
) extends ThrottlingStrategyConfig
    with NgPluginConfig {
  def id = "LocalTokensBucketStrategyConfig"

  override def json: JsValue = Json.obj(
    "id"                      -> id,
    "bucketKey"               -> bucketKey,
    "capacity"                -> capacity,
    "refillRequestIntervalMs" -> refillRequestIntervalMs,
    "refillRequestedTokens"   -> refillRequestedTokens,
    "quota"                   -> quota.json
  )

  def refillRatePerSecond: Double =
    (refillRequestedTokens.toDouble * 1000.0) / refillRequestIntervalMs.toDouble

  override def fmt: Format[ThrottlingStrategyConfig] =
    LocalTokensBucketStrategyConfig.format.asInstanceOf[Format[ThrottlingStrategyConfig]]
}

object LocalTokensBucketStrategyConfig {
  val format = new Format[LocalTokensBucketStrategyConfig] {
    override def reads(json: JsValue): JsResult[LocalTokensBucketStrategyConfig] = Try {
      LocalTokensBucketStrategyConfig(
        bucketKey = json.selectAsOptString("bucketKey").getOrElse(IdGenerator.uuid),
        capacity = json.selectAsOptLong("capacity").getOrElse(300),
        refillRequestIntervalMs = json.selectAsOptLong("refillRequestIntervalMs").getOrElse(50),
        refillRequestedTokens = json.selectAsOptLong("refillRequestedTokens").getOrElse(50),
        quota = json.select("quota").asOpt(AllowedQuota.fmt).getOrElse(AllowedQuota())
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(value)     => JsSuccess(value)
    }
    override def writes(o: LocalTokensBucketStrategyConfig): JsValue             = o.json
  }
}

case class LocalTokensBucketStrategy(bucketId: String, config: LocalTokensBucketStrategyConfig)(implicit env: Env)
    extends ThrottlingStrategy {
  implicit val ec: ExecutionContext = env.otoroshiExecutionContext

  private val lastLeaderRequestTimeMs = new AtomicReference[Option[Long]](None)
  private val bucketRef               = new AtomicReference[LocalBucket](
    LocalBucket(tokens = config.capacity.toDouble, lastRefillMs = System.currentTimeMillis())
  )

  private def askForRefill(): Future[Unit] = {
    val currentTimeMs = System.currentTimeMillis()

    val shouldRefill = lastLeaderRequestTimeMs.get() match {
      case Some(lastMs) => (currentTimeMs - lastMs) >= config.refillRequestIntervalMs
      case None         => true
    }

    if (shouldRefill) {
      lastLeaderRequestTimeMs.set(Some(currentTimeMs))

      bucketRef.updateAndGet { oldBucket =>
        val timeElapsedMs  = currentTimeMs - oldBucket.lastRefillMs
        val timeElapsedSec = timeElapsedMs / 1000.0

        val tokensToAdd     = timeElapsedSec * config.refillRatePerSecond
        val newBucketTokens = Math.min(config.capacity, oldBucket.tokens + tokensToAdd)

        if (tokensToAdd > 0) {
          oldBucket.copy(tokens = newBucketTokens, lastRefillMs = currentTimeMs)
        } else {
          oldBucket
        }
      }
    }

    FastFuture.successful(())
  }

  private def getDailyAndMonthlyQuotas(key: String, allowedQuotas: AllowedQuota)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[QuotaState] = {
    val redisCli = env.datastores.redis

    val dayEnd   = DateTime.now().secondOfDay().withMaximumValue()
    val monthEnd = DateTime.now().dayOfMonth().withMaximumValue().secondOfDay().withMaximumValue()

    for {
      dailyCalls   <- redisCli.get(dailyQuotaKey(key)).fast.map(_.map(_.utf8String.toLong).getOrElse(0L))
      monthlyCalls <- redisCli.get(monthlyQuotaKey(key)).fast.map(_.map(_.utf8String.toLong).getOrElse(0L))
    } yield {
      val daily   = Quota(
        limit = allowedQuotas.daily,
        consumed = dailyCalls,
        resetsAt = dayEnd.getMillis
      )
      val monthly = Quota(
        limit = allowedQuotas.monthly,
        consumed = monthlyCalls,
        resetsAt = monthEnd.getMillis
      )

      QuotaState(
        window = Quota(),
        daily = daily,
        monthly = monthly
      )
    }
  }

  override def check(key: String, allowedQuotas: AllowedQuota)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[ThrottlingResult] = {
    getDailyAndMonthlyQuotas(key, allowedQuotas)
      .map(state => {
        val tokensAfter     = bucketRef.get().tokens
        val hadEnoughTokens = tokensAfter > 0 && state.daily.withinLimit && state.monthly.withinLimit

        ThrottlingResult(
          allowed = hadEnoughTokens,
          quotas = state
        )
      })
  }

  override def checkAndIncrement(
      key: String,
      increment: Long,
      allowedQuotas: AllowedQuota,
      expirationSeconds: Int
  )(implicit env: Env, ec: ExecutionContext): Future[ThrottlingResult] = {
    askForRefill().flatMap { _ =>
      getDailyAndMonthlyQuotas(key, allowedQuotas)
        .flatMap(currentState => {
          val tokensBefore = bucketRef.getAndUpdate { current =>
            if (current.tokens >= increment) {
              current.copy(tokens = current.tokens - increment)
            } else {
              current
            }
          }

          val hadEnoughTokens =
            tokensBefore.tokens >= increment && currentState.daily.withinLimit && currentState.monthly.withinLimit

          if (hadEnoughTokens) {
            super
              .incrementDailyAndMonthly(key, increment)
              .map { case (dailyCalls, monthyCalls) =>
                ThrottlingResult(
                  allowed = true,
                  quotas = currentState.copy(
                    daily = currentState.daily.copy(consumed = dailyCalls),
                    monthly = currentState.monthly.copy(consumed = monthyCalls)
                  )
                )
              }
          } else
            ThrottlingResult(allowed = false, quotas = currentState).future
        })
    }
  }

  override def reset(key: String, expirationSeconds: Int)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[QuotaState] = {
    val redisCli = env.datastores.redis

    val dayEnd     = DateTime.now().secondOfDay().withMaximumValue()
    val toDayEnd   = dayEnd.getMillis - DateTime.now().getMillis
    val monthEnd   = DateTime.now().dayOfMonth().withMaximumValue().secondOfDay().withMaximumValue()
    val toMonthEnd = monthEnd.getMillis - DateTime.now().getMillis

    for {
      _ <- redisCli.set(dailyQuotaKey(key), "0")
      _ <- redisCli.pttl(dailyQuotaKey(key)).filter(_ > -1).recoverWith { case _ =>
             redisCli.expire(dailyQuotaKey(key), (toDayEnd / 1000).toInt)
           }
      _ <- redisCli.set(monthlyQuotaKey(key), "0")
      _ <- redisCli.pttl(monthlyQuotaKey(key)).filter(_ > -1).recoverWith { case _ =>
             redisCli.expire(monthlyQuotaKey(key), (toMonthEnd / 1000).toInt)
           }
    } yield QuotaState(
      window = Quota(limit = config.quota.window, consumed = 0, resetsAt = 0),
      daily = Quota(limit = config.quota.daily, consumed = 0, resetsAt = dayEnd.getMillis),
      monthly = Quota(limit = config.quota.monthly, consumed = 0, resetsAt = monthEnd.getMillis)
    )
  }
}

case class LegacyThrottlingStrategy(clientId: String, config: LegacyThrottlingStrategyConfig)
    extends ThrottlingStrategy {

  override def totalCallsKey(name: String)(implicit env: Env): String   = s"${env.storageRoot}:apikey:quotas:global:$name"
  override def dailyQuotaKey(name: String)(implicit env: Env): String   = s"${env.storageRoot}:apikey:quotas:daily:$name"
  override def monthlyQuotaKey(name: String)(implicit env: Env): String =
    s"${env.storageRoot}:apikey:quotas:monthly:$name"
  override def throttlingKey(name: String)(implicit env: Env): String   = s"${env.storageRoot}:apikey:quotas:second:$name"
}

case class FixedWindowStrategyConfig(
    bucketKey: Option[String] = None,
    windowDurationMs: Long = 10000L,
    quota: AllowedQuota = AllowedQuota()
) extends ThrottlingStrategyConfig
    with NgPluginConfig {
  def id = "FixedWindowStrategyConfig"

  override def json: JsValue                         =
    Json.obj("id" -> id, "quota" -> quota.json, "windowDurationMs" -> windowDurationMs, "bucketKey" -> bucketKey)

  override def fmt: Format[ThrottlingStrategyConfig] =
    FixedWindowStrategyConfig.format.asInstanceOf[Format[ThrottlingStrategyConfig]]
}

object FixedWindowStrategyConfig {
  val format = new Format[FixedWindowStrategyConfig] {
    override def reads(json: JsValue): JsResult[FixedWindowStrategyConfig] = Try {
      FixedWindowStrategyConfig(
        windowDurationMs = json.selectAsOptLong("windowDurationMs").getOrElse(10000L),
        quota = json.select("quota").as(AllowedQuota.fmt),
        bucketKey = json.selectAsOptString("bucketKey")
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(value)     => JsSuccess(value)
    }
    override def writes(o: FixedWindowStrategyConfig): JsValue             = o.json
  }
}

case class FixedWindowStrategy(bucketId: String, config: FixedWindowStrategyConfig) extends ThrottlingStrategy {

  private case class FixedWindowBucket(
      windowStart: Long,
      count: Long
  )

  private val bucketRef = new AtomicReference[FixedWindowBucket](
    FixedWindowBucket(windowStart = System.currentTimeMillis(), count = 0)
  )

  private def getDailyAndMonthlyQuotas(key: String, allowedQuotas: AllowedQuota)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[QuotaState] = {
    val redisCli = env.datastores.redis

    val dayEnd   = DateTime.now().secondOfDay().withMaximumValue()
    val monthEnd = DateTime.now().dayOfMonth().withMaximumValue().secondOfDay().withMaximumValue()

    for {
      dailyCalls   <- redisCli.get(dailyQuotaKey(key)).fast.map(_.map(_.utf8String.toLong).getOrElse(0L))
      monthlyCalls <- redisCli.get(monthlyQuotaKey(key)).fast.map(_.map(_.utf8String.toLong).getOrElse(0L))
    } yield {
      val daily   = Quota(
        limit = allowedQuotas.daily,
        consumed = dailyCalls,
        resetsAt = dayEnd.getMillis
      )
      val monthly = Quota(
        limit = allowedQuotas.monthly,
        consumed = monthlyCalls,
        resetsAt = monthEnd.getMillis
      )

      QuotaState(
        window = Quota(),
        daily = daily,
        monthly = monthly
      )
    }
  }

  override def check(key: String, allowedQuotas: AllowedQuota)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[ThrottlingResult] = {
    getDailyAndMonthlyQuotas(key, allowedQuotas)
      .map(state => {
        val tokensAfter     = bucketRef.get().count
        val hadEnoughTokens = tokensAfter > 0 && state.daily.withinLimit && state.monthly.withinLimit

        ThrottlingResult(
          allowed = hadEnoughTokens,
          quotas = state
        )
      })
  }

  override def checkAndIncrement(key: String, increment: Long, allowedQuotas: AllowedQuota, expirationSeconds: Int)(
      implicit
      env: Env,
      ec: ExecutionContext
  ): Future[ThrottlingResult] = {
    val now = System.currentTimeMillis()

    val before = bucketRef.getAndUpdate { current =>
      if (now - current.windowStart >= config.windowDurationMs) {
        FixedWindowBucket(windowStart = now, count = 0)
      } else if (current.count < config.quota.window) {
        current.copy(count = current.count + 1)
      } else {
        current
      }
    }

    val dayEnd   = DateTime.now().secondOfDay().withMaximumValue()
    val monthEnd = DateTime.now().dayOfMonth().withMaximumValue().secondOfDay().withMaximumValue()

    val allowed = before.count < config.quota.window

    if (allowed) {
      super
        .incrementDailyAndMonthly(key, increment)
        .map { case (dailyCalls, monthyCalls) =>
          ThrottlingResult(
            allowed = true,
            quotas = QuotaState(
              window = Quota(
                limit = config.quota.window,
                consumed = before.count + increment,
                resetsAt = now + Math.max(0, (config.windowDurationMs - now - before.windowStart))
              ),
              daily = Quota(
                limit = config.quota.daily,
                consumed = dailyCalls,
                resetsAt = dayEnd.getMillis
              ),
              monthly = Quota(
                limit = config.quota.monthly,
                consumed = monthyCalls,
                resetsAt = monthEnd.getMillis
              )
            )
          )
        }
    } else {
      quotas(key, expirationSeconds)
        .map(quotas =>
          ThrottlingResult(
            allowed = false,
            quotas = quotas.copy(
              window = Quota(
                limit = config.quota.window,
                consumed = before.count,
                resetsAt = now + Math.max(0, config.windowDurationMs - now - before.windowStart)
              )
            )
          )
        )
    }
  }

}

case class LegacyThrottlingStrategyConfig(quota: AllowedQuota = AllowedQuota())
    extends ThrottlingStrategyConfig
    with NgPluginConfig {
  def id = "LegacyThrottlingStrategyConfig"

  override def json: JsValue = Json.obj("quota" -> quota.json, "id" -> id)

  override def fmt: Format[ThrottlingStrategyConfig] =
    LegacyThrottlingStrategyConfig.format.asInstanceOf[Format[ThrottlingStrategyConfig]]
}

object LegacyThrottlingStrategyConfig {
  val format = new Format[LegacyThrottlingStrategyConfig] {
    override def reads(json: JsValue): JsResult[LegacyThrottlingStrategyConfig] = Try {
      LegacyThrottlingStrategyConfig(
        quota = json.select("quota").as(AllowedQuota.fmt)
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(value)     => JsSuccess(value)
    }
    override def writes(o: LegacyThrottlingStrategyConfig): JsValue             = o.json
  }
}

class LocalTokenBucket extends NgAccessValidator {

  override def steps: Seq[NgStep]                = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean      = true
  override def core: Boolean               = true
  override def name: String                = "Local Token Bucket"
  override def description: Option[String] =
    "Applies a token bucket strategy to smoothly limit traffic while allowing controlled bursts.".some

  override def defaultConfigObject: Option[NgPluginConfig] = LocalTokensBucketStrategyConfig().some

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config = ctx
      .cachedConfig(internalName)(LocalTokensBucketStrategyConfig.format)
      .getOrElse(LocalTokensBucketStrategyConfig())

    val key =
      RateLimiterUtils.getKey(config.bucketKey, ctx.request.some, ctx.attrs, ctx.route.some, ctx.apikey, ctx.user)

    val strategy = env.rateLimiter.getOrCreate(
      key,
      req = ctx.request.some,
      attrs = ctx.attrs,
      route = ctx.route.some,
      apiKey = ctx.apikey,
      user = ctx.user,
      throttlingStrategy = config.some
    )

    strategy
      .checkAndIncrement(key, 1, config.quota.copy(window = config.capacity), expirationSeconds = env.throttlingWindow)
      .flatMap { throttlingResult =>
        if (!throttlingResult.allowed)
          Errors
            .craftResponseResult(
              "Too much requests",
              TooManyRequests,
              ctx.request,
              None,
              None,
              duration = ctx.report.getDurationNow(),
              overhead = ctx.report.getOverheadInNow(),
              attrs = ctx.attrs,
              maybeRoute = ctx.route.some
            )
            .map(e => NgAccess.NgDenied(e))
        else {
          NgAccess.NgAllowed.vfuture
        }
      }
  }
}

class FixedWindow extends NgAccessValidator {

  override def steps: Seq[NgStep]                = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean      = true
  override def core: Boolean               = true
  override def name: String                = "Fixed Window"
  override def description: Option[String] =
    "Fixed Window Throttling is a rate-limiting strategy that restricts each user to a maximum of M requests within a fixed time window (for example, 100 requests per minute).".some

  override def defaultConfigObject: Option[NgPluginConfig] = FixedWindowStrategyConfig().some

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config = ctx
      .cachedConfig(internalName)(FixedWindowStrategyConfig.format)
      .getOrElse(FixedWindowStrategyConfig())

    val key =
      RateLimiterUtils.getKey(config.bucketKey, ctx.request.some, ctx.attrs, ctx.route.some, ctx.apikey, ctx.user)

    val strategy = env.rateLimiter.getOrCreate(
      key,
      req = ctx.request.some,
      attrs = ctx.attrs,
      route = ctx.route.some,
      apiKey = ctx.apikey,
      user = ctx.user,
      throttlingStrategy = config.some
    )

    strategy
      .checkAndIncrement(key, 1, config.quota.copy(window = config.capacity), expirationSeconds = env.throttlingWindow)
      .flatMap { throttlingResult =>
        if (!throttlingResult.allowed)
          Errors
            .craftResponseResult(
              "Too much requests",
              TooManyRequests,
              ctx.request,
              None,
              None,
              duration = ctx.report.getDurationNow(),
              overhead = ctx.report.getOverheadInNow(),
              attrs = ctx.attrs,
              maybeRoute = ctx.route.some
            )
            .map(e => NgAccess.NgDenied(e))
        else {
          NgAccess.NgAllowed.vfuture
        }
      }
  }
}

case class ThrottlingResult(
    allowed: Boolean,
    quotas: QuotaState
)

trait ThrottlingStrategyConfig {
  def id: String
  def json: JsValue
  def quota: AllowedQuota
  def fmt: Format[ThrottlingStrategyConfig]
}

object ThrottlingStrategyConfig {
  val fmt = new Format[ThrottlingStrategyConfig] {

    override def reads(json: JsValue): JsResult[ThrottlingStrategyConfig] = {
      json match {
        case JsNull => JsError("null value")
        case value  =>
          value.selectAsOptString("id") match {
            case Some("LocalTokensBucketStrategyConfig") => LocalTokensBucketStrategyConfig.format.reads(value)
            case Some("LegacyThrottlingStrategyConfig")  => LegacyThrottlingStrategyConfig.format.reads(value)
            case Some("FixedWindowStrategyConfig")       => FixedWindowStrategyConfig.format.reads(value)
            case None                                    => JsError("unknown type")
          }
      }
    }

    override def writes(o: ThrottlingStrategyConfig): JsValue = o.json
  }
}

case class AllowedQuota(
    window: Long = Long.MaxValue,
    daily: Long = Long.MaxValue,
    monthly: Long = Long.MaxValue
) {
  def json: JsValue = Json.obj(
    "window"  -> window,
    "daily"   -> daily,
    "monthly" -> monthly
  )
}

object AllowedQuota {
  def fmt = new Format[AllowedQuota] {

    override def reads(json: JsValue): JsResult[AllowedQuota] = Try {
      AllowedQuota(
        window = json.selectAsOptLong("window").getOrElse(Long.MaxValue),
        daily = json.selectAsOptLong("daily").getOrElse(Long.MaxValue),
        monthly = json.selectAsOptLong("monthly").getOrElse(Long.MaxValue)
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(value)     => JsSuccess(value)
    }

    override def writes(o: AllowedQuota): JsValue = o.json
  }
}

case class QuotaState(
    window: Quota = Quota.unlimited,
    daily: Quota = Quota.unlimited,
    monthly: Quota = Quota.unlimited
) {
  def withinLimits: Boolean     = window.withinLimit && daily.withinLimit && monthly.withinLimit
  def legacy(): RemainingQuotas = RemainingQuotas(
    authorizedCallsPerWindow = window.limit,
    throttlingCallsPerWindow = window.consumed,
    remainingCallsPerWindow = window.remaining,
    authorizedCallsPerDay = daily.limit,
    currentCallsPerDay = daily.consumed,
    remainingCallsPerDay = daily.remaining,
    authorizedCallsPerMonth = monthly.limit,
    currentCallsPerMonth = monthly.consumed,
    remainingCallsPerMonth = monthly.remaining
  )
}

case class Quota(
    limit: Long = Long.MaxValue,
    consumed: Long = Long.MaxValue,
    resetsAt: Long = Long.MaxValue
) {
  def remaining: Long      = Math.max(0, limit - consumed)
  def withinLimit: Boolean = consumed < (limit + 1)
  def exceeded: Boolean    = consumed > limit
}

object Quota {
  val unlimited: Quota = Quota(Long.MaxValue, 0, Long.MaxValue)
}

trait ThrottlingStrategy {
  def throttlingKey(name: String)(implicit env: Env): String =
    s"${env.storageRoot}:ratelimiter:quotas:window:$name"

  def dailyQuotaKey(name: String)(implicit env: Env): String =
    s"${env.storageRoot}:ratelimiter:quotas:daily:$name"

  def monthlyQuotaKey(name: String)(implicit env: Env): String =
    s"${env.storageRoot}:ratelimiter:quotas:monthly:$name"

  def totalCallsKey(name: String)(implicit env: Env): String =
    s"${env.storageRoot}:ratelimiter:quotas:global:$name"

  def incrementDailyAndMonthly(key: String, increment: Long)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[(Long, Long)] = {
    val redisCli = env.datastores.redis

    val dayEnd     = DateTime.now().secondOfDay().withMaximumValue()
    val toDayEnd   = dayEnd.getMillis - DateTime.now().getMillis
    val monthEnd   = DateTime.now().dayOfMonth().withMaximumValue().secondOfDay().withMaximumValue()
    val toMonthEnd = monthEnd.getMillis - DateTime.now().getMillis

    for {
      dailyCalls <- redisCli.incrby(dailyQuotaKey(key), increment)
      _          <- redisCli.pttl(dailyQuotaKey(key)).flatMap {
                      case -1 => redisCli.expire(dailyQuotaKey(key), (toDayEnd / 1000).toInt)
                      case _  => Future.successful(())
                    }

      monthlyCalls <- redisCli.incrby(monthlyQuotaKey(key), increment)
      _            <- redisCli.pttl(monthlyQuotaKey(key)).flatMap {
                        case -1 => redisCli.expire(monthlyQuotaKey(key), (toMonthEnd / 1000).toInt)
                        case _  => Future.successful(())
                      }
    } yield {
      (dailyCalls, monthlyCalls)
    }
  }

  def checkAndIncrement(
      key: String,
      increment: Long,
      allowedQuotas: AllowedQuota,
      expirationSeconds: Int
  )(implicit env: Env, ec: ExecutionContext): Future[ThrottlingResult] = {
    val redisCli = env.datastores.redis

    // Calculate reset timestamps
    val now      = System.currentTimeMillis()
    val dayEnd   = DateTime.now().secondOfDay().withMaximumValue()
    val monthEnd = DateTime.now().dayOfMonth().withMaximumValue().secondOfDay().withMaximumValue()

    env.clusterAgent.incrementApi(key, increment)

    for {
      secCalls  <- redisCli.incrby(throttlingKey(key), increment)
      windowTTL <- redisCli.pttl(throttlingKey(key)).flatMap {
                     case -1  =>
                       redisCli.expire(throttlingKey(key), expirationSeconds).map(_ => expirationSeconds * 1000L)
                     case ttl => Future.successful(ttl)
                   }

      dailyAndMonthlyCalls <- incrementDailyAndMonthly(key, increment)

      _ <- redisCli.incrby(totalCallsKey(key), increment)
    } yield {
      val state = QuotaState(
        window = Quota(
          limit = allowedQuotas.window,
          consumed = secCalls,
          resetsAt = now + windowTTL
        ),
        daily = Quota(
          limit = allowedQuotas.daily,
          consumed = dailyAndMonthlyCalls._1,
          resetsAt = dayEnd.getMillis
        ),
        monthly = Quota(
          limit = allowedQuotas.monthly,
          consumed = dailyAndMonthlyCalls._2,
          resetsAt = monthEnd.getMillis
        )
      )

      ThrottlingResult(
        allowed = state.withinLimits,
        quotas = state
      )
    }
  }

  def quotas(key: String, expirationSeconds: Int)(implicit ec: ExecutionContext, env: Env): Future[QuotaState] = {
    val redisCli = env.datastores.redis

    val dayEnd   = DateTime.now().secondOfDay().withMaximumValue()
    val monthEnd = DateTime.now().dayOfMonth().withMaximumValue().secondOfDay().withMaximumValue()
    val now      = System.currentTimeMillis()

    for {
      throttlingCallsPerWindow <- redisCli.get(throttlingKey(key)).fast.map(_.map(_.utf8String.toLong).getOrElse(0L))
      dailyCalls               <- redisCli.get(dailyQuotaKey(key)).fast.map(_.map(_.utf8String.toLong).getOrElse(0L))
      monthlyCalls             <- redisCli.get(monthlyQuotaKey(key)).fast.map(_.map(_.utf8String.toLong).getOrElse(0L))
      windowTTL                <- redisCli.pttl(throttlingKey(key)).flatMap {
                                    case -1  =>
                                      redisCli.expire(throttlingKey(key), expirationSeconds).map(_ => expirationSeconds * 1000L)
                                    case ttl => Future.successful(ttl)
                                  }
    } yield {
      QuotaState(
        window = Quota(
          limit = config.quota.window,
          consumed = throttlingCallsPerWindow,
          resetsAt = now + windowTTL
        ),
        daily = Quota(
          limit = config.quota.daily,
          consumed = dailyCalls,
          resetsAt = dayEnd.getMillis
        ),
        monthly = Quota(
          limit = config.quota.monthly,
          consumed = monthlyCalls,
          resetsAt = monthEnd.getMillis
        )
      )
    }
  }

  def check(key: String, allowedQuotas: AllowedQuota)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[ThrottlingResult] = {
    val redisCli = env.datastores.redis

    // Calculate reset timestamps
    val now      = System.currentTimeMillis()
    val dayEnd   = DateTime.now().secondOfDay().withMaximumValue()
    val monthEnd = DateTime.now().dayOfMonth().withMaximumValue().secondOfDay().withMaximumValue()

    for {
      throttlingCallsPerWindow <- redisCli.get(throttlingKey(key)).fast.map(_.map(_.utf8String.toLong).getOrElse(0L))
      windowTTL                <- redisCli.pttl(throttlingKey(key)).fast.map(_.max(0L))
      dailyCalls               <- redisCli.get(dailyQuotaKey(key)).fast.map(_.map(_.utf8String.toLong).getOrElse(0L))
      monthlyCalls             <- redisCli.get(monthlyQuotaKey(key)).fast.map(_.map(_.utf8String.toLong).getOrElse(0L))
    } yield {
      val state = QuotaState(
        window = Quota(
          limit = allowedQuotas.window,
          consumed = throttlingCallsPerWindow,
          resetsAt = now + windowTTL
        ),
        daily = Quota(
          limit = allowedQuotas.daily,
          consumed = dailyCalls,
          resetsAt = dayEnd.getMillis
        ),
        monthly = Quota(
          limit = allowedQuotas.monthly,
          consumed = monthlyCalls,
          resetsAt = monthEnd.getMillis
        )
      )

      ThrottlingResult(
        allowed = state.withinLimits,
        quotas = state
      )
    }
  }

  def reset(key: String, expirationSeconds: Int)(implicit env: Env, ec: ExecutionContext): Future[QuotaState] = {
    val redisCli = env.datastores.redis

    val now        = System.currentTimeMillis()
    val dayEnd     = DateTime.now().secondOfDay().withMaximumValue()
    val toDayEnd   = dayEnd.getMillis - DateTime.now().getMillis
    val monthEnd   = DateTime.now().dayOfMonth().withMaximumValue().secondOfDay().withMaximumValue()
    val toMonthEnd = monthEnd.getMillis - DateTime.now().getMillis

    for {
      windowTTL <- redisCli.pttl(throttlingKey(key)).flatMap {
                     case -1  =>
                       redisCli.expire(throttlingKey(key), expirationSeconds).map(_ => expirationSeconds * 1000L)
                     case ttl => Future.successful(ttl)
                   }
      _         <- redisCli.set(totalCallsKey(key), "0")
      _         <- redisCli.pttl(throttlingKey(key)).filter(_ > -1).recoverWith { case _ =>
                     redisCli.expire(throttlingKey(key), expirationSeconds)
                   }
      _         <- redisCli.set(dailyQuotaKey(key), "0")
      _         <- redisCli.pttl(dailyQuotaKey(key)).filter(_ > -1).recoverWith { case _ =>
                     redisCli.expire(dailyQuotaKey(key), (toDayEnd / 1000).toInt)
                   }
      _         <- redisCli.set(monthlyQuotaKey(key), "0")
      _         <- redisCli.pttl(monthlyQuotaKey(key)).filter(_ > -1).recoverWith { case _ =>
                     redisCli.expire(monthlyQuotaKey(key), (toMonthEnd / 1000).toInt)
                   }
    } yield QuotaState(
      window = Quota(limit = config.quota.window, consumed = 0, resetsAt = now + windowTTL),
      daily = Quota(limit = config.quota.daily, consumed = 0, resetsAt = dayEnd.getMillis),
      monthly = Quota(limit = config.quota.monthly, consumed = 0, resetsAt = monthEnd.getMillis)
    )
  }

  def config: ThrottlingStrategyConfig
}

object ThrottlingStrategy {
  def apply(config: ThrottlingStrategyConfig, key: String)(implicit env: Env) = {
    val conf = config.json

    config.id match {
      case "LocalTokensBucketStrategyConfig" =>
        LocalTokensBucketStrategy(
          key,
          LocalTokensBucketStrategyConfig.format
            .reads(conf)
            .getOrElse(LocalTokensBucketStrategyConfig())
        )
      case "FixedWindowStrategyConfig"       =>
        FixedWindowStrategy(
          key,
          FixedWindowStrategyConfig.format
            .reads(conf)
            .getOrElse(FixedWindowStrategyConfig())
        )
      case "LegacyThrottlingStrategyConfig"  =>
        LegacyThrottlingStrategy(
          key,
          LegacyThrottlingStrategyConfig.format
            .reads(conf)
            .getOrElse(LegacyThrottlingStrategyConfig())
        )
    }
  }

  def default(clientId: String) = LegacyThrottlingStrategy(clientId, LegacyThrottlingStrategyConfig())
}

object RateLimiterUtils {
  def getKey(
      key: String,
      req: Option[RequestHeader] = None,
      attrs: TypedMap,
      route: Option[NgRoute] = None,
      apiKey: Option[ApiKey] = None,
      user: Option[PrivateAppsUser] = None
  )(implicit env: Env) = {
    GlobalExpressionLanguage.apply(
      value = key,
      req = req.orElse(attrs.get(otoroshi.plugins.Keys.RequestKey)),
      service = None,
      route = route.orElse(attrs.get(otoroshi.next.plugins.Keys.RouteKey)),
      apiKey = apiKey.orElse(attrs.get(otoroshi.plugins.Keys.ApiKeyKey)),
      user = user.orElse(attrs.get(otoroshi.plugins.Keys.UserKey)),
      context = attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty),
      attrs = attrs,
      env = env
    )
  }
}

class RateLimiter(env: Env) {
  implicit val ec: ExecutionContext = env.otoroshiExecutionContext

  val strategies = new UnboundedTrieMap[String, ThrottlingStrategy]()

  def getOrCreate(
      value: String,
      req: Option[RequestHeader] = None,
      attrs: TypedMap,
      route: Option[NgRoute] = None,
      apiKey: Option[ApiKey] = None,
      user: Option[PrivateAppsUser] = None,
      throttlingStrategy: Option[ThrottlingStrategyConfig]
  ): ThrottlingStrategy = {

    val key = RateLimiterUtils.getKey(value, req, attrs, route, apiKey, user)(env)

    throttlingStrategy match {
      case Some(config) => getOrCreateWithConfig(key, config)
      case None         => strategies.getOrElse(key, ThrottlingStrategy.default(key))
    }
  }

  private def getOrCreateWithConfig(key: String, config: ThrottlingStrategyConfig): ThrottlingStrategy = {
    strategies.get(key) match {
      case Some(strategy) if strategy.config.id == config.id => strategy
      case _                                                 =>
        val newStrategy = ThrottlingStrategy.apply(config, key)(env)
        strategies.put(key, newStrategy)
        newStrategy
    }
  }
}
