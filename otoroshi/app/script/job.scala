package otoroshi.script

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import akka.actor.{ActorSystem, Cancellable, Scheduler}
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl._
import akka.util.ByteString
import otoroshi.cluster.ClusterMode
import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.events.{JobErrorEvent, JobRunEvent, JobStartedEvent, JobStoppedEvent}
import otoroshi.models.GlobalConfig
import otoroshi.next.plugins.WasmJob
import otoroshi.next.plugins.api.{NgPluginCategory, NgPluginVisibility, NgStep}
import otoroshi.next.utils.JsonHelpers
import otoroshi.next.workflow.WorkflowJob
import otoroshi.utils
import otoroshi.utils.{future, JsonPathValidator, JsonValidator, SchedulerHelper, TypedMap}
import play.api.Logger
import play.api.libs.json._
import otoroshi.security.IdGenerator
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.config.ConfigUtils
import otoroshi.utils.syntax.implicits._

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Random, Success, Try}

sealed trait JobKind {
  def name: String
}
object JobKind       {

  case object ScheduledOnce  extends JobKind { def name: String = "ScheduledOnce"  }
  case object ScheduledEvery extends JobKind { def name: String = "ScheduledEvery" }
  case object Cron           extends JobKind { def name: String = "Cron"           }
  case object Autonomous     extends JobKind { def name: String = "Autonomous"     }

  def apply(value: String): JobKind = value.toLowerCase() match {
    case "scheduledonce"  => ScheduledOnce
    case "scheduledevery" => ScheduledEvery
    case "cron"           => Cron
    case "autonomous"     => Autonomous
    case _                => ScheduledEvery
  }
}

sealed trait JobStarting
object JobStarting {
  case object Never             extends JobStarting
  case object Automatically     extends JobStarting
  case object FromConfiguration extends JobStarting
}

sealed trait JobInstantiation {
  def name: String
}
object JobInstantiation       {

  case object OneInstancePerOtoroshiInstance       extends JobInstantiation {
    def name: String = "OneInstancePerOtoroshiInstance"
  }
  case object OneInstancePerOtoroshiWorkerInstance extends JobInstantiation {
    def name: String = "OneInstancePerOtoroshiWorkerInstance"
  }
  case object OneInstancePerOtoroshiLeaderInstance extends JobInstantiation {
    def name: String = "OneInstancePerOtoroshiLeaderInstance"
  }
  case object OneInstancePerOtoroshiCluster        extends JobInstantiation {
    def name: String = "OneInstancePerOtoroshiCluster"
  }

  def apply(value: String): JobInstantiation = value.toLowerCase() match {
    case "oneinstanceperotoroshiinstance"       => OneInstancePerOtoroshiInstance
    case "oneinstanceperotoroshiworkerinstance" => OneInstancePerOtoroshiWorkerInstance
    case "oneinstanceperotoroshileaderinstance" => OneInstancePerOtoroshiLeaderInstance
    case "oneinstanceperotoroshicluster"        => OneInstancePerOtoroshiCluster
    case _                                      => OneInstancePerOtoroshiInstance
  }
}

sealed trait JobVisibility
object JobVisibility {
  case object Internal extends JobVisibility
  case object UserLand extends JobVisibility
}

case class JobContext(
    snowflake: String,
    attrs: TypedMap,
    globalConfig: JsValue,
    actorSystem: ActorSystem,
    scheduler: Scheduler
) extends ContextWithConfig {
  final def config: JsValue     = Json.obj()
  final override def index: Int = 0
  def wasmJson: JsValue         = Json.obj(
    "snowflake"     -> snowflake,
    "attrs"         -> attrs.json,
    "global_config" -> globalConfig
  )
}

case class JobId(id: String)

trait Job extends NamedPlugin with StartableAndStoppable with InternalEventListener { self =>

  private val refId   = new AtomicReference[String](s"cp:${self.getClass.getName}")
  private val promise = Promise[Unit]

  final override def pluginType: PluginType = PluginType.JobType

  override def visibility: NgPluginVisibility = jobVisibility match {
    case JobVisibility.UserLand => NgPluginVisibility.NgUserLand
    case JobVisibility.Internal => NgPluginVisibility.NgInternal
  }
  override def steps: Seq[NgStep]             = Seq.empty

