package otoroshi.next.plugins

import org.apache.pekko.http.scaladsl.util.FastFuture
import org.apache.pekko.http.scaladsl.util.FastFuture.EnhancedFuture
import org.apache.pekko.util.ByteString
import io.lettuce.core.ScriptOutputType
import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.models.RemainingQuotas
import otoroshi.next.plugins.api.*
import otoroshi.security.IdGenerator
import otoroshi.storage.drivers.lettuce.{LettuceRedisCluster, LettuceRedisStandaloneAndSentinels}
import otoroshi.utils.TypedMap
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*
import play.api.mvc.Results.TooManyRequests

import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*
import scala.compat.java8.FutureConverters.*
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
        quota = json.select("quota").asOpt(using AllowedQuota.fmt).getOrElse(AllowedQuota())
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
        val newBucketTokens = Math.min(config.capacity.toDouble, oldBucket.tokens + tokensToAdd)

        if (tokensToAdd > 0) {
          oldBucket.copy(tokens = newBucketTokens, lastRefillMs = currentTimeMs)
        } else {
          oldBucket
        }
      }
    }

    FastFuture.successful(())
  }

  private def getDailyAndMonthlyQuotas(key: String, allowedQuotas: AllowedQuota)(using
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

  override def check(key: String, allowedQuotas: AllowedQuota)(using
      env: Env,
      ec: ExecutionContext
  ): Future[ThrottlingResult] = {
    getDailyAndMonthlyQuotas(key, allowedQuotas)
      .map(state => {
        val tokensLeft = bucketRef.get().tokens

        ThrottlingResult(
          allowed = tokensLeft >= 1 && state.daily.acceptsMore(1) && state.monthly.acceptsMore(1),
          quotas = state
        )
      })
  }

  override def checkAndIncrement(
      key: String,
      increment: Long,
      allowedQuotas: AllowedQuota,
      expirationSeconds: Int
  )(using env: Env, ec: ExecutionContext): Future[ThrottlingResult] = {
    askForRefill().flatMap { _ =>
      getDailyAndMonthlyQuotas(key, allowedQuotas)
        .flatMap(currentState => {
          // the quotas are looked at before a token is taken: a call one of them turns away must not
          // consume a token the calls that will pass are entitled to
          if (!currentState.daily.acceptsMore(increment) || !currentState.monthly.acceptsMore(increment)) {
            ThrottlingResult(allowed = false, quotas = currentState).future
          } else {
            val tokensBefore = bucketRef.getAndUpdate { current =>
              if (current.tokens >= increment) {
                current.copy(tokens = current.tokens - increment)
              } else {
                current
              }
            }

            if (tokensBefore.tokens >= increment) {
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
            } else {
              ThrottlingResult(allowed = false, quotas = currentState).future
            }
          }
        })
    }
  }

  override def reset(key: String, allowedQuotas: AllowedQuota, expirationSeconds: Int)(using
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
      window = Quota(limit = allowedQuotas.window, consumed = 0, resetsAt = 0),
      daily = Quota(limit = allowedQuotas.daily, consumed = 0, resetsAt = dayEnd.getMillis),
      monthly = Quota(limit = allowedQuotas.monthly, consumed = 0, resetsAt = monthEnd.getMillis)
    )
  }
}

case class LegacyThrottlingStrategy(clientId: String, config: LegacyThrottlingStrategyConfig, env: Env)
    extends ThrottlingStrategy {

  def client(): otoroshi.storage.RedisLike = env.datastores.redis

  override def totalCallsKey(name: String)(using env: Env): String   = s"${env.storageRoot}:apikey:quotas:global:$name"
  override def dailyQuotaKey(name: String)(using env: Env): String   = s"${env.storageRoot}:apikey:quotas:daily:$name"
  override def monthlyQuotaKey(name: String)(using env: Env): String =
    s"${env.storageRoot}:apikey:quotas:monthly:$name"
  override def throttlingKey(name: String)(using env: Env): String   = s"${env.storageRoot}:apikey:quotas:second:$name"
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
        quota = json.select("quota").as(using AllowedQuota.fmt)
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
        quota = json.select("quota").as(using AllowedQuota.fmt)
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(value)     => JsSuccess(value)
    }
    override def writes(o: LuaDistributedRedisThrottlingStrategyConfig): JsValue             = o.json
  }
}

