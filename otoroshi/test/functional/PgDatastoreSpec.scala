package functional

import com.dimafeng.testcontainers.GenericContainer
import com.typesafe.config.ConfigFactory
import io.vertx.core.json.{JsonArray, JsonObject}
import io.vertx.pgclient.{PgBuilder, PgConnectOptions}
import io.vertx.sqlclient.{Pool, Row, RowSet, Tuple}
import org.apache.pekko.util.ByteString
import org.testcontainers.containers.wait.strategy.Wait
import otoroshi.env.Env
import otoroshi.models.{Tenant, TenantId}
import otoroshi.security.IdGenerator
import otoroshi.storage.drivers.reactivepg.ReactivePgRedis
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
  private def insertRow(key: String, mvalue: JsonArray): Unit =
    runSql("insert into otoroshi.entities (key, type, mvalue) values ($1, 'hash', $2::jsonb)", Tuple.of(key, mvalue))

  // the kind and the jvalue of the row of a key: the jvalue is what the optimized reads of the entities return
  private def entityColumns(key: String): (String, JsValue) = {
    val row = runSql("select kind, jvalue from otoroshi.entities where key = $1", Tuple.of(key)).iterator().next()
    (row.getString("kind"), Json.parse(row.getJsonObject("jvalue").encode()))
  }

  private def newPool(): Pool =
    PgBuilder
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

  private def runSql(query: String, params: Tuple): RowSet[Row] = {
    val pool = newPool()
    try {
      pool
        .preparedQuery(query)
        .execute(params)
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

    // the cleanup job deletes the expired rows every few seconds: until it does, the row of an expired key is there,
    // and setnx takes it over as if the key was absent (#2969)
    "set a key whose expired row is not deleted yet" in {
      given env: Env = otoroshiComponents.env
      val store      = env.datastores.rawDataStore
      val key        = s"${env.storageRoot}:tests:setnx:${IdGenerator.uuid}"

      runSql(
        "insert into otoroshi.entities (key, type, value, ttl_starting_at, ttl) values ($1, 'string', 'expired', NOW() - interval '1 hour', '1 second'::interval)",
        Tuple.of(key)
      )
      store.exists(key).futureValue mustBe false

      store.setnx(key, ByteString("fresh"), Some(60000L)).futureValue mustBe true
      store.get(key).futureValue.map(_.utf8String) mustBe Some("fresh")
      store.pttl(key).futureValue must (be > 0L and be <= 60000L)
      store.setnx(key, ByteString("other"), Some(60000L)).futureValue mustBe false
      store.get(key).futureValue.map(_.utf8String) mustBe Some("fresh")

      store.del(Seq(key)).futureValue
    }

    // values are bound as they are: a single quote must not come back doubled
    "store raw values with single quotes as they are" in {
      given env: Env = otoroshiComponents.env
      val store      = env.datastores.rawDataStore
      val key        = s"${env.storageRoot}:tests:quotes:${IdGenerator.uuid}"

      store.set(key, ByteString("it's"), None).futureValue mustBe true
      store.get(key).futureValue.map(_.utf8String) mustBe Some("it's")
      store.set(key, ByteString("it's 'quoted'"), Some(60000L)).futureValue mustBe true
      store.get(key).futureValue.map(_.utf8String) mustBe Some("it's 'quoted'")

      store.del(Seq(key)).futureValue
    }

    // the insert of an entity and its update write the same jvalue, the one the optimized reads (findAll) return
    "store entities with single quotes as they are" in {
      given env: Env = otoroshiComponents.env
      val tenants    = env.datastores.tenantDataStore
      val id         = s"tenant-quotes-${IdGenerator.uuid}"
      val key        = tenants.key(id)
      val created    = Tenant(TenantId(id), "it's", "default-src 'self'", metadata = Map("it's" -> "'self'"))
      val updated    = created.copy(name = "it's updated")

      tenants.set(created).futureValue mustBe true
      val (kind, inserted) = entityColumns(key)
      kind must not be empty
      inserted mustBe created.json

      tenants.set(updated).futureValue mustBe true
      entityColumns(key)._2 mustBe updated.json
      tenants.findById(id).futureValue.map(_.json) mustBe Some(updated.json)
      tenants.findAll(force = true).futureValue.find(_.id.value == id).map(_.json) mustBe Some(updated.json)

      // what fills the kind of a row written without one
      env.datastores.redis.asInstanceOf[ReactivePgRedis].setEntityKind(key, kind, Json.stringify(created.json)).futureValue
      entityColumns(key) mustBe (kind, created.json)

      tenants.delete(id).futureValue
    }

    "store hashes, lists and sets with single quotes as they are" in {
      given env: Env = otoroshiComponents.env
      val pool       = newPool()
      try {
        // with avoidJsonPath (cockroachdb has no jsonb_path_query_array), ltrim writes the trimmed list itself
        val withoutJsonPath =
          new ReactivePgRedis(pool, env.otoroshiActorSystem, env, "otoroshi.entities", true, avoidJsonPath = true)
        Seq(env.datastores.redis, withoutJsonPath).foreach { redis =>
          val key = s"${env.storageRoot}:tests:quotes:${IdGenerator.uuid}"

          redis.hset(s"$key:hash", "it's", "a 'value'").futureValue
          redis.hgetall(s"$key:hash").futureValue.view.mapValues(_.utf8String).toMap mustBe Map("it's" -> "a 'value'")

          redis.lpush(s"$key:list", "it's", "a 'value'", "'").futureValue
          redis.ltrim(s"$key:list", 0, 10).futureValue mustBe true
          redis.lrange(s"$key:list", 0, 10).futureValue.map(_.utf8String) mustBe Seq("it's", "a 'value'", "'")

          redis.sadd(s"$key:set", "it's").futureValue
          redis.sismember(s"$key:set", "it's").futureValue mustBe true
          redis.smembers(s"$key:set").futureValue.map(_.utf8String) mustBe Seq("it's")

          redis.del(s"$key:hash", s"$key:list", s"$key:set").futureValue
        }
      } finally {
        pool.close()
      }
    }
  }
}