  def uniqueId: JobId
  def jobVisibility: JobVisibility                                    = JobVisibility.UserLand
  def kind: JobKind                                                   = JobKind.Autonomous
  def starting: JobStarting                                           = JobStarting.Automatically
  def instantiation(ctx: JobContext, env: Env): JobInstantiation      = JobInstantiation.OneInstancePerOtoroshiInstance
  def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = Some(FiniteDuration(0, TimeUnit.MILLISECONDS))
  def interval(ctx: JobContext, env: Env): Option[FiniteDuration]     = None
  def cronExpression(ctx: JobContext, env: Env): Option[String]       = None
  def predicate(ctx: JobContext, env: Env): Option[Boolean]           = None

  def currentConfig(name: String, ctx: JobContext, env: Env): Option[JsValue] = {
    val globalConfig = env.datastores.globalConfigDataStore.latest()(env.otoroshiExecutionContext, env)
    val context      = Json.obj(
      "env"      -> globalConfig.env,
      "instance" -> env.configurationJson.select("otoroshi").select("instance").asValue
    )
    globalConfig.plugins.config
      .select(name)
      .asOpt[JsValue] match {
      case Some(JsArray(values))   => {
        values.find { value =>
          value.select("predicates").asOpt[Seq[JsObject]] match {
            case None             => false
            case Some(predicates) => {
              val validators =
                predicates.map(v => JsonValidator.format.reads(v)).collect { case JsSuccess(value, _) => value }
              validators.forall(_.validate(context)(env))
            }
          }
        }
      }
      case Some(obj @ JsObject(_)) => {
        obj.select("predicates").asOpt[Seq[JsObject]] match {
          case None             => None
          case Some(predicates) => {
            val validators =
              predicates.map(v => JsonValidator.format.reads(v)).collect { case JsSuccess(value, _) => value }
            if (validators.forall(_.validate(context)(env))) {
              obj.some
            } else {
              None
            }
          }
        }
      }
      case _                       => None
    }
  }

  private[script] def jobStartHook(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    JobStartedEvent(env.snowflakeGenerator.nextIdStr(), env.env, this, ctx).toAnalytics()
    jobStart(ctx)(env, ec)
  }

  private[script] def jobStopHook(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    JobStoppedEvent(env.snowflakeGenerator.nextIdStr(), env.env, this, ctx).toAnalytics()
    promise.trySuccess(())
    jobStop(ctx)(env, ec)
  }

  private[script] def jobRunHook(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    JobRunEvent(env.snowflakeGenerator.nextIdStr(), env.env, this, ctx).toAnalytics()
    try {
      jobRun(ctx)(env, ec).andThen { case Failure(e) =>
        JobErrorEvent(env.snowflakeGenerator.nextIdStr(), env.env, this, ctx, e)
      }
    } catch {
      case e: Throwable =>
        JobErrorEvent(env.snowflakeGenerator.nextIdStr(), env.env, this, ctx, e)
        FastFuture.failed(e)
    }
  }

  def jobStart(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = Job.funit
  def jobStop(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit]  = Job.funit
  def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit]   = Job.funit

  private def header(env: Env): String = s"[${uniqueId.id} / ${env.number}] -"

  final override def startWithPluginId(pluginId: String, env: Env): Future[Unit] = {
    if (JobManager.logger.isDebugEnabled) JobManager.logger.debug(s"${header(env)} plugin started")
    refId.set(pluginId)
    env.jobManager.registerJob(this)
    Job.funit
  }

  final override def stop(env: Env): Future[Unit] = {
    if (JobManager.logger.isDebugEnabled) JobManager.logger.debug(s"${header(env)} plugin stopped")
    env.jobManager.unregisterJob(this)
    Job.funit
  }

  final private[script] def underlyingId: String = {
    Option(refId.get()).getOrElse(s"cp:${self.getClass.getName}")
  }

  final def launchNow()(implicit env: Env): Future[Unit] = {
    val manager = env.jobManager
    manager.registerJob(this)
    manager.startIfPossible(this)
    promise.future.andThen { case _ =>
      manager.unregisterJob(this)
    }(manager.jobExecutor)
  }