object LuaDistributedRedisThrottlingStrategy {
  // Reads the three counters, and only when the call fits under every limit does it INCRBY them and
  // PEXPIRE the ones that have no TTL yet. Single round-trip per call, and the whole decision is
  // atomic, so a call that is turned away consumes nothing at all: no give back to do.
  // Runs on the dedicated rate-limiter Redis (standalone or cluster). For cluster compat, the four
  // counter keys share the same hash-tag so they always land on the same slot.
  // KEYS = [windowKey, dailyKey, monthlyKey, totalKey]
  // ARGV = [increment, windowTtlMs, dailyTtlMs, monthlyTtlMs, windowLimit, dailyLimit, monthlyLimit]
  // returns [windowCalls, windowTtl, dailyCalls, dailyTtl, monthlyCalls, monthlyTtl, allowed]
  val script: String =
    """local incr = tonumber(ARGV[1])
      |local function peek(k)
      |  return {tonumber(redis.call('GET', k) or '0'), redis.call('PTTL', k)}
      |end
      |local function ttlOf(p, ttl)
      |  if p < 0 then return tonumber(ttl) else return p end
      |end
      |local w = peek(KEYS[1])
      |local d = peek(KEYS[2])
      |local m = peek(KEYS[3])
      |if (w[1] + incr > tonumber(ARGV[5])) or (d[1] + incr > tonumber(ARGV[6])) or (m[1] + incr > tonumber(ARGV[7])) then
      |  return {w[1], ttlOf(w[2], ARGV[2]), d[1], ttlOf(d[2], ARGV[3]), m[1], ttlOf(m[2], ARGV[4]), 0}
      |end
      |local function bump(k, p, ttl)
      |  local c = redis.call('INCRBY', k, incr)
      |  if p < 0 then redis.call('PEXPIRE', k, ttl) end
      |  return c
      |end
      |local wc = bump(KEYS[1], w[2], ARGV[2])
      |local dc = bump(KEYS[2], d[2], ARGV[3])
      |local mc = bump(KEYS[3], m[2], ARGV[4])
      |redis.call('INCRBY', KEYS[4], incr)
      |return {wc, ttlOf(w[2], ARGV[2]), dc, ttlOf(d[2], ARGV[3]), mc, ttlOf(m[2], ARGV[4]), 1}""".stripMargin
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
  override def throttlingKey(name: String)(using env: Env): String   =
    s"${env.storageRoot}:ratelimiter:lua:{$name}:window"
  override def dailyQuotaKey(name: String)(using env: Env): String   =
    s"${env.storageRoot}:ratelimiter:lua:{$name}:daily"
  override def monthlyQuotaKey(name: String)(using env: Env): String =
    s"${env.storageRoot}:ratelimiter:lua:{$name}:monthly"
  override def totalCallsKey(name: String)(using env: Env): String   =
    s"${env.storageRoot}:ratelimiter:lua:{$name}:global"

  override def checkAndIncrement(
      key: String,
      increment: Long,
      allowedQuotas: AllowedQuota,
      expirationSeconds: Int
  )(using env: Env, ec: ExecutionContext): Future[ThrottlingResult] = {
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
      ByteString(toMonthEnd.toString),
      ByteString(allowedQuotas.window.toString),
      ByteString(allowedQuotas.daily.toString),
      ByteString(allowedQuotas.monthly.toString)
    )

