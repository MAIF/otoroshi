package functional

import com.dimafeng.testcontainers.GenericContainer
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.Eventually
import org.testcontainers.containers.wait.strategy.Wait
import otoroshi.env.Env
import otoroshi.security.IdGenerator
import otoroshi.storage.Healthy
import play.api.Configuration

import java.time.Duration
import scala.concurrent.duration.DurationInt

/**
 * Boots a real cassandra server in a container and runs otoroshi on top of it with the `cassandra` datastore, ie. the
 * driver built on `com.datastax.oss:java-driver-core`, configured by `app.cassandra` in `conf/application.conf`.
 *
 * Run it alone with:
 *   sbt 'testOnly functional.CassandraDatastoreSpec'
 */
class CassandraDatastoreSpec extends ContainerizedDatastoreSpec with Eventually {

  private val cassandraPort = 9042

  override def storeName: String = "cassandra (java-driver-core)"

  override lazy val container: GenericContainer = GenericContainer(
    dockerImage = "cassandra:4.1",
    exposedPorts = Seq(cassandraPort),
    env = Map(
      "MAX_HEAP_SIZE" -> "512M",
      "HEAP_NEWSIZE"  -> "128M"
    ),
    waitStrategy = Wait
      .forLogMessage(".*Starting listening for CQL clients.*\\n", 1)
      .withStartupTimeout(Duration.ofMinutes(3))
  )

  // the contact points are resolved from app.cassandra.host and app.cassandra.port when application.conf is loaded, so
  // they are set here
  override def getTestConfiguration(configuration: Configuration): Configuration = {
    Configuration(
      ConfigFactory
        .parseString(s"""
           |app.storage = "cassandra"
           |app.cassandra.basic.contact-points = [ "${container.host}:${container.mappedPort(cassandraPort)}" ]
           |app.cassandra.basic.load-balancing-policy.local-datacenter = "datacenter1"
           |""".stripMargin)
        .resolve()
    ).withFallback(configuration)
  }

  "The cassandra datastore" should {

    "report the database as healthy" in {
      given env: Env = otoroshiComponents.env
      env.datastores.health()(using env.otoroshiExecutionContext).futureValue mustBe Healthy
    }

    // the expirations table holds the ttl of the keys, and the cleanup of the expired keys, every 5 seconds, deletes
    // them whatever their type: a key expires at most a few seconds late, never early. A key set again without ttl,
    // or deleted then set again, has no ttl anymore and is not deleted, a key whose ttl is set again is deleted later
    "expire the keys whose ttl is over, and only them" in {
      given env: Env = otoroshiComponents.env
      val redis      = env.datastores.redis
      val prefix     = s"${env.storageRoot}:tests:ttl:${IdGenerator.uuid}"
      val withTtl    = s"$prefix:with-ttl"
      val string     = s"$prefix:string"
      val hash       = s"$prefix:hash"
      val persisted  = s"$prefix:persisted"
      val recreated  = s"$prefix:recreated"
      val renewed    = s"$prefix:renewed"

      redis.set(persisted, "first", pxMilliseconds = Some(1000L)).futureValue mustBe true
      redis.set(persisted, "second").futureValue mustBe true
      redis.pttl(persisted).futureValue mustBe -1L
      redis.set(recreated, "first", pxMilliseconds = Some(1000L)).futureValue mustBe true
      redis.del(recreated).futureValue
      redis.set(recreated, "second").futureValue mustBe true
      redis.pttl(recreated).futureValue mustBe -1L
      redis.set(renewed, "renewed", pxMilliseconds = Some(1000L)).futureValue mustBe true
      redis.pexpire(renewed, 60000L).futureValue mustBe true

      redis.set(withTtl, "expires", pxMilliseconds = Some(1000L)).futureValue mustBe true
      redis.set(string, "expires").futureValue mustBe true
      redis.pexpire(string, 1000L).futureValue mustBe true
      redis.hset(hash, "a", "1").futureValue
      redis.pexpire(hash, 1000L).futureValue mustBe true
      redis.get(withTtl).futureValue.map(_.utf8String) mustBe Some("expires")
      redis.exists(string).futureValue mustBe true
      redis.exists(hash).futureValue mustBe true

      eventually(timeout(15.seconds), interval(500.millis)) {
        redis.exists(withTtl).futureValue mustBe false
        redis.exists(string).futureValue mustBe false
        redis.exists(hash).futureValue mustBe false
      }
      redis.get(withTtl).futureValue mustBe None
      redis.get(string).futureValue mustBe None
      redis.hgetall(hash).futureValue mustBe empty

      // the cleanup that deleted the keys above saw the former ttl of the other ones expire too
      await(1.second)
      redis.get(persisted).futureValue.map(_.utf8String) mustBe Some("second")
      redis.get(recreated).futureValue.map(_.utf8String) mustBe Some("second")
      redis.get(renewed).futureValue.map(_.utf8String) mustBe Some("renewed")
      redis.pttl(renewed).futureValue must be > 0L

      redis.del(withTtl, string, hash, persisted, recreated, renewed).futureValue
    }
  }
}
