package functional

import com.dimafeng.testcontainers.GenericContainer
import com.typesafe.config.ConfigFactory
import org.testcontainers.containers.wait.strategy.Wait
import play.api.Configuration
import plugins.ApiAliasKeysTests

// the subscriptions created at once reserve with setnx, that has to be atomic on the postgresql datastore too (#2969):
// sbt "testOnly functional.ApiAliasKeysPgSpec"
class ApiAliasKeysPgSpec extends PluginsTestSpecBase {

  private val pgPort = 5432

  lazy val container: GenericContainer = GenericContainer(
    dockerImage = "postgres:16-alpine",
    exposedPorts = Seq(pgPort),
    env = Map(
      "POSTGRES_USER"     -> "otoroshi",
      "POSTGRES_PASSWORD" -> "otoroshi",
      "POSTGRES_DB"       -> "otoroshi"
    ),
    // postgres starts, runs its init scripts, then restarts: wait for the second 'ready' line
    waitStrategy = Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2)
  )

  override def configurationSpec: Configuration = Configuration(
    ConfigFactory
      .parseString(s"""
         |app.storage = "experimental-pg"
         |app.pg.host = "${container.host}"
         |app.pg.port = ${container.mappedPort(pgPort)}
         |app.pg.database = "otoroshi"
         |app.pg.user = "otoroshi"
         |app.pg.password = "otoroshi"
         |app.pg.poolSize = 10
         |""".stripMargin)
      .resolve()
  )

  override def beforeAll(): Unit = {
    container.start()
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    super.afterAll()
    container.stop()
  }

  lazy val aliases = new ApiAliasKeysTests(this)

  s"alias keys on the postgresql datastore" should {
    "share one credential when the subscriptions of an owner are created at once" in {
      aliases.subscriptionsCreatedAtOnce()
    }
    "be minted once when a credential is shared on one api by subscriptions created at once" in {
      aliases.sameApiAtOnce()
    }
  }
}