  final def auditJson(ctx: JobContext)(implicit env: Env): JsValue =
    Json.obj(
      "uniqueId"       -> uniqueId.id,
      "name"           -> name,
      "kind"           -> kind.toString,
      "starting"       -> starting.toString,
      "instantiation"  -> instantiation(ctx, env).toString,
      "initialDelay"   -> initialDelay(ctx, env)
        .map(v => BigDecimal(v.toMillis))
        .map(JsNumber.apply)
        .getOrElse(JsNull)
        .as[JsValue],
      "interval"       -> interval(ctx, env)
        .map(v => BigDecimal(v.toMillis))
        .map(JsNumber.apply)
        .getOrElse(JsNull)
        .as[JsValue],
      "cronExpression" -> cronExpression(ctx, env).map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "config"         -> env.datastores.globalConfigDataStore.latestSafe
        .map(_.scripts.jobConfig)
        .getOrElse(Json.obj())
        .as[JsValue]
    )
}

object Job {
  val funit = FastFuture.successful(())
}

case class RegisteredJobContext(
    job: Job,
    env: Env,
    actorSystem: ActorSystem,
    ranOnce: AtomicBoolean,
    started: AtomicBoolean,
    stopped: AtomicBoolean,
    runId: AtomicReference[String],
    ref: AtomicReference[Option[Cancellable]]
) {

  private implicit val ec = actorSystem.dispatcher
  private implicit val ev = env

  private lazy val attrs = TypedMap.empty
  private lazy val randomLock = {
    val ref = new AtomicReference[String](IdGenerator.token(16))
    if (JobManager.logger.isDebugEnabled) JobManager.logger.debug(s"$header random lock value is '${ref.get()}'")
    ref
  }

  private def header: String = s"[${job.uniqueId.id} / ${env.number}] -"

  def runStartHook(): Unit = {
    if (started.compareAndSet(false, true)) {
      if (JobManager.logger.isDebugEnabled) JobManager.logger.debug(s"$header running start hook")
      job.jobStartHook(
        JobContext(
          snowflake = runId.get(),
          attrs = attrs,
          globalConfig = ConfigUtils.mergeOpt(
            env.datastores.globalConfigDataStore.latestSafe.map(_.scripts.jobConfig).getOrElse(Json.obj()),
            env.datastores.globalConfigDataStore.latestSafe.map(_.plugins.config)
          ),
          actorSystem = actorSystem,
          scheduler = actorSystem.scheduler
        )
      )(env, actorSystem.dispatcher)
    }
  }

  def runStopHook(): Unit = {
    if (stopped.compareAndSet(false, true)) {
      if (JobManager.logger.isDebugEnabled) JobManager.logger.debug(s"$header running stop hook")
      job.jobStopHook(
        JobContext(
          snowflake = runId.get(),
          attrs = attrs,
          globalConfig = ConfigUtils.mergeOpt(
            env.datastores.globalConfigDataStore.latestSafe.map(_.scripts.jobConfig).getOrElse(Json.obj()),
            env.datastores.globalConfigDataStore.latestSafe.map(_.plugins.config)
          ),
          actorSystem = actorSystem,
          scheduler = actorSystem.scheduler
        )
      )(env, actorSystem.dispatcher)
    }
  }

  def stop(config: GlobalConfig, env: Env): Unit = {
    if (JobManager.logger.isDebugEnabled) JobManager.logger.debug(s"$header stopping job context")
    runId.set(env.snowflakeGenerator.nextIdStr())
    runStopHook()
    Option(ref.get()).flatten.foreach(_.cancel())
    ref.set(None)
    env.jobManager.unregisterLock(job.uniqueId, randomLock.get())
    releaseLock()
  }

  def run(): Unit = {
    if (JobManager.logger.isDebugEnabled) JobManager.logger.debug(s"$header running the job")
    runId.set(env.snowflakeGenerator.nextIdStr())
    runStartHook()
    val ctx = JobContext(
      snowflake = runId.get(),
      attrs = attrs,
      globalConfig = ConfigUtils.mergeOpt(
        env.datastores.globalConfigDataStore.latestSafe.map(_.scripts.jobConfig).getOrElse(Json.obj()),
        env.datastores.globalConfigDataStore.latestSafe.map(_.plugins.config)
      ),
      actorSystem = actorSystem,
      scheduler = actorSystem.scheduler
    )
    job.kind match {
      case JobKind.Autonomous if !ranOnce.get()     => {
        ref.set(Some(actorSystem.scheduler.scheduleOnce(job.initialDelay(ctx, env).getOrElse(0.millisecond)) {
          try {
            if (!stopped.get()) {
              job.jobRunHook(ctx).andThen { case _ =>
                ref.set(None)
              // runStopHook()
              // releaseLock()
              }
            }
          } catch {
            case e: Throwable =>
              ref.set(None)
            // runStopHook()
            // releaseLock()
          }
        }))
      }
      case JobKind.ScheduledOnce if !ranOnce.get()  => {
        ref.set(Some(actorSystem.scheduler.scheduleOnce(job.initialDelay(ctx, env).getOrElse(0.millisecond)) {
          try {
            if (!stopped.get()) {
              job.jobRunHook(ctx).andThen { case _ =>
                ref.set(None)
                runStopHook()
              // releaseLock()
              }
            }
          } catch {
            case e: Throwable =>
              ref.set(None)
              runStopHook()
            // releaseLock()
          }
        }))
      }
      case JobKind.ScheduledEvery if !ranOnce.get() => {
        ref.set(Some(actorSystem.scheduler.scheduleOnce(job.initialDelay(ctx, env).getOrElse(0.millisecond)) {
          try {
            if (!stopped.get()) {
              job.jobRunHook(ctx).andThen { case _ =>
                ref.set(None)
              // releaseLock()
              }
            }
          } catch {
            case e: Throwable =>
              ref.set(None)
            // releaseLock()
          }
        }))
      }
      case JobKind.ScheduledEvery                   => {
        job.interval(ctx, env) match {
          case None           => ()
          case Some(interval) => {
            ref.set(Some(actorSystem.scheduler.scheduleOnce(interval) {
              try {
                if (!stopped.get()) {
                  job.jobRunHook(ctx).andThen { case _ =>
                    ref.set(None)
                  // releaseLock()
                  }
                }
              } catch {
                case e: Throwable =>
                  ref.set(None)
                // releaseLock()
              }
            }))
          }
        }
      }
      case JobKind.Cron                             => {
        job.cronExpression(ctx, env) match {
          case None             => ()
          case Some(expression) => {

            import java.time.ZonedDateTime

            import com.cronutils.parser.CronParser

            val now           = ZonedDateTime.now
            val parser        = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ))
            val cron          = parser.parse(expression)
            val executionTime = ExecutionTime.forCron(cron)
            val duration      = executionTime.timeToNextExecution(now)
            if (duration.isPresent) {
              ref.set(Some(actorSystem.scheduler.scheduleOnce(duration.get().toMillis.milliseconds) {
                if (!stopped.get()) {
                  try {
                    job.jobRunHook(ctx).andThen { case _ =>
                      ref.set(None)
                    // releaseLock()
                    }
                  } catch {
                    case e: Throwable =>
                      ref.set(None)
                    // releaseLock()
                  }
                }
              }))
            } else {
              ()
            }
          }
        }
      }
      case _                                        => () // nothing to do here
    }
    ranOnce.compareAndSet(false, true)
  }

  // TODO: Awful, find a better solution
  def acquireClusterWideLock(func: => Unit): Unit = {
    if (env.jobManager.hasNoLockFor(job.uniqueId)) {

      if (JobManager.logger.isDebugEnabled) JobManager.logger.debug(s"$header acquiring cluster wide lock ...")
      val key = s"${env.storageRoot}:locks:jobs:${job.uniqueId.id}"

      def internalsetLock() = {
        env.datastores.rawDataStore.setnx(key, ByteString(randomLock.get()), Some(30 * 1000)).map {
          case true  =>
            env.datastores.rawDataStore.get(key).map {
              case None                                                =>
                if (JobManager.logger.isDebugEnabled) JobManager.logger.debug(s"$header failed to acquire lock - 1")
                env.jobManager.unregisterLock(job.uniqueId, randomLock.get())
                ()
              case Some(value) if value.utf8String != randomLock.get() =>
                if (JobManager.logger.isDebugEnabled) JobManager.logger.debug(s"$header failed to acquire lock - 2")
                env.jobManager.unregisterLock(job.uniqueId, randomLock.get())
                ()
              case Some(value) if value.utf8String == randomLock.get() =>
                if (JobManager.logger.isDebugEnabled) JobManager.logger.debug(s"$header successfully acquired lock")
                env.jobManager.registerLock(job.uniqueId, randomLock.get())
                func
            }
          case false =>
            if (JobManager.logger.isDebugEnabled) JobManager.logger.debug(s"$header failed to acquire lock - 3")
            env.jobManager.unregisterLock(job.uniqueId, randomLock.get())
            ()
        }
      }

      env.datastores.rawDataStore.get(key).map {
        case Some(v) if v.utf8String == randomLock.get() =>
          if (JobManager.logger.isDebugEnabled) JobManager.logger.debug(s"$header already acquired lock")
          env.jobManager.registerLock(job.uniqueId, randomLock.get())
          func
        case Some(v) if v.utf8String != randomLock.get() =>
          if (JobManager.logger.isDebugEnabled) JobManager.logger.debug(s"$header failed to acquire lock - 0")
          env.jobManager.unregisterLock(job.uniqueId, randomLock.get())
          ()
        case None                                        =>
          if (JobManager.logger.isDebugEnabled) JobManager.logger.debug(s"$header no lock found, setnx")
          actorSystem.scheduler.scheduleOnce(Random.nextInt(1000).millisecond) {
            internalsetLock()
          }
      }
    } else {
      func
    }
  }

  // TODO: Awful, find a better solutions
  def releaseLock(): Unit = {
    if (JobManager.logger.isDebugEnabled) JobManager.logger.debug(s"$header releasing cluster wide lock")
    val key = s"${env.storageRoot}:locks:jobs:${job.uniqueId.id}"
    env.datastores.rawDataStore.get(key).map {
      case Some(v) if v.utf8String == randomLock.get() => env.datastores.rawDataStore.del(Seq(key))
      case _                                           => ()
    }
  }

  def tryToRunOnCurrentInstance(f: => Unit): Unit = {
    val ctx = JobContext(
      snowflake = runId.get(),
      attrs = attrs,
      globalConfig = ConfigUtils.mergeOpt(
        env.datastores.globalConfigDataStore.latestSafe.map(_.scripts.jobConfig).getOrElse(Json.obj()),
        env.datastores.globalConfigDataStore.latestSafe.map(_.plugins.config)
      ),
      actorSystem = actorSystem,
      scheduler = actorSystem.scheduler
    )
    job.predicate(ctx, env) match {
      case Some(false) => ()
      case _           => {
        Try(job.instantiation(ctx, env)) match {
          case Failure(e)             => JobManager.logger.error("failure during job instantiation fetch", e)
          case Success(instantiation) => {
            instantiation match {
              case JobInstantiation.OneInstancePerOtoroshiInstance                                             => f
              case JobInstantiation.OneInstancePerOtoroshiWorkerInstance if env.clusterConfig.mode.isOff       => f
              case JobInstantiation.OneInstancePerOtoroshiLeaderInstance if env.clusterConfig.mode.isOff       => f
              case JobInstantiation.OneInstancePerOtoroshiWorkerInstance if env.clusterConfig.mode.isWorker    => f
              case JobInstantiation.OneInstancePerOtoroshiLeaderInstance if env.clusterConfig.mode.isLeader    => f
              case JobInstantiation.OneInstancePerOtoroshiCluster if env.clusterConfig.mode == ClusterMode.Off =>
                acquireClusterWideLock(f)
              case JobInstantiation.OneInstancePerOtoroshiCluster if env.clusterConfig.mode.isLeader           =>
                acquireClusterWideLock(f)
              case _                                                                                           => ()
            }
          }
        }
      }
    }
  }

  def startNext(): Unit = {
    tryToRunOnCurrentInstance {
      job.kind match {
        case JobKind.Autonomous if !ranOnce.get()    => run()
        case JobKind.ScheduledOnce if !ranOnce.get() => run()
        case JobKind.ScheduledEvery                  => run()
        case JobKind.Cron                            => run()
        case _                                       => () // nothing to do here
      }
    }
  }

  def startIfPossible(config: GlobalConfig, env: Env): Unit = {
    Option(ref.get()).flatten match {
      case Some(_) => () // nothing to do, waiting for next round
      case None    => {
        job.starting match {
          case JobStarting.Never             => ()
          case JobStarting.Automatically     => startNext()
          case JobStarting.FromConfiguration => {
            if (config.scripts.enabled || config.plugins.enabled) {
              if (config.scripts.jobRefs.contains(job.underlyingId)) {
                startNext()
              }
              if (config.plugins.refs.contains(job.underlyingId)) {
                startNext()
              }
            }
          }
        }
      }
    }
  }
}

