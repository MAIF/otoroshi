package functional

import com.dimafeng.testcontainers.GenericContainer
import com.typesafe.config.ConfigFactory
import org.testcontainers.containers.wait.strategy.Wait
import play.api.Configuration

/**
 * Boots a real redis server in a container and runs otoroshi on top of it with the `lettuce`
 * datastore, ie. the driver built on `io.lettuce:lettuce-core`.
 *
 * Run it alone with:
 *   sbt 'testOnly functional.LettuceDatastoreSpec'
 */
class LettuceDatastoreSpec extends ContainerizedDatastoreSpec {

  private val redisPort = 6379

  override def storeName: String = "lettuce (lettuce-core)"

  override lazy val container: GenericContainer = GenericContainer(
    dockerImage = "redis:7-alpine",
    exposedPorts = Seq(redisPort),
    waitStrategy = Wait.forLogMessage(".*Ready to accept connections.*\\n", 1)
  )

  override def getTestConfiguration(configuration: Configuration): Configuration = {
    Configuration(
      ConfigFactory
        .parseString(s"""
           |app.storage = "lettuce"
           |app.redis.lettuce.connection = "standalone"
           |app.redis.lettuce.uri = "redis://${container.host}:${container.mappedPort(redisPort)}/0"
           |app.redis.lettuce.pooling.enabled = false
           |""".stripMargin)
        .resolve()
    ).withFallback(configuration)
  }
}
