package otoroshi.jobs

import akka.stream.scaladsl.{Sink, Source}
import otoroshi.env.Env
import otoroshi.next.models.NgRoute
import otoroshi.next.plugins.api.NgPluginCategory
import otoroshi.script._
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json.{JsArray, JsObject, Json}

import java.io.File
import java.nio.file.Files
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

class ServiceDescriptorMigrationJob extends Job {

  private val logger = Logger("otoroshi-jobs-service-descriptor-migration-job")

  override def categories: Seq[NgPluginCategory] = Seq.empty

  override def uniqueId: JobId = JobId("io.otoroshi.core.jobs.ServiceDescriptorMigrationJob")

  override def name: String = "Otoroshi service descriptor migration job"

  override def defaultConfig: Option[JsObject] = None

  override def description: Option[String] =
    s"""This job will transform all ServiceDescriptors into routes""".stripMargin.some

  override def jobVisibility: JobVisibility = JobVisibility.Internal

  override def kind: JobKind = JobKind.ScheduledOnce

  override def starting: JobStarting = JobStarting.Automatically

  override def instantiation(ctx: JobContext, env: Env): JobInstantiation =
    JobInstantiation.OneInstancePerOtoroshiCluster

  override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = 10.seconds.some

  override def interval(ctx: JobContext, env: Env): Option[FiniteDuration] = None

  override def predicate(ctx: JobContext, env: Env): Option[Boolean] = None

  private def warn(message: String)(implicit env: Env): Unit = {
    env.logger.warn(s"[service-descriptors-migration] $message")
  }

  private def error(message: String, t: Throwable)(implicit env: Env): Unit = {
    env.logger.error(s"[service-descriptors-migration] $message", t)
  }

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    if (env.configuration.getOptional[Boolean]("otoroshi.service-descriptors-migration-job.enabled").getOrElse(false)) {
      warn("Running full Service Descriptors migration !!!")
      warn("")
      env.datastores.serviceDescriptorDataStore.findAll(force = true).flatMap { descriptors =>
        val backup = new File("./service-descriptors-backup.json")
        warn(s" - writing a backup to '${backup.getAbsolutePath}'")
        warn("")
        val json = JsArray(descriptors.map(_.json))
        Files.writeString(backup.toPath, json.stringify)
        Source(descriptors.toList)
          .mapAsync(1) { descriptor =>
            warn(s" - migrating '${descriptor.name}' ...")
            val route = NgRoute.fromServiceDescriptor(descriptor, debug = false)
            route.save().flatMap { _ =>
              env.datastores.serviceDescriptorDataStore.delete(descriptor).map { _ =>
                warn(s" - migrating '${descriptor.name}' - OK")
              }
            }.recover {
              case t: Throwable => error(s"error while migrating '${descriptor.name}'", t)
            }
          }.runWith(Sink.ignore)(env.otoroshiMaterializer)
          .andThen {
            case _ => warn("migration done !")
          }
      }.map(_ => ())
    } else {
      ().future
    }
  }
}