object JobManager {
  val logger = Logger("otoroshi-job-manager")
}

class JobManager(env: Env) {

  private[script] val jobActorSystem = ActorSystem("jobs-system")
  private[script] val jobScheduler   = jobActorSystem.scheduler
  private val registeredJobs         = new UnboundedTrieMap[JobId, RegisteredJobContext]()
  private val registeredLocks        = new UnboundedTrieMap[JobId, (String, String)]()
  private val scanRef                = new AtomicReference[Cancellable]()
  private val lockRef                = new AtomicReference[Cancellable]()

  private[script] implicit val jobExecutor = jobActorSystem.dispatcher
  private implicit val ev                  = env

  private[script] def registerLock(jobId: JobId, value: String): Unit = {
    val key = s"${env.storageRoot}:locks:jobs:${jobId.id}"
    registeredLocks.putIfAbsent(jobId, (key, value))
  }

  private[script] def unregisterLock(jobId: JobId, value: String): Unit = {
    // val key = s"${env.storageRoot}:locks:jobs:${jobId.id}"
    registeredLocks.remove(jobId)
  }

  private[script] def hasNoLockFor(jobId: JobId): Boolean = {
    !registeredLocks.contains(jobId)
  }

  private def updateLocks(): Unit = {
    registeredLocks.foreach { case (id, (key, value)) =>
      env.datastores.rawDataStore.get(key).map {
        case Some(v) if v.utf8String == value =>
          env.datastores.rawDataStore.set(key, ByteString(value), Some(30 * 1000))
        case _                                => ()
      }
    }
  }

