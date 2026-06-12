package otoroshi.next.plugins

import org.apache.pekko.http.scaladsl.util.FastFuture
import org.apache.pekko.http.scaladsl.util.FastFuture.EnhancedFuture
import org.apache.pekko.util.ByteString
import io.lettuce.core.ScriptOutputType
import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.models.RemainingQuotas
import otoroshi.next.plugins.api._
import otoroshi.security.IdGenerator
import otoroshi.storage.drivers.lettuce.{LettuceRedisCluster, LettuceRedisStandaloneAndSentinels}
import otoroshi.utils.TypedMap
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.Results.TooManyRequests

import java.util.concurrent.atomic.AtomicReference
import scala.collection.JavaConverters._
import scala.compat.java8.FutureConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class RateLimiterDistributedRedisSettings(enabled: Boolean, uris: Seq[String])

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

case class LocalTokensBucketStrategy(bucketId: String, config: LocalTokensBucketStrategyConfig, env: Env)
    extends ThrottlingStrategy {
  implicit val ec: ExecutionContext = env.otoroshiExecutionContext

  private val lastLeaderRequestTimeMs = new AtomicReference[Option[Long]](None)
  private val bucketRef               = new AtomicReference[LocalBucket](
    LocalBucket(tokens = config.capacity.toDouble, lastRefillMs = System.currentTimeMillis())
  )

  def client(): otoroshi.storage.RedisLike = env.datastores.redis

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
    val redisCli = client()

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
    val redisCli = client()

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

case class LegacyThrottlingStrategy(clientId: String, config: LegacyThrottlingStrategyConfig, env: Env)
    extends ThrottlingStrategy {

  def client(): otoroshi.storage.RedisLike = env.datastores.redis

  override def totalCallsKey(name: String)(implicit env: Env): String   = s"${env.storageRoot}:apikey:quotas:global:$name"
  override def dailyQuotaKey(name: String)(implicit env: Env): String   = s"${env.storageRoot}:apikey:quotas:daily:$name"
  override def monthlyQuotaKey(name: String)(implicit env: Env): String =
    s"${env.storageRoot}:apikey:quotas:monthly:$name"
  override def throttlingKey(name: String)(implicit env: Env): String   = s"${env.storageRoot}:apikey:quotas:second:$name"
}

case class DistributedRedisThrottlingStrategyConfig(
    bucketKey: Option[String] = None,
    quota: AllowedQuota = AllowedQuota()
) extends ThrottlingStrategyConfig
    with NgPluginConfig {
  def id = "DistributedRedisThrottlingStrategyConfig"

  override def json: JsValue                         =
    Json.obj("id" -> id, "quota" -> quota.json, "bucketKey" -> bucketKey)

  override def fmt: Format[ThrottlingStrategyConfig] =
    DistributedRedisThrottlingStrategyConfig.format.asInstanceOf[Format[ThrottlingStrategyConfig]]
}

object DistributedRedisThrottlingStrategyConfig {
  val format = new Format[DistributedRedisThrottlingStrategyConfig] {
    override def reads(json: JsValue): JsResult[DistributedRedisThrottlingStrategyConfig] = Try {
      DistributedRedisThrottlingStrategyConfig(
        bucketKey = json.selectAsOptString("bucketKey"),
        quota = json.select("quota").as(AllowedQuota.fmt)
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(value)     => JsSuccess(value)
    }
    override def writes(o: DistributedRedisThrottlingStrategyConfig): JsValue             = o.json
  }
}

// Throttling strategy backed by a dedicated Redis shared by all otoroshi nodes (leader and workers),
// plugged via env.statefulClientsManager. Algorithm is the canonical Redis rate-limiter pattern
// (atomic INCR + EXPIRE-if-fresh) inherited from the ThrottlingStrategy trait, but runs on a Redis
// pool that is independent from the otoroshi storage backend, so distribution is guaranteed even
// when the storage is in-memory, postgres, cassandra, etc.
case class DistributedRedisThrottlingStrategy(
    bucketId: String,
    config: DistributedRedisThrottlingStrategyConfig,
    clientF: Function0[otoroshi.storage.RedisLike]
) extends ThrottlingStrategy {
  def client(): otoroshi.storage.RedisLike = clientF()
}

case class LuaDistributedRedisThrottlingStrategyConfig(
    bucketKey: Option[String] = None,
    quota: AllowedQuota = AllowedQuota()
) extends ThrottlingStrategyConfig
    with NgPluginConfig {
  def id = "LuaDistributedRedisThrottlingStrategyConfig"

  override def json: JsValue                         =
    Json.obj("id" -> id, "quota" -> quota.json, "bucketKey" -> bucketKey)

  override def fmt: Format[ThrottlingStrategyConfig] =
    LuaDistributedRedisThrottlingStrategyConfig.format.asInstanceOf[Format[ThrottlingStrategyConfig]]
}

object LuaDistributedRedisThrottlingStrategyConfig {
  val format = new Format[LuaDistributedRedisThrottlingStrategyConfig] {
    override def reads(json: JsValue): JsResult[LuaDistributedRedisThrottlingStrategyConfig] = Try {
      LuaDistributedRedisThrottlingStrategyConfig(
        bucketKey = json.selectAsOptString("bucketKey"),
        quota = json.select("quota").as(AllowedQuota.fmt)
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(value)     => JsSuccess(value)
    }
    override def writes(o: LuaDistributedRedisThrottlingStrategyConfig): JsValue             = o.json
  }
}

object LuaDistributedRedisThrottlingStrategy {
  // Atomic counter update: INCRBY then PEXPIRE if the key has no TTL yet. Single round-trip per call.
  // Runs on the dedicated rate-limiter Redis (standalone or cluster). For cluster compat, the four
  // counter keys share the same hash-tag so they always land on the same slot.
  // KEYS = [windowKey, dailyKey, monthlyKey, totalKey]
  // ARGV = [increment, windowTtlMs, dailyTtlMs, monthlyTtlMs]
  val script: String =
    """local incr = tonumber(ARGV[1])
      |local function inc(k, ttl)
      |  local c = redis.call('INCRBY', k, incr)
      |  local p = redis.call('PTTL', k)
      |  if p < 0 then
      |    redis.call('PEXPIRE', k, ttl)
      |    p = tonumber(ttl)
      |  end
      |  return {c, p}
      |end
      |local w = inc(KEYS[1], ARGV[2])
      |local d = inc(KEYS[2], ARGV[3])
      |local m = inc(KEYS[3], ARGV[4])
      |redis.call('INCRBY', KEYS[4], incr)
      |return {w[1], w[2], d[1], d[2], m[1], m[2]}""".stripMargin
}

// Throttling strategy backed by a dedicated Redis shared by all otoroshi nodes (leader and workers),
// using a single Lua script that updates window/daily/monthly counters atomically in 1 RTT
// (canonical INCR + PEXPIRE-if-fresh pattern). Keys are co-located via a hash-tag for Redis Cluster
// support. Falls back to the trait's default multi-call implementation if the underlying client is
// not a Lettuce one (e.g. otoroshi storage backend other than Redis when the dedicated client is
// not configured).
case class LuaDistributedRedisThrottlingStrategy(
    bucketId: String,
    config: LuaDistributedRedisThrottlingStrategyConfig,
    clientF: Function0[otoroshi.storage.RedisLike]
) extends ThrottlingStrategy {

  def client(): otoroshi.storage.RedisLike = clientF()

  // Hash-tag the bucket key so all four counters land on the same Redis Cluster slot.
  override def throttlingKey(name: String)(implicit env: Env): String   =
    s"${env.storageRoot}:ratelimiter:lua:{$name}:window"
  override def dailyQuotaKey(name: String)(implicit env: Env): String   =
    s"${env.storageRoot}:ratelimiter:lua:{$name}:daily"
  override def monthlyQuotaKey(name: String)(implicit env: Env): String =
    s"${env.storageRoot}:ratelimiter:lua:{$name}:monthly"
  override def totalCallsKey(name: String)(implicit env: Env): String   =
    s"${env.storageRoot}:ratelimiter:lua:{$name}:global"

  override def checkAndIncrement(
      key: String,
      increment: Long,
      allowedQuotas: AllowedQuota,
      expirationSeconds: Int
  )(implicit env: Env, ec: ExecutionContext): Future[ThrottlingResult] = {
    val redis = client()

    val now        = System.currentTimeMillis()
    val dayEnd     = DateTime.now().secondOfDay().withMaximumValue()
    val monthEnd   = DateTime.now().dayOfMonth().withMaximumValue().secondOfDay().withMaximumValue()
    val toDayEnd   = dayEnd.getMillis - now
    val toMonthEnd = monthEnd.getMillis - now
    val windowMs   = expirationSeconds.toLong * 1000L

    val keys: Array[String]     = Array(throttlingKey(key), dailyQuotaKey(key), monthlyQuotaKey(key), totalCallsKey(key))
    val args: Array[ByteString] = Array(
      ByteString(increment.toString),
      ByteString(windowMs.toString),
      ByteString(toDayEnd.toString),
      ByteString(toMonthEnd.toString)
    )

    env.clusterAgent.incrementApi(key, increment)

    val maybeFut: Option[Future[java.util.List[Object]]] = redis match {
      case l: LettuceRedisStandaloneAndSentinels =>
        Some(
          l.redis
            .eval[java.util.List[Object]](
              LuaDistributedRedisThrottlingStrategy.script,
              ScriptOutputType.MULTI,
              keys,
              args: _*
            )
            .toScala
        )
      case l: LettuceRedisCluster                =>
        Some(
          l.redis
            .eval[java.util.List[Object]](
              LuaDistributedRedisThrottlingStrategy.script,
              ScriptOutputType.MULTI,
              keys,
              args: _*
            )
            .toScala
        )
      case _                                     => None
    }

    maybeFut match {
      case None      => super.checkAndIncrement(key, increment, allowedQuotas, expirationSeconds)
      case Some(fut) =>
        fut.map { javaList =>
          val list         = javaList.asScala.toList.map(_.asInstanceOf[java.lang.Long].longValue())
          val secCalls     = list(0)
          val windowTTL    = list(1)
          val dailyCalls   = list(2)
          val monthlyCalls = list(4)

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

          ThrottlingResult(allowed = state.withinLimits, quotas = state)
        }
    }
  }
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

case class FixedWindowStrategy(bucketId: String, config: FixedWindowStrategyConfig, env: Env)
    extends ThrottlingStrategy {

  def client(): otoroshi.storage.RedisLike = env.datastores.redis

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
    val redisCli = client()

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

    val key = config.bucketKey.evaluateEl(ctx.attrs)

    val strategy = env.rateLimiter.getOrCreate(
      key,
      attrs = ctx.attrs,
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

    val key = config.bucketKey.getOrElse("").evaluateEl(ctx.attrs)

    val strategy = env.rateLimiter.getOrCreate(
      key,
      attrs = ctx.attrs,
      throttlingStrategy = config.some
    )

    strategy
      .checkAndIncrement(
        key,
        1,
        config.quota.copy(window = config.quota.window),
        expirationSeconds = env.throttlingWindow
      )
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

class DistributedRedisThrottling extends NgAccessValidator {

  override def steps: Seq[NgStep]                = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean      = true
  override def core: Boolean               = true
  override def name: String                = "Distributed Redis Throttling"
  override def description: Option[String] =
    "Throttling backed by a dedicated Redis shared by all otoroshi nodes (leader and workers). Requires otoroshi.rate-limiter.distributed-redis.enabled = true.".some

  override def defaultConfigObject: Option[NgPluginConfig] = DistributedRedisThrottlingStrategyConfig().some

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config = ctx
      .cachedConfig(internalName)(DistributedRedisThrottlingStrategyConfig.format)
      .getOrElse(DistributedRedisThrottlingStrategyConfig())

    val key = config.bucketKey.getOrElse("").evaluateEl(ctx.attrs)

    val strategy = env.rateLimiter.getOrCreate(
      key,
      attrs = ctx.attrs,
      throttlingStrategy = config.some
    )

    strategy
      .checkAndIncrement(
        key,
        1,
        config.quota,
        expirationSeconds = env.throttlingWindow
      )
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

class LuaDistributedRedisThrottling extends NgAccessValidator {

  override def steps: Seq[NgStep]                = Seq(NgStep.ValidateAccess)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean      = true
  override def core: Boolean               = true
  override def name: String                = "Lua Distributed Redis Throttling"
  override def description: Option[String] =
    "Throttling backed by a dedicated Redis shared by all otoroshi nodes (leader and workers), updating window/daily/monthly counters atomically with a single Lua script (1 round-trip). Hash-tagged keys for Redis Cluster compat.".some

  override def defaultConfigObject: Option[NgPluginConfig] = LuaDistributedRedisThrottlingStrategyConfig().some

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config = ctx
      .cachedConfig(internalName)(LuaDistributedRedisThrottlingStrategyConfig.format)
      .getOrElse(LuaDistributedRedisThrottlingStrategyConfig())

    val key = config.bucketKey.getOrElse("").evaluateEl(ctx.attrs)

    val strategy = env.rateLimiter.getOrCreate(
      key,
      attrs = ctx.attrs,
      throttlingStrategy = config.some
    )

    strategy
      .checkAndIncrement(
        key,
        1,
        config.quota,
        expirationSeconds = env.throttlingWindow
      )
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
            case Some("LocalTokensBucketStrategyConfig")             => LocalTokensBucketStrategyConfig.format.reads(value)
            case Some("LegacyThrottlingStrategyConfig")              => LegacyThrottlingStrategyConfig.format.reads(value)
            case Some("FixedWindowStrategyConfig")                   => FixedWindowStrategyConfig.format.reads(value)
            case Some("DistributedRedisThrottlingStrategyConfig")    =>
              DistributedRedisThrottlingStrategyConfig.format.reads(value)
            case Some("LuaDistributedRedisThrottlingStrategyConfig") =>
              LuaDistributedRedisThrottlingStrategyConfig.format.reads(value)
            case _                                                   => JsError("unknown type")
          }
      }
    }

    override def writes(o: ThrottlingStrategyConfig): JsValue = o.json
  }
}

case class AllowedQuota(
    window: Long = RemainingQuotas.MaxValue,
    daily: Long = RemainingQuotas.MaxValue,
    monthly: Long = RemainingQuotas.MaxValue
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
        window = json.selectAsOptLong("window").getOrElse(RemainingQuotas.MaxValue),
        daily = json.selectAsOptLong("daily").getOrElse(RemainingQuotas.MaxValue),
        monthly = json.selectAsOptLong("monthly").getOrElse(RemainingQuotas.MaxValue)
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
    limit: Long = RemainingQuotas.MaxValue,
    consumed: Long = RemainingQuotas.MaxValue,
    resetsAt: Long = RemainingQuotas.MaxValue
) {
  def remaining: Long      = Math.max(0, limit - consumed)
  def withinLimit: Boolean = consumed < (limit + 1)
  def exceeded: Boolean    = consumed > limit
}

object Quota {
  val unlimited: Quota = Quota(RemainingQuotas.MaxValue, 0, RemainingQuotas.MaxValue)
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

  def client(): otoroshi.storage.RedisLike

  def incrementDailyAndMonthly(key: String, increment: Long)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[(Long, Long)] = {
    val redisCli = client()

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
    val redisCli = client()

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
    val redisCli = client()

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
    val redisCli = client()

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
    val redisCli = client()

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
  def apply(config: ThrottlingStrategyConfig, key: String)(implicit env: Env): ThrottlingStrategy = {
    val conf = config.json

    config.id match {
      case "LocalTokensBucketStrategyConfig"             =>
        LocalTokensBucketStrategy(
          key,
          LocalTokensBucketStrategyConfig.format
            .reads(conf)
            .getOrElse(LocalTokensBucketStrategyConfig()),
          env
        )
      case "FixedWindowStrategyConfig"                   =>
        FixedWindowStrategy(
          key,
          FixedWindowStrategyConfig.format
            .reads(conf)
            .getOrElse(FixedWindowStrategyConfig()),
          env
        )
      case "LegacyThrottlingStrategyConfig"              =>
        LegacyThrottlingStrategy(
          key,
          LegacyThrottlingStrategyConfig.format
            .reads(conf)
            .getOrElse(LegacyThrottlingStrategyConfig()),
          env
        )
      case "DistributedRedisThrottlingStrategyConfig"    =>
        DistributedRedisThrottlingStrategy(
          key,
          DistributedRedisThrottlingStrategyConfig.format
            .reads(conf)
            .getOrElse(DistributedRedisThrottlingStrategyConfig()),
          () => env.rateLimiter.adhocRateLimiterRedis
        )
      case "LuaDistributedRedisThrottlingStrategyConfig" =>
        LuaDistributedRedisThrottlingStrategy(
          key,
          LuaDistributedRedisThrottlingStrategyConfig.format
            .reads(conf)
            .getOrElse(LuaDistributedRedisThrottlingStrategyConfig()),
          () => env.rateLimiter.adhocRateLimiterRedis
        )
    }
  }

  def default(clientId: String)(implicit env: Env): ThrottlingStrategy =
    if (env.rateLimiter.distributedRedisSettings.enabled) {
      DistributedRedisThrottlingStrategy(
        clientId,
        DistributedRedisThrottlingStrategyConfig(),
        () => env.rateLimiter.globalRateLimiterRedis
      )
    } else {
      LegacyThrottlingStrategy(clientId, LegacyThrottlingStrategyConfig(), env)
    }
}

class RateLimiter(_env: Env) {
  private implicit val env: Env     = _env
  implicit val ec: ExecutionContext = _env.otoroshiExecutionContext

  private val distributedRedisId = "otoroshi-rate-limiter-distributed-redis"
  private val strategies         = new UnboundedTrieMap[String, ThrottlingStrategy]()

  lazy val distributedRedisSettings: RateLimiterDistributedRedisSettings = RateLimiterDistributedRedisSettings(
    enabled = _env.configuration
      .getOptionalWithFileSupport[Boolean]("otoroshi.rate-limiter.distributed-redis.enabled")
      .getOrElse(false),
    uris = (_env.configuration
      .getOptionalWithFileSupport[Seq[String]]("otoroshi.rate-limiter.distributed-redis.uris")
      .getOrElse(Seq.empty) ++
      _env.configuration
        .getOptionalWithFileSupport[String]("otoroshi.rate-limiter.distributed-redis.urisStr")
        .map(_.split(";").map(_.trim).toSeq)
        .getOrElse(Seq.empty))
  )

  def adhocRateLimiterRedis: otoroshi.storage.RedisLike = distributedRedisSettings.uris match {
    case uris if uris.nonEmpty && uris.length == 1 =>
      _env.statefulClientsManager.client(
        distributedRedisId,
        otoroshi.statefulclients.DistributedRateLimiterLettuceStatefulClientConfig(uris.head)
      )
    case uris if uris.nonEmpty && uris.length > 1  =>
      _env.statefulClientsManager.client(
        distributedRedisId,
        otoroshi.statefulclients.DistributedRateLimiterLettuceClusterStatefulClientConfig(uris)
      )
    case _                                         => _env.datastores.redis
  }

  def globalRateLimiterRedis: otoroshi.storage.RedisLike = {
    distributedRedisSettings.uris match {
      case uris if uris.nonEmpty && uris.length == 1 && distributedRedisSettings.enabled =>
        _env.statefulClientsManager.client(
          distributedRedisId,
          otoroshi.statefulclients.DistributedRateLimiterLettuceStatefulClientConfig(uris.head)
        )
      case uris if uris.nonEmpty && uris.length > 1 && distributedRedisSettings.enabled  =>
        _env.statefulClientsManager.client(
          distributedRedisId,
          otoroshi.statefulclients.DistributedRateLimiterLettuceClusterStatefulClientConfig(uris)
        )
      case _                                                                             => _env.datastores.redis
    }
  }

  def getOrCreate(
      value: String,
      attrs: TypedMap,
      throttlingStrategy: Option[ThrottlingStrategyConfig]
  ): ThrottlingStrategy = {
    val key = value.evaluateEl(attrs)(env)
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
