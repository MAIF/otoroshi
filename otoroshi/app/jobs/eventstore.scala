package otoroshi.jobs.updates

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import otoroshi.env.Env
import otoroshi.plugins.jobs.kubernetes.KubernetesConfig
import otoroshi.script.{Job, JobContext, JobId, JobInstantiation, JobKind, JobStarting, JobVisibility}
import play.api.Logger
import play.api.libs.json.{JsNull, JsObject, JsValue, Json}
import otoroshi.utils.syntax.implicits._

import otoroshi.events.impl._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Try}

object EventstoreCheckerJob {
  val initialized = new AtomicBoolean(false)
  val works       = new AtomicBoolean(false)
}

class EventstoreCheckerJob extends Job {

  private val logger = Logger("otoroshi-jobs-eventstore-checker")

  override def uniqueId: JobId = JobId("io.otoroshi.core.jobs.EventstoreCheckerJob")

  override def name: String = "Eventstore checker"

  override def defaultConfig: Option[JsObject] = None

  override def description: Option[String] =
    s"""This job will check if the elastic eventstore is connected""".stripMargin.some

  override def visibility: JobVisibility = JobVisibility.Internal

  override def kind: JobKind = JobKind.ScheduledEvery

  override def starting: JobStarting = JobStarting.Automatically

  override def instantiation(ctx: JobContext, env: Env): JobInstantiation =
    JobInstantiation.OneInstancePerOtoroshiInstance

  override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = 1.seconds.some

  override def interval(ctx: JobContext, env: Env): Option[FiniteDuration] = 10.seconds.some

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    env.datastores.globalConfigDataStore.singleton().flatMap { config =>
      config.elasticReadsConfig match {
        case None           =>
          ().future
        case Some(esConfig) => {
          val read = new ElasticReadsAnalytics(esConfig, env)
          read
            .getElasticVersion()
            .map { version =>
              EventstoreCheckerJob.initialized.set(true)
              EventstoreCheckerJob.works.set(true)
            }
            .recover { case t: Throwable =>
              EventstoreCheckerJob.initialized.set(true)
              EventstoreCheckerJob.works.set(false)
            }
        }
      }
    }
  }
}