  private def scanRegisteredJobs(): Unit = {
    env.datastores.globalConfigDataStore.singleton().map { config =>
      registeredJobs.foreach { case (id, ctx) =>
        ctx.startIfPossible(config, env)
      }
    }
  }

  private[script] def startIfPossible(job: Job): Unit = {
    env.datastores.globalConfigDataStore.singleton().map { config =>
      registeredJobs.get(job.uniqueId).foreach(_.startIfPossible(config, env))
    }
  }

  private def stopAllJobs(): Unit = {
    env.datastores.globalConfigDataStore.singleton().map { config =>
      registeredJobs.foreach { case (id, ctx) =>
        ctx.stop(config, env)
      }
    }
  }

  def start(): Unit = {
    JobManager.logger.info("Starting job manager")
    env.scriptManager.jobNames
      .filterNot(_ == classOf[WasmJob].getName)
      .filterNot(_ == classOf[WorkflowJob].getName)
      .map(name => env.scriptManager.getAnyScript[Job]("cp:" + name)) // starting auto registering for cp jobs
    scanRef.set(
      jobScheduler.scheduleAtFixedRate(1.second, 1.second)(SchedulerHelper.runnable(scanRegisteredJobs()))(jobExecutor)
    )
    lockRef.set(
      jobScheduler.scheduleAtFixedRate(1.second, 10.seconds)(utils.SchedulerHelper.runnable(updateLocks()))(jobExecutor)
    )
  }

