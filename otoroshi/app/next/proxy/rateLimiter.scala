package otoroshi.next.proxy

import akka.http.scaladsl.util.FastFuture
import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.models.OtoroshiAdmin
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.syntax.implicits.BetterSyntax

import scala.concurrent.Future

case class LocalBucket(
                        key: String,
                        maxTokens: Long,
                        lastRefillTime: Long,
                        var tokens: Long = 0,
                        isBlocked: Boolean = false)

case class TokenBatchRequest(
                              key: String,
                              requestedTokens: Int,
                              timestamp: Long)

class RateLimiter(env: Env) {

  val leaderRetryIntervalMS: Long = 50
  val buckets = new UnboundedTrieMap[String, LocalBucket]()

  var lastLeaderRequestTime = DateTime.now().getMillis

  def now() = DateTime.now().getMillis

  def checkEmptyBucket(bucketKey: String, maxTokens: Long): Future[Any] = {
    if (now() - lastLeaderRequestTime >= leaderRetryIntervalMS) {
      sendTokenBatchRequest(bucketKey, maxTokens)
    } else
      FastFuture.successful(())
  }

  def sendTokenBatchRequest(bucketKey: String, maxTokens: Long): Future[Unit] = {
    println("Calling the Rate Limit Service")

    val accepted = Math.random() > .5 // TODO

    if (accepted) {
      println("The RLS has accepted the request")
      val time = now()
      val localBucket = LocalBucket(bucketKey, maxTokens, time, tokens = maxTokens - 1)
      buckets.put(bucketKey, localBucket)
      lastLeaderRequestTime = time
    }

    FastFuture.successful(())
  }

  def consume(bucketKey: String): Boolean = {
    buckets.get(bucketKey) match {
      case Some(bucket) if bucket.tokens >= 1 =>
        bucket.tokens -= 1
        println("remaining tokens : ", bucket.tokens)
        true
      case Some(bucket) => bucket.isBlocked
      case None => false
    }
  }
}
