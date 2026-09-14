package functional

import com.dimafeng.testcontainers.{ForAllTestContainer, GenericContainer}
import io.vertx.pgclient.{PgBuilder, PgConnectOptions}
import io.vertx.sqlclient.{Pool, PoolOptions, Tuple => VertxTuple}
import org.scalatest.OptionValues
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.testcontainers.containers.wait.strategy.Wait
import otoroshi.next.analytics.exporter.*
import otoroshi.storage.drivers.reactivepg.pgimplicits.*
import play.api.libs.json.*

import java.time.OffsetDateTime
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * The projection refactor against a real postgresql.
 *
 * The pure-logic spec proves the routing; this proves the parts only a database can: that every
 * projection's DDL is valid, that its `insertSql` and `toTuple` agree on arity, and that the
 * retention purge deletes from the tables it now claims to — including the alert log, which was
 * never pruned before.
 *
 *   sbt 'testOnly functional.AnalyticsProjectionPgSpec'
 */
class AnalyticsProjectionPgSpec
    extends org.scalatest.wordspec.AnyWordSpec
    with org.scalatest.matchers.must.Matchers
    with OptionValues
    with ScalaFutures
    with IntegrationPatience
    with ForAllTestContainer {

  private val pgUser     = "otoroshi"
  private val pgPassword = "otoroshi"
  private val pgDatabase = "otoroshi"
  private val pgPort     = 5432

  override val container: GenericContainer = GenericContainer(
    dockerImage = "postgres:16-alpine",
    exposedPorts = Seq(pgPort),
    env = Map(
      "POSTGRES_USER"     -> pgUser,
      "POSTGRES_PASSWORD" -> pgPassword,
      "POSTGRES_DB"       -> pgDatabase
    ),
    waitStrategy = Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2)
  )

  private given ec: ExecutionContext = ExecutionContext.global

  private lazy val settings = UserAnalyticsExporterSettings(
    host = container.host,
    port = container.mappedPort(pgPort),
    database = pgDatabase,
    user = pgUser,
    password = pgPassword,
    retentionDays = 30
  )

  private lazy val pool: Pool = PgBuilder
    .pool()
    .connectingTo(
      new PgConnectOptions()
        .setHost(settings.host)
        .setPort(settings.port)
        .setDatabase(settings.database)
        .setUser(settings.user)
        .setPassword(settings.password)
    )
    .`with`(new PoolOptions().setMaxSize(4))
    .build()

  /** An extension's projection: its own table, its own strip, the shared column contract. */
  private object CustomProjection extends AnalyticsProjection {
    override val id                                              = "test.custom-events"
    override def accepts(event: JsValue): Boolean                =
      (event \ "@type").asOpt[String].contains("CustomSecurityEvent")
    override def table(s: UserAnalyticsExporterSettings): String = s.prefixedTable("custom")
    override def createTableSql(s: UserAnalyticsExporterSettings): String =
      s"""CREATE TABLE IF NOT EXISTS ${table(s)} (
         |${AnalyticsProjection.commonColumns}
         |  verdict         TEXT,
         |  raw             JSONB       NOT NULL DEFAULT '{}'::jsonb
         |);""".stripMargin
    override def indexStatements(s: UserAnalyticsExporterSettings): Seq[String] =
      AnalyticsProjection.commonIndexes(table(s), s"${s.tablePrefix}_custom")
    override def insertSql(s: UserAnalyticsExporterSettings): String =
      s"""INSERT INTO ${table(s)} (id, ts, env, tenant, teams, route_id, route_name, apikey_id, user_email, from_ip, verdict, raw)
         |VALUES ($$1, $$2, $$3, $$4, $$5, $$6, $$7, $$8, $$9, $$10, $$11, $$12::jsonb)""".stripMargin
    // the bulky field must be gone by the time the row is built, without toTuple doing it
    override def strip(event: JsValue): JsValue = event.as[JsObject] - "signals"
    override def toTuple(event: JsValue): VertxTuple = VertxTuple.of(
      (event \ "@id").as[String],
      OffsetDateTime.now(),
      "prod",
      "default",
      Array.empty[String],
      (event \ "route_id").asOpt[String].orNull,
      (event \ "route_name").asOpt[String].orNull,
      null,
      null,
      (event \ "from").asOpt[String].orNull,
      (event \ "verdict").asOpt[String].orNull,
      Json.stringify(event)
    )
  }

  private val projections = AnalyticsProjection.resolve(Seq(CustomProjection))

  private def await[A](f: Future[A]): A = Await.result(f, 60.seconds)

  private def count(table: String): Int =
    await(pool.query(s"SELECT COUNT(*) AS c FROM $table").executeAsync().map(_.iterator().next().getInteger("c")))

  private def insert(projection: AnalyticsProjection, events: Seq[JsValue]): Unit = {
    val rows: java.util.List[VertxTuple] = events.map(e => projection.toTuple(projection.strip(e))).asJava
    await(pool.preparedQuery(projection.insertSql(settings)).executeBatch(rows).scala.map(_ => ()))
  }

  private def purge(projection: AnalyticsProjection, days: Int): Int =
    await(
      pool
        .query(
          s"DELETE FROM ${projection.table(settings)} WHERE ${projection.retentionColumn} < NOW() - INTERVAL '$days days'"
        )
        .executeAsync()
        .map(_.rowCount())
    )

  private def now: Long = System.currentTimeMillis()

  private val gatewayEvent: JsObject = Json
    .parse(s"""{
      |  "@id": "event_42", "@timestamp": $now, "@type": "GatewayEvent", "@env": "prod",
      |  "@service": "MyApp", "@serviceId": "route_xxx", "method": "GET", "status": 502,
      |  "url": "https://example.com/path", "protocol": "HTTP/1.1", "duration": 121, "overhead": 20,
      |  "backendDuration": 0, "err": true, "from": "1.2.3.4",
      |  "data": { "dataIn": 100, "dataOut": 200 },
      |  "to": { "uri": "/", "host": "api.example.com", "scheme": "https" },
      |  "headers": [ { "key": "X-A", "value": "1" } ],
      |  "identity": { "identityType": "APIKEY", "identity": "apikey_42", "label": "demo" },
      |  "geolocationInfo": { "country": "FR" }, "userAgentInfo": { "ua": "curl/8.0" },
      |  "route": { "id": "route_xxx", "name": "MyRoute",
      |    "_loc": { "tenant": "acme", "teams": ["red"] },
      |    "metadata": {}, "frontend": { "domains": ["example.com"] } }
      |}""".stripMargin)
    .as[JsObject]

  private def alertEvent(id: String): JsObject = Json.obj(
    "@id"              -> id,
    "@timestamp"       -> now,
    "@type"            -> "AlertEvent",
    "alertSubcategory" -> "user-analytics",
    "alert_id"         -> "alert_1",
    "alert_name"       -> "too many errors",
    "severity"         -> "high",
    "message"          -> "boom"
  )

  private def customEvent(id: String): JsObject = Json.obj(
    "@id"        -> id,
    "@type"      -> "CustomSecurityEvent",
    "route_id"   -> "route_xxx",
    "route_name" -> "MyRoute",
    "from"       -> "9.9.9.9",
    "verdict"    -> "blocked",
    "signals"    -> Json.arr("a" * 128)
  )

  "The projection schema" should {

    "create every projection's table, core and extension alike" taggedAs Docker in {
      await(AnalyticsSchema.migrate(pool, settings, projections))
      projections.foreach(p => count(p.table(settings)) mustBe 0)
    }

    "be safe to run twice" taggedAs Docker in {
      await(AnalyticsSchema.migrate(pool, settings, projections))
      projections.foreach(p => count(p.table(settings)) mustBe 0)
    }
  }

  "Insertion through a projection" should {

    "write a gateway event to the table it always went to" taggedAs Docker in {
      insert(GatewayEventProjection, Seq(gatewayEvent))
      count(AnalyticsSchema.fullTable(settings)) mustBe 1
    }

    "write a user-analytics alert to the alert log" taggedAs Docker in {
      insert(FiredAlertProjection, Seq(alertEvent("fa_1"), alertEvent("fa_2")))
      count(AnalyticsSchema.firedAlertsTable(settings)) mustBe 2
    }

    "write an extension's event to the extension's own table" taggedAs Docker in {
      insert(CustomProjection, Seq(customEvent("c_1")))
      count(CustomProjection.table(settings)) mustBe 1
    }

    "have applied the projection's strip before the row was built" taggedAs Docker in {
      val raw = await(
        pool
          .query(s"SELECT raw FROM ${CustomProjection.table(settings)} WHERE id = 'c_1'")
          .executeAsync()
          .map(_.iterator().next().getValue("raw").toString)
      )
      raw must include("verdict")
      raw must not include "signals"
    }
  }

  "The retention purge" should {

    "delete nothing while the rows are inside the window" taggedAs Docker in {
      projections.filter(_.retention).foreach(p => purge(p, 30) mustBe 0)
      count(AnalyticsSchema.fullTable(settings)) mustBe 1
      count(AnalyticsSchema.firedAlertsTable(settings)) mustBe 2
    }

    "prune the alert log, which nothing pruned before" taggedAs Docker in {
      FiredAlertProjection.retention mustBe true
      // everything written above is "now", so a zero-day window takes all of it
      purge(FiredAlertProjection, 0) mustBe 2
      count(AnalyticsSchema.firedAlertsTable(settings)) mustBe 0
    }

    "prune an extension's table too" taggedAs Docker in {
      purge(CustomProjection, 0) mustBe 1
      count(CustomProjection.table(settings)) mustBe 0
    }

    "still prune the events table" taggedAs Docker in {
      purge(GatewayEventProjection, 0) mustBe 1
      count(AnalyticsSchema.fullTable(settings)) mustBe 0
    }
  }
}
