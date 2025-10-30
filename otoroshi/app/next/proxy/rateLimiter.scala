package otoroshi.next.proxy

import akka.http.scaladsl.util.FastFuture
import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.next.plugins.api.NgPluginCategory
import otoroshi.script.{Job, JobId}
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.syntax.implicits.{BetterJsReadable, BetterJsValueReader, BetterSyntax}
import play.api.Logger
import play.api.libs.json._

import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class LocalBucket(key: String = "", var tokens: Double = 0, var lastRefill: Long)

case class TokensSnapshot(tokens: Double, lastRefill: Long) {
  def asJson(): JsObject = Json.obj(
    "tokens" -> tokens,
    "lastRefill" -> lastRefill
  )
}

object TokensSnapshot {
  val _fmt = new Format[TokensSnapshot] {

    override def reads(json: JsValue): JsResult[TokensSnapshot] = Try {
      TokensSnapshot(
        tokens = json.selectAsDouble("tokens"),
        lastRefill = json.selectAsLong("lastRefill")
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }

    override def writes(o: TokensSnapshot) = o.asJson
  }
}

trait ThrottlingStrategyConfig {
  def `type`: String
  def json: JsObject
}

object ThrottlingStrategyConfig {
  val format = new Format[ThrottlingStrategyConfig] {
    override def writes(o: ThrottlingStrategyConfig): JsValue = Json.obj(
      "type"    -> o.`type`,
      "config"  -> o.json
    )

    override def reads(json: JsValue): JsResult[ThrottlingStrategyConfig] = Try {
      val throttlingType = (json \ "type").asString
      val config = (json \ "config").asValue

      throttlingType match {
        case "local-tokens-bucket" => LocalTokensBucketStrategyConfig(
          bucketKey = config.selectAsOptString("bucketKey").getOrElse(""),
          capacity = config.selectAsLong("capacity"),
          refillRequestIntervalMs = config.selectAsLong("refillRequestIntervalMs"),
          refillRequestedTokens = config.selectAsLong("refillRequestedTokens")
        )
        case _ => ???
      }

    } match {
      case Failure(e) => JsError(e.getMessage())
      case Success(v) => JsSuccess(v)
    }
  }
}

trait ThrottlingStrategy {
  def consume(): Boolean
  def askForRefill(): Future[Unit] = FastFuture.successful(())
  def config: ThrottlingStrategyConfig
}

object ThrottlingStrategy {
  def apply(config: ThrottlingStrategyConfig)(implicit env: Env) = {
    val conf = config.json
    config.`type` match {
      case "local-tokens-bucket" => LocalTokensBucketStrategy(LocalTokensBucketStrategyConfig(
        bucketKey = conf.selectAsOptString("bucketKey").getOrElse(""),
        capacity = conf.selectAsLong("capacity"),
        refillRequestIntervalMs = conf.selectAsLong("refillRequestIntervalMs"),
        refillRequestedTokens = conf.selectAsLong("refillRequestedTokens")
      ))
      case "local-fixed-window" => LocalFixedWindowStrategy(LocalFixedWindowConfig(
        bucketKey = conf.selectAsOptString("bucketKey").getOrElse(""),
        capacity = conf.selectAsLong("capacity")
      ))
    }
  }
}

case class LocalTokensBucketStrategyConfig(bucketKey: String,
                                           capacity: Long,
                                           refillRequestIntervalMs: Long = 50,
                                           refillRequestedTokens: Long = 50) extends ThrottlingStrategyConfig {
  def `type` = "local-tokens-bucket"

  override def json(): JsObject = Json.obj(
    "bucketKey" -> bucketKey,
    "capacity" -> capacity,
    "refillRequestIntervalMs" -> refillRequestIntervalMs,
    "refillRequestedTokens" -> refillRequestedTokens
  )
}

case class LocalFixedWindowConfig(bucketKey: String, capacity: Long) extends ThrottlingStrategyConfig {
  override def `type`: String = "local-fixed-window"

  override def json: JsObject = Json.obj(
    "bucketKey" -> bucketKey,
    "capacity" -> capacity
  )
}

case class LocalFixedWindowStrategy(config: LocalFixedWindowConfig)(implicit env: Env)
  extends ThrottlingStrategy {
  implicit val ec: ExecutionContext = env.otoroshiExecutionContext

  private val windowMs: Long = env.throttlingWindow * 1000L

  private val windowStart = new AtomicLong(currentWindowStart())
  private val counter = new AtomicLong(0L)

  private def currentWindowStart(): Long = DateTime.now().getMillis / windowMs * windowMs

  override def askForRefill(): Future[Unit] = FastFuture.successful(())

  override def consume(): Boolean = {
    val now: Long = DateTime.now().getMillis
    val currentWindow = now / windowMs * windowMs

    val prevWindow = windowStart.get()
    if (currentWindow > prevWindow) {
      if (windowStart.compareAndSet(prevWindow, currentWindow)) {
        counter.set(0L)
      }
    }

    val newCount = counter.incrementAndGet()
    val allowed = newCount <= config.capacity

    if (!allowed) {
      println(
        s"[${config.bucketKey}] too many requests: $newCount > ${config.capacity} (window start = $currentWindow)"
      )
    }

    allowed
  }
}

case class LocalTokensBucketStrategy(config: LocalTokensBucketStrategyConfig)(implicit env: Env)
  extends ThrottlingStrategy {
  implicit val ec: ExecutionContext = env.otoroshiExecutionContext

  val bucketId = s"${env.storageRoot}:apikey:bucket:${config.bucketKey}"

  private val lastLeaderRequestTime = new AtomicReference[Option[Long]](None)
  private val memoryBucket = new AtomicReference[Double](0.0)
  private val bucketRef = new AtomicReference[LocalBucket](
    LocalBucket(key = bucketId, tokens = config.capacity, lastRefill = now())
  )

  private val refillRate: Double = config.capacity.toDouble / env.throttlingWindow.toDouble

  private def now(): Long = DateTime.now().getMillis / 1000

  override def askForRefill(): Future[Unit] = {
    val currentTime = now()

    val shouldRefill = lastLeaderRequestTime.get() match {
      case Some(last) => (currentTime - last) * 1000 >= config.refillRequestIntervalMs
      case None       => true
    }

    if (shouldRefill) {
      lastLeaderRequestTime.set(Some(currentTime))

      bucketRef.updateAndGet { oldBucket =>
        val timeElapsed = currentTime - oldBucket.lastRefill
        val tokensToAdd = timeElapsed * refillRate
        val newBucketTokens = Math.min(config.capacity, oldBucket.tokens + tokensToAdd)
        val availableTokens = Math.min(config.refillRequestedTokens, newBucketTokens)

        if (availableTokens >= 1) {
          memoryBucket.set(availableTokens)
          println(s"Mise à jour du bucket ${oldBucket.key} j'autorise ${availableTokens.toInt}")
          oldBucket.copy(tokens = newBucketTokens - availableTokens, lastRefill = currentTime)
        } else {
          println("NO MORE TOKEN")
          oldBucket
        }
      }
    }

    FastFuture.successful(())
  }

  override def consume(): Boolean = {
    val currentTokens = memoryBucket.get()
    if (currentTokens >= 1.0) {
      val newTokens = memoryBucket.updateAndGet(v => Math.max(0.0, v - 1.0))
      println(
        s"remaining memory tokens : $newTokens and in global bucket : ${bucketRef.get().tokens}"
      )
      true
    } else {
      false
    }
  }
}

case class GlobalTokenBucketStrategy() {

}

case class GlobalFixedWindow() {

}

class RateLimiter(env: Env) {

