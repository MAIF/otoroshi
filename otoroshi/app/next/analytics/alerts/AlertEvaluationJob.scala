package otoroshi.next.analytics.alerts

import akka.http.scaladsl.util.FastFuture
import akka.util.ByteString
import otoroshi.env.Env
import otoroshi.events.Alerts
import otoroshi.next.analytics.models.UserAlert
import otoroshi.next.plugins.api.NgPluginCategory
import otoroshi.script.{Job, JobContext, JobId, JobInstantiation, JobKind, JobStarting, JobVisibility}
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
 * Periodic job that evaluates every enabled `UserAlert` and emits a
 * `UserAnalyticsAlertEvent` when an alert is currently firing, respecting
 * the per-alert evaluation interval and cooldown.
 *
 * State persisted per alert in the `rawDataStore` under
 * `${storageRoot}:analytics:alert-state:${alertId}`.
 *
 * Runs leader-only (cluster) so we don't get N copies of the same event.
 */
class AlertEvaluationJob extends Job {

  private val logger = Logger("otoroshi-user-analytics-alert-job")

  override def categories: Seq[NgPluginCategory] = Seq.empty

  override def uniqueId: JobId = JobId("io.otoroshi.next.analytics.AlertEvaluationJob")

  override def name: String = "Otoroshi user-analytics alert evaluation job"

  override def jobVisibility: JobVisibility = JobVisibility.Internal

  override def kind: JobKind = JobKind.ScheduledEvery

  override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = 30.seconds.some

  override def interval(ctx: JobContext, env: Env): Option[FiniteDuration] = 60.seconds.some

  override def starting: JobStarting = JobStarting.Automatically

  override def instantiation(ctx: JobContext, env: Env): JobInstantiation =
    JobInstantiation.OneInstancePerOtoroshiCluster

  override def predicate(ctx: JobContext, env: Env): Option[Boolean] = None

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val alerts = env.proxyState.allUserAlerts().filter(_.enabled)
    if (alerts.isEmpty) FastFuture.successful(())
    else {
      Future
        .sequence(alerts.map(a => processAlert(a).recover {
          case e: Throwable =>
            logger.error(s"error while processing alert '${a.id}'", e)
        }))
        .map(_ => ())
    }
  }

  // --------------------------------------------------------------------------
  // Per-alert lifecycle
  // --------------------------------------------------------------------------

  private case class AlertState(lastEvaluatedAt: Long, lastFiredAt: Long)
  private val ZERO_STATE = AlertState(0L, 0L)

  private def processAlert(alert: UserAlert)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val now = System.currentTimeMillis()
    loadState(alert.id).flatMap { st =>
      val nextEvaluation = st.lastEvaluatedAt + alert.evaluationIntervalSeconds * 1000L
      if (now < nextEvaluation) {
        FastFuture.successful(())
      } else {
        AlertEvaluator.evaluate(alert).flatMap { case (firing, evals) =>
          val cooldownOver = (now - st.lastFiredAt) >= alert.cooldownSeconds * 1000L
          if (firing && cooldownOver) {
            val ev = UserAnalyticsAlertEvent(
              alertConfig = alert,
              evaluations = evals,
              `@id` = env.snowflakeGenerator.nextIdStr()
            )(env)
            Try(Alerts.send(ev)(env)).recover { case e: Throwable =>
              logger.error(s"failed to send alert '${alert.id}'", e)
            }
            saveState(alert.id, AlertState(lastEvaluatedAt = now, lastFiredAt = now)).map(_ => ())
          } else {
            saveState(alert.id, st.copy(lastEvaluatedAt = now)).map(_ => ())
          }
        }
      }
    }
  }

  // --------------------------------------------------------------------------
  // State persistence
  // --------------------------------------------------------------------------

  private def stateKey(id: String)(implicit env: Env): String =
    s"${env.storageRoot}:analytics:alert-state:$id"

  private def loadState(id: String)(implicit env: Env, ec: ExecutionContext): Future[AlertState] = {
    env.datastores.rawDataStore.get(stateKey(id)).map { opt =>
      opt
        .flatMap(bs => Try(Json.parse(bs.utf8String)).toOption)
        .map { js =>
          AlertState(
            lastEvaluatedAt = (js \ "lastEvaluatedAt").asOpt[Long].getOrElse(0L),
            lastFiredAt = (js \ "lastFiredAt").asOpt[Long].getOrElse(0L)
          )
        }
        .getOrElse(ZERO_STATE)
    }
  }

  private def saveState(id: String, st: AlertState)(implicit env: Env, ec: ExecutionContext): Future[Boolean] = {
    val payload = Json.obj(
      "lastEvaluatedAt" -> st.lastEvaluatedAt,
      "lastFiredAt"     -> st.lastFiredAt
    )
    env.datastores.rawDataStore.set(stateKey(id), ByteString.fromString(Json.stringify(payload)), None)
  }
}
