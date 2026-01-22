package otoroshi.next.plugins

import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.util.FastFuture.EnhancedFuture
import org.joda.time.DateTime
import otoroshi.el.GlobalExpressionLanguage
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.models.{ApiKey, PrivateAppsUser, RemainingQuotas}
import otoroshi.next.models.NgRoute
import otoroshi.next.plugins.api.{
  NgAccess,
  NgAccessContext,
  NgAccessValidator,
  NgPluginCategory,
  NgPluginConfig,
  NgPluginVisibility,
  NgStep
}
import otoroshi.security.IdGenerator
import otoroshi.utils.TypedMap
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.syntax.implicits.{BetterJsValue, BetterJsValueReader, BetterSyntax}
import play.api.libs.json.{Format, JsError, JsObject, JsResult, JsSuccess, JsValue, Json}
import play.api.mvc.RequestHeader
import play.api.mvc.Results.TooManyRequests

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class LocalBucket(key: String = "", var tokens: Double = 0, var lastRefillMs: Long)

case class LocalTokensBucketStrategyConfig(
    bucketKey: String = IdGenerator.uuid,
    capacity: Long = 300,
    refillRequestIntervalMs: Long = 50,
    refillRequestedTokens: Long = 50,
    quota: AllowedQuota = AllowedQuota()
) extends ThrottlingStrategyConfig
    with NgPluginConfig {
  def `type` = "local-tokens-bucket"

  override def json(): JsValue = Json.obj(
    "bucketKey"               -> bucketKey,
    "capacity"                -> capacity,
    "refillRequestIntervalMs" -> refillRequestIntervalMs,
    "refillRequestedTokens"   -> refillRequestedTokens,
    "quota"                   -> quota.json
  )

  def refillRatePerSecond: Double =
    (refillRequestedTokens.toDouble * 1000.0) / refillRequestIntervalMs.toDouble
}

object LocalTokensBucketStrategyConfig {
  val format = new Format[LocalTokensBucketStrategyConfig] {
    override def reads(json: JsValue): JsResult[LocalTokensBucketStrategyConfig] = Try {
      LocalTokensBucketStrategyConfig(
        bucketKey = json.selectAsOptString("bucketKey").getOrElse(""),
        capacity = json.selectAsLong("capacity"),
        refillRequestIntervalMs = json.selectAsLong("refillRequestIntervalMs"),
        refillRequestedTokens = json.selectAsLong("refillRequestedTokens"),
        quota = json.select("quota").as(AllowedQuota.fmt)
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(value)     => JsSuccess(value)
    }
    override def writes(o: LocalTokensBucketStrategyConfig): JsValue             = o.json()
  }
}

case class LocalTokensBucketStrategy(bucketId: String, config: LocalTokensBucketStrategyConfig)(implicit env: Env)
    extends ThrottlingStrategy {
  implicit val ec: ExecutionContext = env.otoroshiExecutionContext

  private val lastLeaderRequestTimeMs = new AtomicReference[Option[Long]](None)
  private val memoryBucket            = new AtomicReference[Double](config.capacity.toDouble)
  private val bucketRef               = new AtomicReference[LocalBucket](
    LocalBucket(key = bucketId, tokens = 0, lastRefillMs = System.currentTimeMillis())
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
        val availableTokens = Math.min(config.refillRequestedTokens, newBucketTokens)

        if (availableTokens >= 1) {
          memoryBucket.set(availableTokens)
          println(s"Refilling bucket ${oldBucket.key} with ${availableTokens.toInt} tokens")
          oldBucket.copy(tokens = newBucketTokens - availableTokens, lastRefillMs = currentTimeMs)
        } else {
          println("NO MORE TOKENS")
          oldBucket
        }
      }
    }

    FastFuture.successful(())
  }

  override def check(key: String, allowedQuotas: AllowedQuota)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[ThrottlingResult] = {
    val tokensAfter = memoryBucket.get()

    val hadEnoughTokens = tokensAfter >= 0

    ThrottlingResult(
      allowed = hadEnoughTokens,
      quotas = QuotaState(
        window = Quota(
          limit = config.capacity,
          consumed = (config.refillRequestedTokens - tokensAfter).toLong,
          resetsAt = System.currentTimeMillis() + (tokensAfter / config.refillRatePerSecond * 1000).toLong
        )
      )
    ).future
  }

  override def checkAndIncrement(
      key: String,
      increment: Long,
      allowedQuotas: AllowedQuota
  )(implicit env: Env, ec: ExecutionContext): Future[ThrottlingResult] = {
    val tokensAfter = memoryBucket.updateAndGet { current =>
      if (current >= increment) {
        current - increment
      } else {
        current
      }
    }

    val hadEnoughTokens = tokensAfter >= 0

    if (hadEnoughTokens) {
      ThrottlingResult(
        allowed = true,
        quotas = QuotaState(
          window = Quota(
            limit = config.capacity,
            consumed = (config.refillRequestedTokens - tokensAfter).toLong,
            resetsAt = System.currentTimeMillis() + (tokensAfter / config.refillRatePerSecond * 1000).toLong
          )
        )
      ).future
    } else {
      // Not enough tokens, try refill then retry
      askForRefill().flatMap { _ =>
        val tokensAfterRefill = memoryBucket.updateAndGet { current =>
          if (current >= increment) current - increment else current
        }

        val allowed = tokensAfterRefill >= 0

        ThrottlingResult(
          allowed = allowed,
          quotas = QuotaState(
            window = Quota(
              limit = config.capacity,
              consumed =
                if (allowed) (config.refillRequestedTokens - tokensAfterRefill).toLong
                else config.refillRequestedTokens,
              resetsAt = System.currentTimeMillis() + 1000
            )
          )
        ).future
      }
    }
  }

  override def reset(key: String)(implicit ec: ExecutionContext): Future[Unit] = ???

  override def key: String = bucketId
}

