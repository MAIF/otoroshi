package otoroshi.next.analytics.exporter

import org.apache.pekko.http.scaladsl.util.FastFuture
import io.vertx.pgclient.{PgBuilder, PgConnectOptions, SslMode}
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PoolOptions
import otoroshi.env.Env
import otoroshi.next.plugins.api.NgPluginCategory
import otoroshi.script.{Job, JobContext, JobId, JobInstantiation, JobKind, JobStarting, JobVisibility}
import otoroshi.storage.drivers.reactivepg.pgimplicits.*
import otoroshi.utils.syntax.implicits.*
import play.api.Logger

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}

class AnalyticsRetentionJob extends Job {

  private val logger = Logger("otoroshi-user-analytics-retention-job")

  override def categories: Seq[NgPluginCategory] = Seq.empty

  override def uniqueId: JobId = JobId("io.otoroshi.next.analytics.AnalyticsRetentionJob")

  override def name: String = "Otoroshi user-analytics retention job"

  override def jobVisibility: JobVisibility = JobVisibility.Internal

  override def kind: JobKind = JobKind.ScheduledEvery

  override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = 5.minutes.some

  override def interval(ctx: JobContext, env: Env): Option[FiniteDuration] = 1.hour.some

  override def starting: JobStarting = JobStarting.Automatically

  override def instantiation(ctx: JobContext, env: Env): JobInstantiation =
    JobInstantiation.OneInstancePerOtoroshiCluster

  override def predicate(ctx: JobContext, env: Env): Option[Boolean] = None

  override def jobRun(ctx: JobContext)(using env: Env, ec: ExecutionContext): Future[Unit] = {
    UserAnalyticsExporterSettings.findActiveAnalyticsExporter.flatMap {
      case None         =>
        logger.debug("no active user-analytics exporter, skipping retention job")
        FastFuture.successful(())
      case Some(config) =>
        config.config match {
          case s: UserAnalyticsExporterSettings if s.retentionDays > 0 => deleteOld(s, projections)
          case _                                                       => FastFuture.successful(())
        }
    }
  }

  /**
   * Core first, then whatever the extensions declared — a table nobody prunes grows forever.
   * Excluded projections are pruned too: what they stored before the exclusion still has to age out.
   */
  private def projections(using env: Env): Seq[AnalyticsProjection] =
    AnalyticsProjection.installed(env).filter(_.retention)

  private def deleteOld(s: UserAnalyticsExporterSettings, projections: Seq[AnalyticsProjection])(using
      ec: ExecutionContext
  ): Future[Unit] = {
    val opts = s.uri match {
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
    val pool = PgBuilder.pool().connectingTo(opts).`with`(new PoolOptions().setMaxSize(1)).build()
    projections
      .foldLeft(FastFuture.successful(())) { (acc, projection) =>
        val table = projection.table(s)
        val sql   =
          s"DELETE FROM $table WHERE ${projection.retentionColumn} < NOW() - INTERVAL '${s.retentionDays} days'"
        acc.flatMap { _ =>
          pool
            .query(sql)
            .executeAsync()
            .map { rs =>
              logger.info(
                s"[user-analytics-retention] deleted ${rs
                  .rowCount()} rows older than ${s.retentionDays} days from $table"
              )
            }
            // one projection's table missing or misdeclared must not stop the others being pruned
            .recover { case e: Throwable =>
              logger.error(s"[user-analytics-retention] error while cleaning up $table", e)
            }
        }
      }
      .map(_ => pool.close())
  }
}
