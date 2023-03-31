package otoroshi.models

import otoroshi.env.Env
import otoroshi.next.plugins.api.{NgPluginCategory, NgPluginVisibility, NgStep}
import otoroshi.next.plugins.{CachedWasmScript, WasmConfig, WasmUtils}
import otoroshi.script._
import otoroshi.security.IdGenerator
import otoroshi.storage.{BasicStore, RedisLike, RedisLikeStore}
import otoroshi.utils.syntax.implicits.{BetterJsReadable, BetterSyntax}
import play.api.libs.json._

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class WasmPlugin(
    id: String,
    name: String,
    description: String,
    steps: Seq[NgStep] = Seq.empty,
    config: WasmConfig,
    tags: Seq[String] = Seq.empty,
    metadata: Map[String, String] = Map.empty,
    location: otoroshi.models.EntityLocation = otoroshi.models.EntityLocation()
) extends otoroshi.models.EntityLocationSupport {
  def save()(implicit ec: ExecutionContext, env: Env) = env.datastores.wasmPluginsDataStore.set(this)
  override def internalId: String                     = id
  override def json: JsValue                          = WasmPlugin.format.writes(this)
  override def theName: String                        = name
  override def theDescription: String                 = description
  override def theTags: Seq[String]                   = tags
  override def theMetadata: Map[String, String]       = metadata
}

object WasmPlugin {
  def fromJsons(value: JsValue): WasmPlugin =
    try {
      format.reads(value).get
    } catch {
      case e: Throwable => throw e
    }
  val format                                = new Format[WasmPlugin] {
    override def writes(o: WasmPlugin): JsValue             = o.location.jsonWithKey ++ Json.obj(
      "id"          -> o.id,
      "name"        -> o.name,
      "description" -> o.description,
      "config"      -> o.config.json,
      "steps"       -> JsArray(o.steps.map(_.json)),
      "metadata"    -> o.metadata,
      "tags"        -> JsArray(o.tags.map(JsString.apply))
    )
    override def reads(json: JsValue): JsResult[WasmPlugin] = Try {
      WasmPlugin(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = (json \ "id").as[String],
        name = (json \ "name").as[String],
        description = (json \ "description").as[String],
        config = (json \ "config").asOpt(WasmConfig.format).getOrElse(WasmConfig()),
        steps = (json \ "steps")
          .asOpt[Seq[String]]
          .map(_.map(NgStep.apply).collect { case Some(s) => s })
          .getOrElse(Seq.empty),
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String])
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

trait WasmPluginDataStore extends BasicStore[WasmPlugin] {
  def template(env: Env): WasmPlugin = {
    val defaultWasmPlugin = WasmPlugin(
      id = IdGenerator.namedId("wasm-plugin", env),
      name = "New wasm plugin",
      description = "New wasm plugin",
      tags = Seq.empty,
      metadata = Map.empty,
      config = WasmConfig()
    )
    env.datastores.globalConfigDataStore
      .latest()(env.otoroshiExecutionContext, env)
      .templates
      .script
      .map { template =>
        WasmPlugin.format.reads(defaultWasmPlugin.json.asObject.deepMerge(template)).get
      }
      .getOrElse {
        defaultWasmPlugin
      }
  }
}

class KvWasmPluginDataStore(redisCli: RedisLike, _env: Env)
    extends WasmPluginDataStore
    with RedisLikeStore[WasmPlugin] {
  override def fmt: Format[WasmPlugin]                 = WasmPlugin.format
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def key(id: String): String                 = s"${_env.storageRoot}:wasm-plugins:$id"
  override def extractId(value: WasmPlugin): String    = value.id
}

class WasmPluginsCacheManager extends Job {

  override def core: Boolean                                                   = true
  override def name: String                                                    = "Wasm Plugins cache manager"
  override def description: Option[String]                                     = "this job try to cache plugins before they expire".some
  override def defaultConfig: Option[JsObject]                                 = None
  override def categories: Seq[NgPluginCategory]                               = Seq(NgPluginCategory.Other)
  override def visibility: NgPluginVisibility                                  = NgPluginVisibility.NgInternal
  override def steps: Seq[NgStep]                                              = Seq(NgStep.Job)
  override def jobVisibility: JobVisibility                                    = JobVisibility.Internal
  override def starting: JobStarting                                           = JobStarting.Automatically
  override def uniqueId: JobId                                                 = JobId(s"io.otoroshi.jobs.wasm.WasmPluginsCacheManager")
  override def kind: JobKind                                                   = JobKind.ScheduledEvery
  override def instantiation(ctx: JobContext, env: Env): JobInstantiation      =
    JobInstantiation.OneInstancePerOtoroshiInstance
  override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = 5.seconds.some
  override def interval(ctx: JobContext, env: Env): Option[FiniteDuration]     = 20.seconds.some
  override def cronExpression(ctx: JobContext, env: Env): Option[String]       = None

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    env.proxyState.allWasmPlugins().foreach { plugin =>
      val now = System.currentTimeMillis()
      WasmUtils.scriptCache(env).getIfPresent(plugin.config.source.cacheKey) match {
        case None                                                                       => plugin.config.source.getWasm()
        case Some(CachedWasmScript(_, createAt)) if (createAt + env.wasmCacheTtl) < now =>
          plugin.config.source.getWasm()
        case Some(CachedWasmScript(_, createAt))
            if (createAt + env.wasmCacheTtl) > now && (createAt + env.wasmCacheTtl + 1000) < now =>
          plugin.config.source.getWasm()
        case _                                                                          => ()
      }
    }
    funit
  }
}