  def stop(): Unit = {
    JobManager.logger.info("Stopping job manager")
    Option(scanRef.get()).foreach(_.cancel())
    Option(lockRef.get()).foreach(_.cancel())
    stopAllJobs()
    registeredJobs.clear()
    jobActorSystem.terminate()
  }

  def registerJob(job: Job): RegisteredJobContext = {
    val rctx = RegisteredJobContext(
      job = job,
      env = env,
      actorSystem = jobActorSystem,
      ranOnce = new AtomicBoolean(false),
      started = new AtomicBoolean(false),
      stopped = new AtomicBoolean(false),
      runId = new AtomicReference[String](env.snowflakeGenerator.nextIdStr()),
      ref = new AtomicReference[Option[Cancellable]](None)
    )
    val ctx  = JobContext(
      snowflake = rctx.runId.get(),
      attrs = TypedMap.empty,
      globalConfig = ConfigUtils.mergeOpt(
        env.datastores.globalConfigDataStore.latestSafe.map(_.scripts.jobConfig).getOrElse(Json.obj()),
        env.datastores.globalConfigDataStore.latestSafe.map(_.plugins.config)
      ),
      actorSystem = jobActorSystem,
      scheduler = jobActorSystem.scheduler
    )
    if (JobManager.logger.isDebugEnabled)
      JobManager.logger.debug(
        s"Registering job '${job.name}' with id '${job.uniqueId}' of kind ${job.kind} starting ${job.starting} with ${job
          .instantiation(ctx, env)} (${job.initialDelay(ctx, env)} / ${job.interval(ctx, env)} - ${job.cronExpression(ctx, env)})"
      )
    registeredJobs.putIfAbsent(job.uniqueId, rctx)
    rctx
  }

  def unregisterJob(job: Job): Unit = {
    if (JobManager.logger.isDebugEnabled)
      JobManager.logger.debug(s"Unregistering job '${job.name}' with id '${job.uniqueId}'")
    env.datastores.globalConfigDataStore.singleton().map { config =>
      registeredJobs.get(job.uniqueId).foreach(_.stop(config, env))
      registeredJobs.remove(job.uniqueId)
    }
  }
}

