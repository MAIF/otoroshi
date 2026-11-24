package functional

import com.dimafeng.testcontainers.GenericContainer
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import org.apache.pekko.stream.Materializer
import org.scalatest.BeforeAndAfterAll
import org.testcontainers.containers.wait.strategy.Wait
import otoroshi.env.Env
import otoroshi.storage.drivers.cassandra.NewCassandraRedis
import play.api.Configuration

import java.time.Duration
import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters.*

/**
 * Boots a real cassandra server in a container and runs the scenarios of the datastore driver on the `cassandra`
 * driver, built on `com.datastax.oss:java-driver-core` with the reference configuration of the java driver. Otoroshi
 * itself runs on the in memory datastore.
 *
 * Run it alone with:
 *   sbt 'testOnly functional.CassandraDriverSpec'
 */
class CassandraDriverSpec extends OtoroshiSpec with BeforeAndAfterAll with SetnxScenarios {

  private val cassandraPort = 9042

  lazy val container: GenericContainer = GenericContainer(
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

  lazy val cassandra: NewCassandraRedis = {
    given env: Env              = otoroshiComponents.env
    given ExecutionContext      = env.otoroshiExecutionContext
    given Materializer          = env.otoroshiMaterializer
    val driver                  = ConfigFactory
      .defaultReference()
      .getConfig("datastax-java-driver")
      .withValue(
        "basic.contact-points",
        ConfigValueFactory.fromIterable(Seq(s"${container.host}:${container.mappedPort(cassandraPort)}").asJava)
      )
      .withValue("basic.load-balancing-policy.local-datacenter", ConfigValueFactory.fromAnyRef("datacenter1"))
      .withValue("basic.request.timeout", ConfigValueFactory.fromAnyRef("10 seconds"))
    new NewCassandraRedis(
      env.otoroshiActorSystem,
      Configuration(ConfigFactory.empty().withValue("app.cassandra", driver.root()))
    )
  }

  override def getTestConfiguration(configuration: Configuration): Configuration = configuration

  override def beforeAll(): Unit = {
    container.start()
    startOtoroshi()
    cassandra.start()
  }

  override def afterAll(): Unit = {
    cassandra.stop()
    stopAll()
    container.stop()
  }

  "The cassandra datastore driver" should {

    "set a key for one of the writers that set it at once" in {
      given env: Env = otoroshiComponents.env
      setnxOneWriterOfMany(cassandra)
    }

    "set a key again once it expired" in {
      given env: Env = otoroshiComponents.env
      setnxExpiredKey(cassandra)
    }
  }
}