  implicit val ec = env.otoroshiExecutionContext

  val buckets = new UnboundedTrieMap[String, ThrottlingStrategy]()

//  val leaderRetryIntervalMS: Long = 50
//  var lastLeaderRequestTime = DateTime.now().getMillis

  def now() = DateTime.now().getMillis

//  def checkEmptyBucket(bucketKey: String, apiKey: ApiKey): Future[Long] = {
//    if (now() - lastLeaderRequestTime >= leaderRetryIntervalMS) {
//      if (env.clusterConfig.mode == ClusterMode.Worker) {
//        //        sendTokenBatchRequest(bucketKey, apiKey)
//        FastFuture.successful(0)
//      } else {
//        env.datastores
//          .apiKeyDataStore
//          .processTokenBatchRequest(bucketKey = bucketKey, apiKey = apiKey)
//          .map(tokens => {
//            createLocalBucket(bucketKey, tokens)
//            tokens
//          })
//      }
//    } else {
//      FastFuture.successful(0)
//    }
//  }
//
//  private def createLocalBucket(bucketKey: String, tokens: Long) = {
//    val time = now()
//    val localBucket = LocalBucket(bucketKey, time, tokens = tokens)
//    buckets.put(bucketKey, localBucket)
//    lastLeaderRequestTime = time
//  }

//  def sendTokenBatchRequest(bucketKey: String, tokens: Long): Future[Long] = {
//    println("Calling the Rate Limit Service")
//
//    val time = now()
//    val localBucket = LocalBucket(bucketKey, time, tokens = tokens - 1)
//    buckets.put(bucketKey, localBucket)
//    lastLeaderRequestTime = time
//
//    FastFuture.successful(0)
//  }

  def askForRefill(bucketKey: String): Future[Unit] = {
    buckets.get(bucketKey) match {
      case Some(strategy) => strategy.askForRefill()
      case None => FastFuture.successful(0)
    }
  }

  def consume(bucketKey: String, throttlingStrategy: Option[ThrottlingStrategyConfig]): Boolean = {
    // TODO - apply possible changes on throttling strategy
    buckets.get(bucketKey) match {
      case Some(strategy) => strategy.consume()
      case None =>
        throttlingStrategy match {
          case Some(throttling) =>
            buckets.put(bucketKey, ThrottlingStrategy.apply(throttling)(env))
            consume(bucketKey, throttlingStrategy = throttlingStrategy)
          case None => false
        }
    }
  }
}