object DefaultJob extends Job {
  override def uniqueId: JobId                                       = JobId("otoroshi.script.default.DefaultJob")
  override def categories: Seq[NgPluginCategory]                     = Seq.empty
  override def predicate(ctx: JobContext, env: Env): Option[Boolean] = None
}

object CompilingJob extends Job {
  override def uniqueId: JobId                                       = JobId("otoroshi.script.default.CompilingJob")
  override def categories: Seq[NgPluginCategory]                     = Seq.empty
  override def predicate(ctx: JobContext, env: Env): Option[Boolean] = None
}

class StalledJobsDetector extends Job {

  private val logger = Logger("otoroshi-healthcheck-stalled-jobs-detector")

  override def categories: Seq[NgPluginCategory] = Seq.empty

  override def uniqueId: JobId = JobId("io.otoroshi.core.jobs.StalledJobsDetector")

  override def name: String = "Detect stalled jobs and relaunch them"

  override def jobVisibility: JobVisibility = JobVisibility.Internal

  override def kind: JobKind = JobKind.ScheduledEvery

  override def starting: JobStarting = JobStarting.Automatically

  override def instantiation(ctx: JobContext, env: Env): JobInstantiation =
    JobInstantiation.OneInstancePerOtoroshiLeaderInstance

  override def predicate(ctx: JobContext, env: Env): Option[Boolean] = None

  override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = 2.seconds.some

  override def interval(ctx: JobContext, env: Env): Option[FiniteDuration] = 20.seconds.some

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    implicit val mat = env.otoroshiMaterializer
    env.datastores.rawDataStore.keys(s"${env.storageRoot}:locks:jobs:*").flatMap { keys =>
      Source(keys.toList)
        .mapAsync(1) { key =>
          env.datastores.rawDataStore.pttl(key) map {
            case -1 =>
              val jobId = key.replace(s"${env.storageRoot}:locks:jobs:", "")
              logger.warn(s"job stalled detected: '${jobId}'")
              env.datastores.rawDataStore.del(Seq(key)).map { _ =>
                logger.info(s"job '${jobId}' has been unlocked and should work as expected now !")
              }
            case _  => ().vfuture
          }
        }
        .runWith(Sink.ignore)
        .map(_ => ())
    }
  }
}

