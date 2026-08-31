package otoroshi.jobs

import org.apache.pekko.stream.scaladsl.{Sink, Source}
import otoroshi.env.Env
import otoroshi.next.models.NgRoute
import otoroshi.next.plugins.api.NgPluginCategory
import otoroshi.script.*
import otoroshi.utils.syntax.implicits.*
import play.api.Logger
import play.api.libs.json.{JsArray, JsObject, Json}

import otoroshi.models.ServiceDescriptor

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.{Failure, Success, Try}

// [REMOVE SERVICEDESC] class ServiceDescriptorUsageWarning extends Job {
// [REMOVE SERVICEDESC]
// [REMOVE SERVICEDESC]   private val logger = Logger("otoroshi-jobs-service-descriptor-usage-warning")
// [REMOVE SERVICEDESC]
// [REMOVE SERVICEDESC]   override def categories: Seq[NgPluginCategory] = Seq.empty
// [REMOVE SERVICEDESC]
// [REMOVE SERVICEDESC]   override def uniqueId: JobId = JobId("io.otoroshi.core.jobs.ServiceDescriptorUsageWarning")
// [REMOVE SERVICEDESC]
// [REMOVE SERVICEDESC]   override def name: String = "Otoroshi service descriptor usage warning"
// [REMOVE SERVICEDESC]
// [REMOVE SERVICEDESC]   override def defaultConfig: Option[JsObject] = None
// [REMOVE SERVICEDESC]
// [REMOVE SERVICEDESC]   override def description: Option[String] =
// [REMOVE SERVICEDESC]     s"""This job will check if there is still service descriptors in the database""".stripMargin.some
// [REMOVE SERVICEDESC]
// [REMOVE SERVICEDESC]   override def jobVisibility: JobVisibility = JobVisibility.Internal
// [REMOVE SERVICEDESC]
// [REMOVE SERVICEDESC]   override def kind: JobKind = JobKind.ScheduledOnce
// [REMOVE SERVICEDESC]
// [REMOVE SERVICEDESC]   override def starting: JobStarting = JobStarting.Automatically
// [REMOVE SERVICEDESC]
// [REMOVE SERVICEDESC]   override def instantiation(ctx: JobContext, env: Env): JobInstantiation =
// [REMOVE SERVICEDESC]     JobInstantiation.OneInstancePerOtoroshiInstance
// [REMOVE SERVICEDESC]
// [REMOVE SERVICEDESC]   override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = 1.seconds.some
// [REMOVE SERVICEDESC]
// [REMOVE SERVICEDESC]   override def interval(ctx: JobContext, env: Env): Option[FiniteDuration] = None
// [REMOVE SERVICEDESC]
// [REMOVE SERVICEDESC]   override def predicate(ctx: JobContext, env: Env): Option[Boolean] = None
// [REMOVE SERVICEDESC]
// [REMOVE SERVICEDESC]   override def jobRun(ctx: JobContext)(using env: Env, ec: ExecutionContext): Future[Unit] = {
// [REMOVE SERVICEDESC]     env.datastores.serviceDescriptorDataStore.count().map { count =>
// [REMOVE SERVICEDESC]       if (count > 0L) {
// [REMOVE SERVICEDESC]         env.logger.warn("")
// [REMOVE SERVICEDESC]         env.logger.warn(s"-------------------------------------------------------------------------")
// [REMOVE SERVICEDESC]         env.logger.warn(s"##                                                                     ##")
// [REMOVE SERVICEDESC]         env.logger.warn(s"##   It seems that you are still using Service Descriptors             ##")
// [REMOVE SERVICEDESC]         env.logger.warn(s"##   we count ${count} entities remaining. the next major                ")
// [REMOVE SERVICEDESC]         env.logger.warn(s"##   version of Otoroshi will remove support for Service Descriptors   ##")
// [REMOVE SERVICEDESC]         env.logger.warn(s"##                                                                     ##")
// [REMOVE SERVICEDESC]         env.logger.warn(s"##   for more information about that, please read                      ##")
// [REMOVE SERVICEDESC]         env.logger.warn(s"##   https://www.otoroshi.io/docs/topics/deprecating-sd                ##")
// [REMOVE SERVICEDESC]         env.logger.warn(s"##                                                                     ##")
// [REMOVE SERVICEDESC]         env.logger.warn(s"-------------------------------------------------------------------------")
// [REMOVE SERVICEDESC]         env.logger.warn("")
// [REMOVE SERVICEDESC]       }
// [REMOVE SERVICEDESC]     }
// [REMOVE SERVICEDESC]   }
// [REMOVE SERVICEDESC] }

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

  private def warn(message: String)(using env: Env): Unit = {
    env.logger.warn(s"[service-descriptors-migration] $message")
  }

  private def error(message: String, t: Throwable)(using env: Env): Unit = {
    env.logger.error(s"[service-descriptors-migration] $message", t)
  }

  private def error(message: String)(using env: Env): Unit = {
    env.logger.error(s"[service-descriptors-migration] $message")
  }

  // the invariant this job must never break: for a given id, there is always either a route or a
  // service descriptor in the datastore. never neither. a descriptor left behind is a failed
  // migration that will be retried at the next startup, a lost entity is unrecoverable.
  private sealed trait MigrationResult
  private case object MigrationOk      extends MigrationResult
  private case object MigrationSkipped extends MigrationResult
  private case object MigrationFailed  extends MigrationResult

  private def restoreIfMissing(descriptor: ServiceDescriptor)(using
      env: Env,
      ec: ExecutionContext
  ): Future[MigrationResult] = {
    env.datastores.serviceDescriptorDataStore.findById(descriptor.id).flatMap {
      case Some(_) => MigrationFailed.vfuture
      case None    =>
        warn(s" - restoring service descriptor '${descriptor.name}' after a failed migration")
        descriptor.save().map { restored =>
          if (!restored) {
            error(
              s"unable to restore service descriptor '${descriptor.name}' (${descriptor.id}). it is still available in the backup file"
            )
          }
          MigrationFailed
        }
    }
  }

  private def migrateOne(descriptor: ServiceDescriptor)(using
      env: Env,
      ec: ExecutionContext
  ): Future[MigrationResult] = {
    val name   = descriptor.name
    val result = Try(NgRoute.fromServiceDescriptor(descriptor, debug = false)) match {
      case Failure(t)     =>
        error(s"error while converting service descriptor '$name' to a route, it has been left untouched", t)
        MigrationFailed.vfuture
      case Success(route) =>
        env.datastores.routeDataStore.findById(route.id).flatMap {
          // never silently overwrite a route somebody else created with the same id
          case Some(existing) if !existing.metadata.get("otoroshi-core-legacy").contains("true") =>
            error(
              s"a route with id '${route.id}' already exists and was not created by this migration. service descriptor '$name' has been left untouched, please resolve the conflict manually"
            )
            MigrationSkipped.vfuture
          case _                                                                                =>
            route.save().flatMap {
              case false =>
                error(s"unable to save the route for service descriptor '$name', it has been left untouched")
                MigrationFailed.vfuture
              case true  =>
                // read the route back before dropping the descriptor: a successful write is not a
                // guarantee that the entity is actually readable from the datastore
                env.datastores.routeDataStore.findById(route.id).flatMap {
                  case None    =>
                    error(
                      s"the route for service descriptor '$name' is not readable back after being saved, it has been left untouched"
                    )
                    MigrationFailed.vfuture
                  case Some(_) =>
                    env.datastores.serviceDescriptorDataStore.delete(descriptor).flatMap { deleted =>
                      env.datastores.routeDataStore.findById(route.id).flatMap {
                        case Some(_) =>
                          if (!deleted) {
                            warn(
                              s" - route '${route.id}' created but service descriptor '$name' could not be deleted, it will be cleaned up at the next startup"
                            )
                          }
                          MigrationOk.vfuture
                        case None    =>
                          error(s"route '${route.id}' vanished right after migrating service descriptor '$name'")
                          restoreIfMissing(descriptor)
                      }
                    }
                }
            }
        }
    }
    result.recoverWith { case t: Throwable =>
      error(s"unexpected error while migrating service descriptor '$name'", t)
      restoreIfMissing(descriptor)
    }
  }

  override def jobRun(ctx: JobContext)(using env: Env, ec: ExecutionContext): Future[Unit] = {
    //[REMOVE SERVICEDESC] if (env.configuration.getOptional[Boolean]("otoroshi.service-descriptors-migration-job.enabled").getOrElse(false)) {
    //[REMOVE SERVICEDESC]   warn("Running full Service Descriptors migration !!!")
    //[REMOVE SERVICEDESC]   warn("")
      env.datastores.serviceDescriptorDataStore
        .findAll(force = true)
        .flatMap { descriptors =>
          if (descriptors.nonEmpty) {
            val backup = new File("./service-descriptors-backup.json")
            warn("Running full Service Descriptors migration !!!")
            warn("")
            warn(s" - writing a backup to '${backup.getAbsolutePath}'")
            warn("")
            // no backup, no migration. we would have no way back if a conversion went wrong
            Try(Files.writeString(backup.toPath, JsArray(descriptors.map(_.json)).stringify)) match {
              case Failure(t) =>
                error(
                  s"unable to write the backup to '${backup.getAbsolutePath}', aborting the migration. no service descriptor has been touched",
                  t
                )
                ().vfuture
              case Success(_) =>
                val migrated = new AtomicInteger(0)
                val skipped  = new AtomicInteger(0)
                val failed   = new AtomicInteger(0)
                Source(descriptors.toList)
                  .mapAsync(1) { descriptor =>
                    warn(s" - migrating service descriptor '${descriptor.name}' ...")
                    migrateOne(descriptor).map {
                      case MigrationOk      =>
                        migrated.incrementAndGet()
                        warn(s" - migrating service descriptor '${descriptor.name}' - OK")
                      case MigrationSkipped => skipped.incrementAndGet()
                      case MigrationFailed  => failed.incrementAndGet()
                    }
                  }
                  .runWith(Sink.ignore)(using env.otoroshiMaterializer)
                  .map { _ =>
                    val left = skipped.get() + failed.get()
                    warn("")
                    warn(
                      s"migration done: ${migrated.get()} migrated, ${skipped.get()} skipped, ${failed.get()} failed (out of ${descriptors.size})"
                    )
                    if (left > 0) {
                      warn("")
                      warn(s"$left service descriptor(s) could not be migrated and are still in your datastore.")
                      warn(s"a backup of all of them is available at '${backup.getAbsolutePath}'.")
                      warn("the migration will run again at the next otoroshi startup.")
                      warn("")
                    }
                    ()
                  }
            }
          } else {
            ().vfuture
          }
        }
    //[REMOVE SERVICEDESC] } else {
    //[REMOVE SERVICEDESC]   ().future
    //[REMOVE SERVICEDESC] }
  }
}
