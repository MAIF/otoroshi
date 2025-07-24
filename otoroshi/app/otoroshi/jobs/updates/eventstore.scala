package otoroshi.jobs.updates

import otoroshi.env.Env
import otoroshi.events.impl._
import otoroshi.next.plugins.api.NgPluginCategory
import otoroshi.script._
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json.JsObject

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object EventstoreCheckerJob {
  val initialized = new AtomicBoolean(false)
  val works       = new AtomicBoolean(false)
}

class EventstoreCheckerJob extends Job {

  private val logger = Logger("otoroshi-jobs-eventstore-checker")

  override def categories: Seq[NgPluginCategory] = Seq.empty

  override def uniqueId: JobId = JobId("io.otoroshi.core.jobs.EventstoreCheckerJob")

  override def name: String = "Eventstore checker"

  override def defaultConfig: Option[JsObject] = None

  override def description: Option[String] =
    s"""This job will check if the elastic eventstore is connected""".stripMargin.some

  override def jobVisibility: JobVisibility = JobVisibility.Internal

  override def kind: JobKind = JobKind.ScheduledEvery

  override def starting: JobStarting = JobStarting.Automatically

  override def instantiation(ctx: JobContext, env: Env): JobInstantiation =
    JobInstantiation.OneInstancePerOtoroshiInstance

  override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = 1.seconds.some

  override def interval(ctx: JobContext, env: Env): Option[FiniteDuration] = 10.seconds.some

  override def predicate(ctx: JobContext, env: Env): Option[Boolean] = None

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    env.datastores.globalConfigDataStore.singleton().flatMap { config =>
      config.elasticReadsConfig match {
        case None           =>
          ().future
        case Some(esConfig) =>
          val read = new ElasticReadsAnalytics(esConfig, env)
          read
            .checkAvailability()
            .map {
              case Left(_)  =>
                EventstoreCheckerJob.initialized.set(true)
                EventstoreCheckerJob.works.set(false)
              case Right(_) =>
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