    val maybeFut: Option[Future[java.util.List[Object]]] = redis match {
      case l: LettuceRedisStandaloneAndSentinels =>
        Some(
          l.redis
            .eval[java.util.List[Object]](
              LuaDistributedRedisThrottlingStrategy.script,
              ScriptOutputType.MULTI,
              keys,
              args*
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
              args*
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
          val allowed      = list(6) == 1L

          // the script counts nothing for a call it turns away, so the cluster only hears about the
          // calls that went through
          if (allowed) {
            env.clusterAgent.incrementApi(key, increment)
          }

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

          ThrottlingResult(allowed = allowed, quotas = state)
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
        quota = json.select("quota").as(using AllowedQuota.fmt),
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

  private def getDailyAndMonthlyQuotas(key: String, allowedQuotas: AllowedQuota)(using
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

  // the calls already made in the current window. a window that is over holds none any more,
  // whatever the counter still says: the next call going through is what opens the next one
  private def windowQuota(bucket: FixedWindowBucket, now: Long): Quota = {
    val over = now - bucket.windowStart >= config.windowDurationMs
    Quota(
      limit = config.quota.window,
      consumed = if (over) 0L else bucket.count,
      resetsAt = (if (over) now else bucket.windowStart) + config.windowDurationMs
    )
  }

  override def check(key: String, allowedQuotas: AllowedQuota)(using
      env: Env,
      ec: ExecutionContext
  ): Future[ThrottlingResult] = {
    getDailyAndMonthlyQuotas(key, allowedQuotas)
      .map(state => {
        val window = windowQuota(bucketRef.get(), System.currentTimeMillis())
        ThrottlingResult(
          allowed = window.acceptsMore(1) && state.daily.acceptsMore(1) && state.monthly.acceptsMore(1),
          quotas = state.copy(window = window)
        )
      })
  }

  override def checkAndIncrement(key: String, increment: Long, allowedQuotas: AllowedQuota, expirationSeconds: Int)(
      using
      env: Env,
      ec: ExecutionContext
  ): Future[ThrottlingResult] = {
    // the daily and the monthly counters are read before anything is taken from the window: a call
    // one of them turns away must not eat a slot the calls that will pass are entitled to
    getDailyAndMonthlyQuotas(key, allowedQuotas).flatMap { currentState =>
      val now = System.currentTimeMillis()
      if (!currentState.daily.acceptsMore(increment) || !currentState.monthly.acceptsMore(increment)) {
        ThrottlingResult(
          allowed = false,
          quotas = currentState.copy(window = windowQuota(bucketRef.get(), now))
        ).vfuture
      } else {
        val before = bucketRef.getAndUpdate { current =>
          if (now - current.windowStart >= config.windowDurationMs) {
            // the window is over: this call opens the next one and takes its first slot
            FixedWindowBucket(windowStart = now, count = increment)
          } else if (current.count + increment <= config.quota.window) {
            current.copy(count = current.count + increment)
          } else {
            current
          }
        }

        val opened  = now - before.windowStart >= config.windowDurationMs
        val allowed = opened || (before.count + increment <= config.quota.window)
        val window  = Quota(
          limit = config.quota.window,
          consumed = if (opened) increment else if (allowed) before.count + increment else before.count,
          resetsAt = (if (opened) now else before.windowStart) + config.windowDurationMs
        )

        if (allowed) {
          super
            .incrementDailyAndMonthly(key, increment)
            .map { case (dailyCalls, monthyCalls) =>
              ThrottlingResult(
                allowed = true,
                quotas = currentState.copy(
                  window = window,
                  daily = currentState.daily.copy(consumed = dailyCalls),
                  monthly = currentState.monthly.copy(consumed = monthyCalls)
                )
              )
            }
        } else {
          ThrottlingResult(allowed = false, quotas = currentState.copy(window = window)).vfuture
        }
      }
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
        quota = json.select("quota").as(using AllowedQuota.fmt)
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

  override def access(ctx: NgAccessContext)(using env: Env, ec: ExecutionContext): Future[NgAccess] = {
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

  override def access(ctx: NgAccessContext)(using env: Env, ec: ExecutionContext): Future[NgAccess] = {
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

  override def access(ctx: NgAccessContext)(using env: Env, ec: ExecutionContext): Future[NgAccess] = {
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

  override def access(ctx: NgAccessContext)(using env: Env, ec: ExecutionContext): Future[NgAccess] = {
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
  def withinLimits: Boolean                 = window.withinLimit && daily.withinLimit && monthly.withinLimit
  // whether one more call fits under every limit, which is what a strategy has to know before it
  // counts anything
  def acceptsMore(increment: Long): Boolean =
    window.acceptsMore(increment) && daily.acceptsMore(increment) && monthly.acceptsMore(increment)
  def legacy(): RemainingQuotas             = RemainingQuotas(
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
  // whether one more call fits: the counters are read before they are written, so this is what
  // decides, where an already incremented counter would be read with withinLimit
  def acceptsMore(increment: Long): Boolean = (consumed + increment) < (limit + 1)
}

object Quota {
  val unlimited: Quota = Quota(RemainingQuotas.MaxValue, 0, RemainingQuotas.MaxValue)
}

trait ThrottlingStrategy {
  def throttlingKey(name: String)(using env: Env): String =
    s"${env.storageRoot}:ratelimiter:quotas:window:$name"

  def dailyQuotaKey(name: String)(using env: Env): String =
    s"${env.storageRoot}:ratelimiter:quotas:daily:$name"

  def monthlyQuotaKey(name: String)(using env: Env): String =
    s"${env.storageRoot}:ratelimiter:quotas:monthly:$name"

  def totalCallsKey(name: String)(using env: Env): String =
    s"${env.storageRoot}:ratelimiter:quotas:global:$name"

  def client(): otoroshi.storage.RedisLike

  def incrementDailyAndMonthly(key: String, increment: Long)(using
      env: Env,
      ec: ExecutionContext
  ): Future[(Long, Long)] = {
    val redisCli = client()

    val dayEnd     = DateTime.now().secondOfDay().withMaximumValue()
    val toDayEnd   = dayEnd.getMillis - DateTime.now().getMillis
    val monthEnd   = DateTime.now().dayOfMonth().withMaximumValue().secondOfDay().withMaximumValue()
    val toMonthEnd = monthEnd.getMillis - DateTime.now().getMillis

    val dailyF   = for {
      dailyCalls <- redisCli.incrby(dailyQuotaKey(key), increment)
      _          <- redisCli.pttl(dailyQuotaKey(key)).flatMap {
                      case -1 => redisCli.expire(dailyQuotaKey(key), (toDayEnd / 1000).toInt)
                      case _  => Future.successful(())
                    }
    } yield dailyCalls
    val monthlyF = for {
      monthlyCalls <- redisCli.incrby(monthlyQuotaKey(key), increment)
      _            <- redisCli.pttl(monthlyQuotaKey(key)).flatMap {
                        case -1 => redisCli.expire(monthlyQuotaKey(key), (toMonthEnd / 1000).toInt)
                        case _  => Future.successful(())
                      }
    } yield monthlyCalls

    for {
      dailyCalls   <- dailyF
      monthlyCalls <- monthlyF
    } yield {
      (dailyCalls, monthlyCalls)
    }
  }

  // the counters are read before a single one of them is written: a call that does not fit under
  // every limit must not move any of them, or a consumer hammering a window that is already full
  // would burn its whole day without one call being served.
  // two calls landing at the very same instant can both read a counter that still fits and both go
  // through, where incrementing first would have been exact under concurrency. that is the trade
  // made here: the overshoot is bounded by how many calls are in flight and the counters are exact
  // again on the very next call, while counting calls that never happened is wrong for good. the
  // lua strategy has neither problem, it decides and counts in one atomic script.
  def checkAndIncrement(
      key: String,
      increment: Long,
      allowedQuotas: AllowedQuota,
      expirationSeconds: Int
  )(using env: Env, ec: ExecutionContext): Future[ThrottlingResult] = {
    check(key, allowedQuotas).flatMap { current =>
      if (!current.quotas.acceptsMore(increment)) {
        ThrottlingResult(allowed = false, quotas = current.quotas).vfuture
      } else {
        incrementCounters(key, increment, allowedQuotas, expirationSeconds)
          .map(state => ThrottlingResult(allowed = true, quotas = state))
      }
    }
  }

  // counts one call on every counter. the window, the day, the month and the total have no order
  // between them, so their calls go out together rather than one after the other
  def incrementCounters(
      key: String,
      increment: Long,
      allowedQuotas: AllowedQuota,
      expirationSeconds: Int
  )(using env: Env, ec: ExecutionContext): Future[QuotaState] = {
    val redisCli = client()

    // Calculate reset timestamps
    val now      = System.currentTimeMillis()
    val dayEnd   = DateTime.now().secondOfDay().withMaximumValue()
    val monthEnd = DateTime.now().dayOfMonth().withMaximumValue().secondOfDay().withMaximumValue()

    env.clusterAgent.incrementApi(key, increment)

    val windowF          = for {
      secCalls  <- redisCli.incrby(throttlingKey(key), increment)
      windowTTL <- redisCli.pttl(throttlingKey(key)).flatMap {
                     case -1  =>
                       redisCli.expire(throttlingKey(key), expirationSeconds).map(_ => expirationSeconds * 1000L)
                     case ttl => Future.successful(ttl)
                   }
    } yield (secCalls, windowTTL)
    val dailyMonthlyF    = incrementDailyAndMonthly(key, increment)
    val totalF           = redisCli.incrby(totalCallsKey(key), increment)

    for {
      window               <- windowF
      dailyAndMonthlyCalls <- dailyMonthlyF
      _                    <- totalF
    } yield {
      QuotaState(
        window = Quota(
          limit = allowedQuotas.window,
          consumed = window._1,
          resetsAt = now + window._2
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
    }
  }

  def quotas(key: String, allowedQuotas: AllowedQuota, expirationSeconds: Int)(using
      ec: ExecutionContext,
      env: Env
  ): Future[QuotaState] = {
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
    }
  }

  def check(key: String, allowedQuotas: AllowedQuota)(using
      env: Env,
      ec: ExecutionContext
  ): Future[ThrottlingResult] = {
    val redisCli = client()

    // Calculate reset timestamps
    val now      = System.currentTimeMillis()
    val dayEnd   = DateTime.now().secondOfDay().withMaximumValue()
    val monthEnd = DateTime.now().dayOfMonth().withMaximumValue().secondOfDay().withMaximumValue()

    // every call goes through this before it is counted, so the four reads go out together rather
    // than one after the other
    val windowF  = redisCli.get(throttlingKey(key)).fast.map(_.map(_.utf8String.toLong).getOrElse(0L))
    val ttlF     = redisCli.pttl(throttlingKey(key)).fast.map(_.max(0L))
    val dailyF   = redisCli.get(dailyQuotaKey(key)).fast.map(_.map(_.utf8String.toLong).getOrElse(0L))
    val monthlyF = redisCli.get(monthlyQuotaKey(key)).fast.map(_.map(_.utf8String.toLong).getOrElse(0L))

    for {
      throttlingCallsPerWindow <- windowF
      windowTTL                <- ttlF
      dailyCalls               <- dailyF
      monthlyCalls             <- monthlyF
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

      // the very same question checkAndIncrement asks, so a caller that only checks and a caller
      // that counts turn away the same calls
      ThrottlingResult(
        allowed = state.acceptsMore(1),
        quotas = state
      )
    }
  }

  def reset(key: String, allowedQuotas: AllowedQuota, expirationSeconds: Int)(using
      env: Env,
      ec: ExecutionContext
  ): Future[QuotaState] = {
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
      window = Quota(limit = allowedQuotas.window, consumed = 0, resetsAt = now + windowTTL),
      daily = Quota(limit = allowedQuotas.daily, consumed = 0, resetsAt = dayEnd.getMillis),
      monthly = Quota(limit = allowedQuotas.monthly, consumed = 0, resetsAt = monthEnd.getMillis)
    )
  }

  def config: ThrottlingStrategyConfig
}

object ThrottlingStrategy {
  def apply(config: ThrottlingStrategyConfig, key: String)(using env: Env): ThrottlingStrategy = {
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

  def default(clientId: String)(using env: Env): ThrottlingStrategy =
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
      .getOrElse(Seq.empty).toSeq ++
      _env.configuration
        .getOptionalWithFileSupport[String]("otoroshi.rate-limiter.distributed-redis.urisStr")
        .map(_.split(";").map(_.trim).toSeq)
        .getOrElse(Seq.empty).toSeq)
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
    val key = value.evaluateEl(attrs)(using env)
    throttlingStrategy match {
      case Some(config) => getOrCreateWithConfig(key, config)
      case None         => strategies.getOrElse(key, ThrottlingStrategy.default(key))
    }
  }

  private def getOrCreateWithConfig(key: String, config: ThrottlingStrategyConfig): ThrottlingStrategy = {
    strategies.get(key) match {
      case Some(strategy) if strategy.config.id == config.id => strategy
      case _                                                 =>
        val newStrategy = ThrottlingStrategy.apply(config, key)(using env)
        strategies.put(key, newStrategy)
        newStrategy
    }
  }
}