trait OneTimeJob extends Job {

  private val canRun = new AtomicBoolean(false)

  def singleStart(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = funit
  def singleStop(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit]  = funit
  def singleRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit]   = funit

  final override def cronExpression(ctx: JobContext, env: Env): Option[String]       = None
  final override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = 1.second.some
  final override def interval(ctx: JobContext, env: Env): Option[FiniteDuration]     = 10.seconds.some
  final override def instantiation(ctx: JobContext, env: Env): JobInstantiation      =
    JobInstantiation.OneInstancePerOtoroshiCluster
  final override def jobVisibility: JobVisibility                                    = JobVisibility.Internal
  final override def starting: JobStarting                                           = JobStarting.Automatically

  private def unregisterSelf(env: Env): Unit = {
    env.jobManager.unregisterJob(this)
  }

  private def stopJob(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    if (canRun.get()) {
      env.datastores.rawDataStore
        .set(
          s"${env.storageRoot}:jobs:one-time-done:${uniqueId.id}",
          Json.obj("done" -> true, "date" -> DateTime.now().toString()).stringify.byteString,
          None
        )
        .flatMap { res =>
          canRun.set(false)
          unregisterSelf(env)
          if (canRun.get()) {
            singleStop(ctx)
          } else {
            funit
          }
        }
    } else {
      funit
    }
  }

  final override def jobStart(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    env.datastores.rawDataStore.get(s"${env.storageRoot}:jobs:one-time-done:${uniqueId.id}").flatMap {
      case None    => {
        canRun.set(true)
        singleStart(ctx)
      }
      case Some(_) => {
        canRun.set(false)
        unregisterSelf(env)
        funit
      }
    }
  }

  final override def jobStop(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    stopJob(ctx)
  }

  final override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    if (canRun.get()) {
      singleRun(ctx).andThen { case _ =>
        stopJob(ctx)
      }
    } else {
      funit
    }
  }
}

/*
class TestEveryJob extends Job {

  override def uniqueId: JobId                  = JobId("otoroshi.script.test.TestEveryJob")
  override def starting: JobStarting            = JobStarting.FromConfiguration
  override def kind:     JobKind                = JobKind.ScheduledEvery
  override def interval: Option[FiniteDuration] = Some(2.seconds)

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    println(s"Hello from job from $uniqueId - $underlyingId")
    Job.funit
  }
}

class TestAutonomousJob extends Job {

  override def uniqueId: JobId                  = JobId("otoroshi.script.test.TestAutonomousJob")
  override def starting: JobStarting            = JobStarting.FromConfiguration
  override def kind:     JobKind                = JobKind.Autonomous

  val ref = new AtomicReference[Cancellable]()

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    ref.set(ctx.scheduler.schedule(0.millisecond, 10.seconds) {
      println(s"Hello from job from $uniqueId - $underlyingId")
    })
    Job.funit
  }

  override def jobStart(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    println(s"Starting $uniqueId")
    Job.funit
  }

  override def jobStop(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    println(s"Stopping $uniqueId")
    Option(ref.get()).foreach(_.cancel())
    Job.funit
  }
}

class TestCronJob extends Job {

  override def uniqueId: JobId                  = JobId("otoroshi.script.test.TestCronJob")
  override def starting: JobStarting            = JobStarting.FromConfiguration
  override def kind:     JobKind                = JobKind.Cron
  override def cronExpression: Option[String] = Some("0 * * * * ?")

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    println(s"Hello from job from $uniqueId - $underlyingId")
    Job.funit
  }
}

class TestOnceJob extends Job {

  override def uniqueId: JobId                      = JobId("otoroshi.script.test.TestOnceJob")
  override def starting: JobStarting                = JobStarting.FromConfiguration
  override def kind:     JobKind                    = JobKind.ScheduledOnce
  override def initialDelay: Option[FiniteDuration] = Some(4.seconds)

  override def jobStart(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    println(s"Starting $uniqueId")
    Job.funit
  }

  override def jobStop(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    println(s"Stopping $uniqueId")
    Job.funit
  }

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    println(s"Hello from job from $uniqueId - $underlyingId")
    Job.funit
  }
}
 */
