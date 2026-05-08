package otoroshi.next.analytics.exporter

import akka.http.scaladsl.util.FastFuture
import io.vertx.pgclient.{PgConnectOptions, PgPool, SslMode}
import io.vertx.sqlclient.PoolOptions
import otoroshi.env.Env
import otoroshi.next.plugins.api.NgPluginCategory
import otoroshi.script.{Job, JobContext, JobId, JobInstantiation, JobKind, JobStarting, JobVisibility}
import otoroshi.storage.drivers.reactivepg.pgimplicits._
import otoroshi.utils.syntax.implicits._
import play.api.Logger

import scala.concurrent.duration._
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

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    UserAnalyticsExporterSettings.findActiveAnalyticsExporter.flatMap {
      case None         =>
        logger.debug("no active user-analytics exporter, skipping retention job")
        FastFuture.successful(())
      case Some(config) =>
        config.config match {
          case s: UserAnalyticsExporterSettings if s.retentionDays > 0 => deleteOld(s)
          case _                                                       => FastFuture.successful(())
        }
    }
  }

  private def deleteOld(s: UserAnalyticsExporterSettings)(implicit ec: ExecutionContext): Future[Unit] = {
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
    val pool = PgPool.pool(opts, new PoolOptions().setMaxSize(1))
    val sql  =
      s"DELETE FROM ${AnalyticsSchema.fullTable(s)} WHERE ts < NOW() - INTERVAL '${s.retentionDays} days'"
    pool
      .query(sql)
      .executeAsync()
      .map { rs =>
        logger.info(
          s"[user-analytics-retention] deleted ${rs.rowCount()} events older than ${s.retentionDays} days from ${AnalyticsSchema.fullTable(s)}"
        )
      }
      .recover { case e: Throwable =>
        logger.error(s"[user-analytics-retention] error while cleaning up ${AnalyticsSchema.fullTable(s)}", e)
      }
      .map(_ => pool.close())
  }
}