case class LegacyThrottlingStrategy(clientId: String) extends ThrottlingStrategy {

  override def totalCallsKey(name: String)(implicit env: Env): String   = s"${env.storageRoot}:apikey:quotas:global:$name"
  override def dailyQuotaKey(name: String)(implicit env: Env): String   = s"${env.storageRoot}:apikey:quotas:daily:$name"
  override def monthlyQuotaKey(name: String)(implicit env: Env): String =
    s"${env.storageRoot}:apikey:quotas:monthly:$name"
  override def throttlingKey(name: String)(implicit env: Env): String   = s"${env.storageRoot}:apikey:quotas:second:$name"

  override def reset(key: String)(implicit ec: ExecutionContext): Future[Unit] = ???

  override def config: ThrottlingStrategyConfig = ???

  override def key: String = clientId
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

    val key = config.bucketKey

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
      .checkAndIncrement(key, 1, config.quota)
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
  def `type`: String
  def json: JsValue

  def quota: AllowedQuota
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
        window = json.selectAsLong("window"),
        daily = json.selectAsLong("daily"),
        monthly = json.selectAsLong("monthly")
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
    limit: Long,
    consumed: Long,
    resetsAt: Long
) {
  def remaining: Long      = Math.max(0, limit - consumed)
  def withinLimit: Boolean = consumed <= limit
  def exceeded: Boolean    = consumed > limit
}

object Quota {
  val unlimited: Quota = Quota(Long.MaxValue, 0, Long.MaxValue)
}

trait ThrottlingStrategy {
  def throttlingKey(name: String)(implicit env: Env): String   =
    s"${env.storageRoot}:ratelimiter:quotas:window:$name"
  def dailyQuotaKey(name: String)(implicit env: Env): String   =
    s"${env.storageRoot}:ratelimiter:quotas:daily:$name"
  def monthlyQuotaKey(name: String)(implicit env: Env): String =
    s"${env.storageRoot}:ratelimiter:quotas:monthly:$name"
  def totalCallsKey(name: String)(implicit env: Env): String   =
    s"${env.storageRoot}:ratelimiter:quotas:global:$name"

  def checkAndIncrement(
      key: String,
      increment: Long,
      allowedQuotas: AllowedQuota
  )(implicit env: Env, ec: ExecutionContext): Future[ThrottlingResult] = {
    val redisCli = env.datastores.redis

    // Calculate reset timestamps
    val now        = System.currentTimeMillis()
    val dayEnd     = DateTime.now().secondOfDay().withMaximumValue()
    val toDayEnd   = dayEnd.getMillis - DateTime.now().getMillis
    val monthEnd   = DateTime.now().dayOfMonth().withMaximumValue().secondOfDay().withMaximumValue()
    val toMonthEnd = monthEnd.getMillis - DateTime.now().getMillis

    env.clusterAgent.incrementApi(key, increment)

    for {
      secCalls  <- redisCli.incrby(throttlingKey(key), increment)
      windowTTL <- redisCli.pttl(throttlingKey(key)).flatMap {
                     case -1  =>
                       redisCli.expire(throttlingKey(key), env.throttlingWindow).map(_ => env.throttlingWindow * 1000L)
                     case ttl => Future.successful(ttl)
                   }

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

  def reset(key: String)(implicit ec: ExecutionContext): Future[Unit]
  def config: ThrottlingStrategyConfig

  def key: String
}

object ThrottlingStrategy {
  def apply(config: ThrottlingStrategyConfig, key: String)(implicit env: Env) = {
    val conf = config.json
    config.`type` match {
      case "local-tokens-bucket" =>
        LocalTokensBucketStrategy(
          key,
          LocalTokensBucketStrategyConfig(
            bucketKey = conf.selectAsOptString("bucketKey").getOrElse(""),
            capacity = conf.selectAsLong("capacity"),
            refillRequestIntervalMs = conf.selectAsLong("refillRequestIntervalMs"),
            refillRequestedTokens = conf.selectAsLong("refillRequestedTokens")
          )
        )
    }
  }

  def default(clientId: String) = LegacyThrottlingStrategy(clientId)
}

class RateLimiter(env: Env) {
  implicit val ec: ExecutionContext = env.otoroshiExecutionContext

  val buckets = new UnboundedTrieMap[String, ThrottlingStrategy]()

  private def getKey(
      key: String,
      req: Option[RequestHeader] = None,
      attrs: TypedMap,
      route: Option[NgRoute] = None,
      apiKey: Option[ApiKey] = None,
      user: Option[PrivateAppsUser] = None
  ) = {
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

  def getOrCreate(
      bucketKey: String,
      req: Option[RequestHeader] = None,
      attrs: TypedMap,
      route: Option[NgRoute] = None,
      apiKey: Option[ApiKey] = None,
      user: Option[PrivateAppsUser] = None,
      throttlingStrategy: Option[ThrottlingStrategyConfig]
  ): ThrottlingStrategy = {
    val key = getKey(bucketKey, req, attrs, route, apiKey, user)
    buckets.getOrElseUpdate(
      key,
      throttlingStrategy
        .map(strategy => ThrottlingStrategy.apply(strategy, key)(env))
        .getOrElse(ThrottlingStrategy.default(key))
    )
  }
}
