package otoroshi.next.plugins

import akka.http.scaladsl.util.FastFuture
import otoroshi.el.GlobalExpressionLanguage
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.models.{ApiKey, PrivateAppsUser}
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
import otoroshi.utils.syntax.implicits.{BetterJsValueReader, BetterSyntax}
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
    refillRequestedTokens: Long = 50
) extends ThrottlingStrategyConfig
    with NgPluginConfig {
  def `type` = "local-tokens-bucket"

  override def json(): JsValue = Json.obj(
    "bucketKey"               -> bucketKey,
    "capacity"                -> capacity,
    "refillRequestIntervalMs" -> refillRequestIntervalMs,
    "refillRequestedTokens"   -> refillRequestedTokens
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
        refillRequestedTokens = json.selectAsLong("refillRequestedTokens")
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(value)     => JsSuccess(value)
    }
    override def writes(o: LocalTokensBucketStrategyConfig): JsValue             = o.json()
  }
}

case class LocalTokensBucketStrategy(config: LocalTokensBucketStrategyConfig)(implicit env: Env)
    extends ThrottlingStrategy {
  implicit val ec: ExecutionContext = env.otoroshiExecutionContext

  val bucketId = s"${env.storageRoot}:apikey:bucket:${config.bucketKey}"

  private val lastLeaderRequestTimeMs = new AtomicReference[Option[Long]](None)
  private val memoryBucket            = new AtomicReference[Double](config.capacity.toDouble)
  private val bucketRef               = new AtomicReference[LocalBucket](
    LocalBucket(key = bucketId, tokens = 0, lastRefillMs = System.currentTimeMillis())
  )

  override def askForRefill(): Future[Unit] = {
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

  def consume(): Future[Boolean] = {
    val currentTokens = memoryBucket.get()
    if (currentTokens >= 1.0) {
      val newTokens = memoryBucket.updateAndGet(v => Math.max(0.0, v - 1.0))
      println(
        s"remaining memory tokens: $newTokens and in global bucket: ${bucketRef.get().tokens}"
      )
      true.future
    } else {
      false.future
    }
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

    val key = config.bucketKey

    env.proxyState.rateLimiter
      .consume(
        key,
        req = ctx.request.some,
        attrs = ctx.attrs,
        route = ctx.route.some,
        apiKey = ctx.apikey,
        user = ctx.user,
        throttlingStrategy = config
      )
      .flatMap { allowed =>
        if (!allowed) {
          env.proxyState.rateLimiter
            .askForRefill(
              key,
              req = ctx.request.some,
              attrs = ctx.attrs,
              route = ctx.route.some,
              apiKey = ctx.apikey,
              user = ctx.user
            )
            .flatMap(_ =>
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
            )
        } else {
          NgAccess.NgAllowed.vfuture
        }
      }
  }
}

trait ThrottlingStrategyConfig {
  def `type`: String
  def json: JsValue
}

trait ThrottlingStrategy {
  def consume(): Future[Boolean]
  def askForRefill(): Future[Unit] = FastFuture.successful(())
  def config: ThrottlingStrategyConfig
}

object ThrottlingStrategy {
  def apply(config: ThrottlingStrategyConfig)(implicit env: Env) = {
    val conf = config.json
    config.`type` match {
      case "local-tokens-bucket" =>
        LocalTokensBucketStrategy(
          LocalTokensBucketStrategyConfig(
            bucketKey = conf.selectAsOptString("bucketKey").getOrElse(""),
            capacity = conf.selectAsLong("capacity"),
            refillRequestIntervalMs = conf.selectAsLong("refillRequestIntervalMs"),
            refillRequestedTokens = conf.selectAsLong("refillRequestedTokens")
          )
        )
    }
  }
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

  def askForRefill(
      bucketKey: String,
      req: Option[RequestHeader] = None,
      attrs: TypedMap,
      route: Option[NgRoute] = None,
      apiKey: Option[ApiKey] = None,
      user: Option[PrivateAppsUser] = None
  ): Future[Unit] = {
    buckets.get(getKey(bucketKey, req, attrs, route, apiKey, user)) match {
      case Some(strategy) => strategy.askForRefill()
      case None           => FastFuture.successful(())
    }
  }

  def consume(
      bucketKey: String,
      req: Option[RequestHeader] = None,
      attrs: TypedMap,
      route: Option[NgRoute] = None,
      apiKey: Option[ApiKey] = None,
      user: Option[PrivateAppsUser] = None,
      throttlingStrategy: ThrottlingStrategyConfig
  ): Future[Boolean] = {
    println("consume for ", bucketKey, throttlingStrategy)
    val key = getKey(bucketKey, req, attrs, route, apiKey, user)

    buckets.get(key) match {
      case Some(strategy) => strategy.consume()
      case None           =>
        buckets.put(key, ThrottlingStrategy.apply(throttlingStrategy)(env))
        consume(key, req, attrs, route, apiKey, user, throttlingStrategy = throttlingStrategy)
    }
  }
}
