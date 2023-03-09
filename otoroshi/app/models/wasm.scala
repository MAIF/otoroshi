package otoroshi.models

import diffson.DiffOps
import otoroshi.env.Env
import otoroshi.script.PluginType
import otoroshi.security.IdGenerator
import otoroshi.storage.{BasicStore, RedisLike, RedisLikeStore}
import otoroshi.utils.syntax.implicits.BetterJsReadable
import play.api.libs.json.{Format, JsResult, JsValue}

case class WasmPlugin(
  id: String,
  name: String,
  description: String,
  tags: Seq[String] = Seq.empty,
  metadata: Map[String, String] = Map.empty,
  location: otoroshi.models.EntityLocation = otoroshi.models.EntityLocation()
) extends otoroshi.models.EntityLocationSupport {
  override def internalId: String = id
  override def json: JsValue = WasmPlugin.format.writes(this)
  override def theName: String = name
  override def theDescription: String = description
  override def theTags: Seq[String] = tags
  override def theMetadata: Map[String, String] = metadata
}

object WasmPlugin {
  val format = new Format[WasmPlugin] {
    override def writes(o: WasmPlugin): JsValue = ???
    override def reads(json: JsValue): JsResult[WasmPlugin] = ???
  }
}

trait WasmPluginDataStore extends BasicStore[WasmPlugin] {
  def template(env: Env): WasmPlugin = {
    val defaultWasmPlugin = WasmPlugin(
      id = IdGenerator.namedId("wasm-plugin", env),
      name = "New wasm plugin",
      description = "New wasm plugin",
      tags = Seq.empty,
      metadata = Map.empty
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

class KvWasmPluginDataStore(redisCli: RedisLike, _env: Env) extends WasmPluginDataStore with RedisLikeStore[WasmPlugin] {
  override def fmt: Format[WasmPlugin]                 = WasmPlugin.format
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def key(id: String): String                 = s"${_env.storageRoot}:wasm-plugins:$id"
  override def extractId(value: WasmPlugin): String    = value.id
}

// TODO: refactor access model
// TODO: fill model
// TODO: support in plugins
// TODO: job for caching

