package functional

import com.dimafeng.testcontainers.GenericContainer
import com.typesafe.config.ConfigFactory
import io.vertx.core.json.{JsonArray, JsonObject}
import io.vertx.pgclient.{PgBuilder, PgConnectOptions}
import io.vertx.sqlclient.Tuple
import org.testcontainers.containers.wait.strategy.Wait
import otoroshi.env.Env
import otoroshi.security.IdGenerator
import play.api.Configuration
import play.api.libs.json.{JsObject, JsValue, Json}

import java.util.concurrent.TimeUnit

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

  // writes a row in the table of the datastore without going through the driver
  private def insertRow(key: String, mvalue: JsonArray): Unit = {
    val pool = PgBuilder
      .pool()
      .connectingTo(
        new PgConnectOptions()
          .setHost(container.host)
          .setPort(container.mappedPort(pgPort))
          .setDatabase(pgDatabase)
          .setUser(pgUser)
          .setPassword(pgPassword)
      )
      .build()
    try {
      pool
        .preparedQuery("insert into otoroshi.entities (key, type, mvalue) values ($1, 'hash', $2::jsonb)")
        .execute(Tuple.of(key, mvalue))
        .toCompletionStage
        .toCompletableFuture
        .get(10, TimeUnit.SECONDS)
    } finally {
      pool.close()
    }
  }

  "The postgresql datastore" should {

    // the raw export of a hash is what the leader sends to the workers (#2907)
    "export a hash" in {
      given env: Env = otoroshiComponents.env
      val redis      = env.datastores.redis
      val key        = s"${env.storageRoot}:tests:hash:${IdGenerator.uuid}"

      redis.hset(key, "a", "1").futureValue
      redis.hset(key, "b", "2").futureValue
      val raw = redis.rawGet(key).futureValue.map(_.asInstanceOf[JsValue])
      raw.map(r => (r \ "mvalue").as[JsObject]) mustBe Some(Json.obj("a" -> "1", "b" -> "2"))

      redis.del(key).futureValue
    }

    // the row a previous version of hset wrote: mvalue is an array, the export must not fail on it and the next hset
    // starts the hash over (#2907)
    "read and fix a hash written as an array" in {
      given env: Env = otoroshiComponents.env
      val redis      = env.datastores.redis
      val key        = s"${env.storageRoot}:tests:hash:${IdGenerator.uuid}"

      insertRow(key, new JsonArray().add(new JsonObject()).add(Json.obj(key -> "1").toString))

      redis.rawGet(key).futureValue.isDefined mustBe true
      redis.hgetall(key).futureValue mustBe empty

      redis.hset(key, "a", "1").futureValue
      redis.hgetall(key).futureValue.view.mapValues(_.utf8String).toMap mustBe Map("a" -> "1")

      redis.del(key).futureValue
    }
  }
}
