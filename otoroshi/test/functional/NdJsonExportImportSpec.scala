package functional

import org.apache.pekko.stream.scaladsl.{Sink, Source}
import otoroshi.env.Env
import otoroshi.storage.drivers.inmemory.InMemoryDataStores
import play.api.Configuration
import play.api.libs.json.*

import scala.concurrent.duration.*

// a full ndjson export of the datastore imports back as it was: the values of hashes, lists and sets, the order of
// the lists and the expiration of the keys
class NdJsonExportImportSpec extends OtoroshiSpec {

  override def getTestConfiguration(configuration: Configuration): Configuration = configuration

  "A full ndjson export" should {
    "warm up" in {
      startOtoroshi()
    }
    "import back as it was" in {
      given env: Env = otoroshiComponents.env
      given ec: scala.concurrent.ExecutionContext                 = env.otoroshiExecutionContext
      given mat: org.apache.pekko.stream.Materializer             = env.otoroshiMaterializer
      val redis                                                   = env.datastores.asInstanceOf[InMemoryDataStores].redis
      val prefix                                                  = s"${env.storageRoot}:ndjson-test"
      // like redis, a lpush of several values puts the last one first
      redis.set(s"$prefix:string", "hello").futureValue
      redis.set(s"$prefix:expiring", "soon").futureValue
      redis.pexpire(s"$prefix:expiring", 1.hour.toMillis).futureValue
      redis.hset(s"$prefix:hash", "plain", "value").futureValue
      redis.hset(s"$prefix:hash", "json", """{"a":1}""").futureValue
      redis.lpush(s"$prefix:list", "third", "second", "first").futureValue
      redis.sadd(s"$prefix:set", "one", "two").futureValue
      redis.lrange(s"$prefix:list", 0, -1).futureValue.map(_.utf8String) mustBe Seq("first", "second", "third")

      val lines = env.datastores.fullNdJsonExport(100, 1, 4).futureValue.runWith(Sink.seq).futureValue
      env.datastores.fullNdJsonImport(Source(lines.toList)).futureValue

      redis.get(s"$prefix:string").futureValue.map(_.utf8String) mustBe Some("hello")
      redis.get(s"$prefix:expiring").futureValue.map(_.utf8String) mustBe Some("soon")
      val ttl = redis.pttl(s"$prefix:expiring").futureValue
      withClue(s"ttl $ttl")(ttl must (be > 0L and be <= 1.hour.toMillis))
      redis.pttl(s"$prefix:string").futureValue mustBe -1L
      redis.hgetall(s"$prefix:hash").futureValue.view.mapValues(_.utf8String).toMap mustBe Map(
        "plain" -> "value",
        "json"  -> """{"a":1}"""
      )
      redis.lrange(s"$prefix:list", 0, -1).futureValue.map(_.utf8String) mustBe Seq("first", "second", "third")
      redis.smembers(s"$prefix:set").futureValue.map(_.utf8String).toSet mustBe Set("one", "two")
    }
    "shutdown" in {
      stopAll()
    }
  }
}
