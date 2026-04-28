package otoroshi.next.analytics.migration

import akka.http.scaladsl.util.FastFuture
import io.vertx.pgclient.{PgConnectOptions, PgPool, SslMode}
import io.vertx.sqlclient.{PoolOptions, Row, Tuple => VertxTuple}
import otoroshi.env.Env
import otoroshi.models.PostgresExporterSettings
import otoroshi.next.analytics.exporter.{
  AnalyticsSchema,
  EventDenormalizer,
  EventStripper,
  UserAnalyticsExporterRegistry
}
import otoroshi.storage.drivers.reactivepg.pgimplicits._
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
 * One-shot migrator from a legacy `postgresql` data exporter table
 * (single-column JSONB events) to the active user-analytics table
 * (denormalized columns + indexes).
 *
 * Pagination is keyset on the `id` TEXT column to avoid OFFSET cost on
 * very large tables. Idempotent thanks to `ON CONFLICT (id) DO NOTHING`.
 */
object LegacyPgMigrator {

  private val logger = Logger("otoroshi-user-analytics-migrator")

  case class MigrationResult(
      sourceTable: String,
      targetTable: String,
      sourceCount: Long,
      processed: Long,
      inserted: Long,
      dryRun: Boolean
  ) {
    def toJson: JsObject = Json.obj(
      "source_table" -> sourceTable,
      "target_table" -> targetTable,
      "source_count" -> sourceCount,
      "processed"    -> processed,
      "inserted"     -> inserted,
      "dry_run"      -> dryRun
    )
  }

  def parseSourceFromJson(json: JsValue): Either[String, PostgresExporterSettings] =
    PostgresExporterSettings.format.reads(json) match {
      case JsSuccess(s, _) => Right(s)
      case JsError(errs)   => Left(errs.toString)
    }

  private def buildConnectOptions(s: PostgresExporterSettings): PgConnectOptions = {
    s.uri match {
      case Some(uri) => PgConnectOptions.fromUri(uri)
      case None      =>
        new PgConnectOptions()
          .setHost(s.host)
          .setPort(s.port)
          .setDatabase(s.database)
          .setUser(s.user)
          .setPassword(s.password)
          .applyOnIf(s.ssl)(_.setSslMode(SslMode.REQUIRE))
    }
  }

  private def parseEvent(row: Row): Option[JsValue] = {
    Try {
      val obj = row.getJsonObject("event")
      Json.parse(obj.encode())
    }.toOption
      .orElse(Try {
        val s = row.getString("event")
        if (s == null) JsNull else Json.parse(s)
      }.toOption)
  }

  /**
   * Run the migration.
   * @param source legacy PG settings (host/port/db/user/pwd/schema/table)
   * @param batchSize number of rows fetched + inserted per round-trip
   * @param dryRun if true, do not insert; just count source rows
   */
  def migrate(source: PostgresExporterSettings, batchSize: Int, dryRun: Boolean)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Either[String, MigrationResult]] = {
    val sourceTable = s"${source.schema}.${source.table}"
    val sourcePool  = PgPool.pool(buildConnectOptions(source), new PoolOptions().setMaxSize(2))

    val targetFut: Future[Either[String, (String, PgPool, otoroshi.next.analytics.exporter.UserAnalyticsExporterSettings)]] =
      UserAnalyticsExporterRegistry.activeRunning.map {
        case None                   => Left("no active user-analytics exporter")
        case Some((settings, pool)) =>
          Right((AnalyticsSchema.fullTable(settings), pool, settings))
      }

    val countFut: Future[Long] =
      sourcePool
        .query(s"SELECT COUNT(*) AS c FROM $sourceTable")
        .executeAsync()
        .map { rs =>
          val it = rs.iterator()
          if (it.hasNext) {
            val r = it.next()
            r.getValue("c") match {
              case n: java.lang.Number => n.longValue()
              case _                   => 0L
            }
          } else 0L
        }

    val program: Future[Either[String, MigrationResult]] = for {
      total  <- countFut
      target <- targetFut
      out    <- target match {
                  case Left(err)                                  =>
                    Future.successful(Left(err): Either[String, MigrationResult])
                  case Right((targetTable, targetPool, settings)) =>
                    if (dryRun) {
                      Future.successful(
                        Right(
                          MigrationResult(sourceTable, targetTable, total, 0L, 0L, dryRun = true)
                        )
                      )
                    } else {
                      val insertSql = EventDenormalizer.insertSql(settings)
                      logger.info(s"[user-analytics-migrator] starting migration $sourceTable → $targetTable, $total source rows")
                      runBatches(sourcePool, sourceTable, targetPool, insertSql, batchSize, lastId = "", processed = 0L, inserted = 0L)
                        .map { case (processed, inserted) =>
                          Right(MigrationResult(sourceTable, targetTable, total, processed, inserted, dryRun = false))
                        }
                    }
                }
    } yield out

    program.andThen { case _ => sourcePool.close() }
  }

  /** Recursively page through the source by keyset on `id`. */
  private def runBatches(
      sourcePool: PgPool,
      sourceTable: String,
      targetPool: PgPool,
      insertSql: String,
      batchSize: Int,
      lastId: String,
      processed: Long,
      inserted: Long
  )(implicit env: Env, ec: ExecutionContext): Future[(Long, Long)] = {
    val sql =
      s"SELECT id, event FROM $sourceTable WHERE id > $$1 ORDER BY id ASC LIMIT $$2"
    sourcePool
      .preparedQuery(sql)
      .execute(VertxTuple.from(Array[AnyRef](lastId, java.lang.Integer.valueOf(batchSize))))
      .scala
      .flatMap { rs =>
        val rows = rs.iterator().asScala.toList
        if (rows.isEmpty) {
          logger.info(s"[user-analytics-migrator] done: processed=$processed inserted=$inserted")
          Future.successful((processed, inserted))
        } else {
          val newLastId = rows.last.getString("id")
          val tuples: java.util.List[VertxTuple] = rows
            .flatMap { r =>
              parseEvent(r).map { ev =>
                val stripped = EventStripper.stripGatewayEvent(ev)
                val drow     = EventDenormalizer.extractColumns(stripped)
                EventDenormalizer.toTuple(drow)
              }
            }
            .asJava
          if (tuples.isEmpty) {
            runBatches(sourcePool, sourceTable, targetPool, insertSql, batchSize, newLastId, processed + rows.size, inserted)
          } else {
            targetPool
              .preparedQuery(insertSql)
              .executeBatch(tuples)
              .scala
              .flatMap { _ =>
                val nextProcessed = processed + rows.size
                val nextInserted  = inserted + tuples.size()
                if (nextProcessed % 50000 == 0 || rows.size < batchSize) {
                  logger.info(
                    s"[user-analytics-migrator] progress: processed=$nextProcessed inserted=$nextInserted lastId=$newLastId"
                  )
                }
                runBatches(sourcePool, sourceTable, targetPool, insertSql, batchSize, newLastId, nextProcessed, nextInserted)
              }
          }
        }
      }
  }
}
