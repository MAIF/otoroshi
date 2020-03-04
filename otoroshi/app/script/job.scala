package otoroshi.script

import akka.http.scaladsl.util.FastFuture
import env.Env
import play.api.libs.json.JsValue

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

sealed trait JobKind
object JobKind {
  case object ScheduledOnce extends JobKind
  case object ScheduledEvery extends JobKind
  case object Autonomous extends JobKind
}

sealed trait JobStarting
object JobStarting {
  case object Automatically extends JobStarting
  case object FromConfiguration extends JobStarting
}

sealed trait JobInstantiation
object JobInstantiation {
  case object OneInstancePerOtoroshiInstance extends JobInstantiation
  case object OneInstancePerOtoroshiWorkerInstance extends JobInstantiation
  case object OneInstancePerOtoroshiLeaderInstance extends JobInstantiation
  case object OneInstancePerOtoroshiCluster extends JobInstantiation
}

case class JobContext(snowflake: String, index: Int, config: JsValue, globalConfig: JsValue) extends ContextWithConfig

trait Job extends NamedPlugin with StartableAndStoppable with InternalEventListener {

  override def pluginType: PluginType = JobType

  def kind: JobKind
  def starting: JobStarting
  def instantiation: JobInstantiation

  def initialDelay: Option[FiniteDuration] = None
  def interval: Option[FiniteDuration] = None

  def jobStart(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = Job.funit
  def jobStop(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = Job.funit
  def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = Job.funit
}

object Job {
  val funit = FastFuture.successful(())
}

class JobManager(env: Env) {

  def start(): Unit = {

  }

  def stop(): Unit = {

  }
}
