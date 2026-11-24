package functional

import org.scalatest.BeforeAndAfterAll
import otoroshi.env.Env
import play.api.Configuration

/**
 * The scenarios of the containerized datastores that matter for the in memory one, the datastore of the other specs.
 *
 * Run it alone with:
 *   sbt 'testOnly functional.InMemoryDatastoreSpec'
 */
class InMemoryDatastoreSpec extends OtoroshiSpec with BeforeAndAfterAll with SetnxScenarios {

  override def getTestConfiguration(configuration: Configuration): Configuration = configuration

  override def beforeAll(): Unit = startOtoroshi()

  override def afterAll(): Unit = stopAll()

  "Otoroshi with the in memory datastore" should {

    "set a key for one of the writers that set it at once" in {
      given env: Env = otoroshiComponents.env
      setnxOneWriterOfMany(env.datastores.redis)
    }

    "set a key again once it expired" in {
      given env: Env = otoroshiComponents.env
      setnxExpiredKey(env.datastores.redis)
    }
  }
}
