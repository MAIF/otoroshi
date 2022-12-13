package otoroshi.jobs.newengine

import otoroshi.env.Env
import otoroshi.models.GlobalConfig
import otoroshi.next.plugins.api.NgPluginCategory
import otoroshi.next.proxy.{ProxyEngine, ProxyEngineConfig}
import otoroshi.script._
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json.{JsNull, JsObject, JsValue, Json}

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Try}

object NewEngine {
  def enabledFromConfig(config: GlobalConfig, env: Env): Boolean = {
    val pluginEnabled = config.plugins.enabled
    val pluginInRefs = config.plugins.refs.contains(s"cp:${classOf[ProxyEngine].getName}")
    val configEnabled = config.plugins.config.select(ProxyEngine.configRoot).asOpt[JsValue].map(s => ProxyEngineConfig.parse(s, env)).getOrElse(ProxyEngineConfig.default).enabled
    pluginEnabled && pluginInRefs && configEnabled
  }
  def enabled(implicit env: Env, ec: ExecutionContext): Future[Boolean] = {
    env.datastores.globalConfigDataStore.singleton().map { config =>
      enabledFromConfig(config, env)
    }
  }
}

class NewEngineJob extends Job {

  private val logger = Logger("otoroshi-jobs-new-engine-alert")

  override def categories: Seq[NgPluginCategory] = Seq.empty

  override def uniqueId: JobId = JobId("io.otoroshi.core.jobs.NewEngineJob")

  override def name: String = "Otoroshi new engine alert"

  override def defaultConfig: Option[JsObject] = None

  override def description: Option[String] =
    s"""This job will check if new engine is enabled""".stripMargin.some

  override def jobVisibility: JobVisibility = JobVisibility.Internal

  override def kind: JobKind = JobKind.ScheduledEvery

  override def starting: JobStarting = JobStarting.Automatically

  override def instantiation(ctx: JobContext, env: Env): JobInstantiation =
    JobInstantiation.OneInstancePerOtoroshiInstance

  override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = 5.seconds.some

  override def interval(ctx: JobContext, env: Env): Option[FiniteDuration] = 24.hours.some

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    NewEngine.enabled.map { enabled  =>
      if (!enabled) {
        logger.info(s"You are using the legacy Otoroshi proxy engine !")
        logger.info(s"The new proxy engine is now ready for production :)")
        logger.info(s"You can check the documentation at https://maif.github.io/otoroshi/manual/topics/engine.html")
      }
    }
    ().future
  }
}