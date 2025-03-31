package otoroshi.jobs

import otoroshi.env.Env
import otoroshi.next.plugins.api.NgPluginCategory
import otoroshi.script._
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json.JsObject

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.{DurationInt, FiniteDuration}

class ServiceDescriptorUsageWarning extends Job {

  private val logger = Logger("otoroshi-jobs-service-descriptor-usage-warning")

  override def categories: Seq[NgPluginCategory] = Seq.empty

  override def uniqueId: JobId = JobId("io.otoroshi.core.jobs.ServiceDescriptorUsageWarning")

  override def name: String = "Otoroshi service descriptor usage warning"

  override def defaultConfig: Option[JsObject] = None

  override def description: Option[String] =
    s"""This job will check if there is still service descriptors in the database""".stripMargin.some

  override def jobVisibility: JobVisibility = JobVisibility.Internal

  override def kind: JobKind = JobKind.ScheduledOnce

  override def starting: JobStarting = JobStarting.Automatically

  override def instantiation(ctx: JobContext, env: Env): JobInstantiation =
    JobInstantiation.OneInstancePerOtoroshiInstance

  override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = 1.seconds.some

  override def interval(ctx: JobContext, env: Env): Option[FiniteDuration] = None

  override def predicate(ctx: JobContext, env: Env): Option[Boolean] = None

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    env.datastores.serviceDescriptorDataStore.count().map { count =>
      if (count > 0L) {
        env.logger.warn("")
        env.logger.warn(s"-------------------------------------------------------------------------")
        env.logger.warn(s"##                                                                     ##")
        env.logger.warn(s"##   It seems that you are still using Service Descriptors             ##")
        env.logger.warn(s"##   we count ${count} entities remaining. the next major                ")
        env.logger.warn(s"##   version of Otoroshi will remove support for Service Descriptors   ##")
        env.logger.warn(s"##                                                                     ##")
        env.logger.warn(s"##   for more information about that, please read                      ##")
        env.logger.warn(s"##   https://maif.github.io/otoroshi/manual/topics/deprecating-sd.html ##")
        env.logger.warn(s"##                                                                     ##")
        env.logger.warn(s"-------------------------------------------------------------------------")
        env.logger.warn("")
      }
    }
  }
}
