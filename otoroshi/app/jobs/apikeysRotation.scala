package otoroshi.jobs.apikeys

import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{Sink, Source}
import otoroshi.env.Env
import otoroshi.script.{Job, JobContext, JobId, JobInstantiation, JobKind, JobStarting, JobVisibility}
import play.api.Logger
import otoroshi.utils.syntax.implicits._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class ApikeysSecretsRotationJob extends Job {

  private val logger = Logger("otoroshi-apikeys-secrets-rotation-job")

  override def uniqueId: JobId = JobId("io.otoroshi.core.jobs.ApikeysSecretsRotationJob")

  override def name: String = "Otoroshi apikeys secrets rotation job"

  override def visibility: JobVisibility = JobVisibility.Internal

  override def kind: JobKind = JobKind.ScheduledEvery

  override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = 1.minutes.some

  override def interval(ctx: JobContext, env: Env): Option[FiniteDuration] = 10.minutes.some

  override def starting: JobStarting = JobStarting.Automatically

  override def instantiation(ctx: JobContext, env: Env): JobInstantiation = JobInstantiation.OneInstancePerOtoroshiCluster

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    env.datastores.apiKeyDataStore.findAll().flatMap { apikeys =>
      Source(apikeys.toList)
        .mapAsync(1)(apikey => env.datastores.apiKeyDataStore.keyRotation(apikey))
        .runWith(Sink.seq)(env.otoroshiMaterializer)
        .map(_ => ())
    }
  }
}
