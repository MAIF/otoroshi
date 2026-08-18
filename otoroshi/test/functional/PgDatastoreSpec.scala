package functional

import com.dimafeng.testcontainers.GenericContainer
import com.typesafe.config.ConfigFactory
import org.testcontainers.containers.wait.strategy.Wait
import play.api.Configuration

/**
 * Boots a real postgresql server in a container and runs otoroshi on top of it with the
 * `experimental-pg` datastore, ie. the reactive-pg driver built on `io.vertx:vertx-pg-client`.
 *
 * Run it alone with:
 *   sbt 'testOnly functional.PgDatastoreSpec'
 */
class PgDatastoreSpec extends ContainerizedDatastoreSpec {

  private val pgUser     = "otoroshi"
  private val pgPassword = "otoroshi"
  private val pgDatabase = "otoroshi"
  private val pgPort     = 5432

  override def storeName: String = "experimental-pg (vertx-pg-client)"

  override lazy val container: GenericContainer = GenericContainer(
    dockerImage = "postgres:16-alpine",
    exposedPorts = Seq(pgPort),
    env = Map(
      "POSTGRES_USER"     -> pgUser,
      "POSTGRES_PASSWORD" -> pgPassword,
      "POSTGRES_DB"       -> pgDatabase
    ),
    // postgres starts, runs its init scripts, then restarts: wait for the second 'ready' line
    waitStrategy = Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2)
  )

  override def getTestConfiguration(configuration: Configuration): Configuration = {
    Configuration(
      ConfigFactory
        .parseString(s"""
           |app.storage = "experimental-pg"
           |app.pg.host = "${container.host}"
           |app.pg.port = ${container.mappedPort(pgPort)}
           |app.pg.database = "$pgDatabase"
           |app.pg.user = "$pgUser"
           |app.pg.password = "$pgPassword"
           |app.pg.poolSize = 10
           |app.pg.logQueries = false
           |""".stripMargin)
        .resolve()
    ).withFallback(configuration)
  }
}
