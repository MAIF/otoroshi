package functional

import org.apache.pekko.util.ByteString
import otoroshi.env.Env
import otoroshi.security.IdGenerator
import otoroshi.storage.RedisLike

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

// setnx is what a writer takes a lock or a reservation with (#2969): of several writers at once, one only is told it
// set the key, and the key holds the value of that writer. A key that expired is set again, a key that did not is not
trait SetnxScenarios { self: OtoroshiSpec =>

  def setnxOneWriterOfMany(redis: RedisLike)(using env: Env): Unit = {
    given scala.concurrent.ExecutionContext = env.otoroshiExecutionContext
    (1 to 5).foreach { round =>
      val key     = s"${env.storageRoot}:tests:setnx:${IdGenerator.uuid}"
      // started together, then awaited
      val writers = (1 to 20).map(i => redis.setnxBS(key, ByteString(s"writer-$i"), Some(60000L)).map(set => (i, set)))
      val results = Future.sequence(writers).futureValue
      val winners = results.collect { case (i, true) => i }
      withClue(s"round $round: ")(winners must have size 1)
      redis.get(key).futureValue.map(_.utf8String) mustBe Some(s"writer-${winners.head}")
      redis.pttl(key).futureValue must be > 0L
      redis.del(key).futureValue
    }
  }

  def setnxExpiredKey(redis: RedisLike)(using env: Env): Unit = {
    val key  = s"${env.storageRoot}:tests:setnx:${IdGenerator.uuid}"
    val held = s"${env.storageRoot}:tests:setnx:${IdGenerator.uuid}"

    redis.setnxBS(key, ByteString("first"), Some(1000L)).futureValue mustBe true
    redis.setnxBS(key, ByteString("second"), Some(1000L)).futureValue mustBe false
    redis.get(key).futureValue.map(_.utf8String) mustBe Some("first")
    await(2500.millis)
    redis.setnxBS(key, ByteString("third"), Some(60000L)).futureValue mustBe true
    redis.get(key).futureValue.map(_.utf8String) mustBe Some("third")
    redis.pttl(key).futureValue must be > 0L
    redis.setnxBS(key, ByteString("fourth"), None).futureValue mustBe false

    // a key set without ttl is held
    redis.setBS(held, ByteString("held"), None, None).futureValue mustBe true
    redis.setnxBS(held, ByteString("other"), Some(1000L)).futureValue mustBe false
    redis.get(held).futureValue.map(_.utf8String) mustBe Some("held")
    // and set again once deleted
    redis.del(held).futureValue
    redis.setnxBS(held, ByteString("other"), None).futureValue mustBe true
    redis.get(held).futureValue.map(_.utf8String) mustBe Some("other")

    redis.del(key, held).futureValue
  }
}
