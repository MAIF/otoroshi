package otoroshi.next.models

import play.api.libs.json.{JsArray, JsObject, JsValue, Json}

case class PluginInstanceConfig(raw: JsObject) {
  def json: JsValue = raw
}

case class PluginInstance(plugin: String, enabled: Boolean, include: Seq[String], exclude: Seq[String], config: PluginInstanceConfig) {
  def json: JsValue = Json.obj(
    "enabled" -> enabled,
    "plugin" -> plugin,
    "include" -> include,
    "include" -> exclude,
    "config" -> config.json
  )
}

case class Plugins(slots: Seq[PluginInstance]) {
  def json: JsValue = JsArray(slots.map(_.json))
}

object Plugins {
  def aaapply(plugins: PluginInstance*): Plugins = {
    Plugins(plugins)
  }
}
