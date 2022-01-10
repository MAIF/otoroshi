package otoroshi.next.models

import otoroshi.env.Env
import otoroshi.next.plugins.api.{NgNamedPlugin, NgPreRouting, PluginWrapper}
import otoroshi.utils.http.RequestImplicits._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}
import play.api.mvc.RequestHeader

import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag

case class PluginInstanceConfig(raw: JsObject) {
  def json: JsValue = raw
}

case class PluginInstance(plugin: String, enabled: Boolean, include: Seq[String], exclude: Seq[String], config: PluginInstanceConfig) {
  def json: JsValue = Json.obj(
    "enabled" -> enabled,
    "plugin" -> plugin,
    "include" -> include,
    "exclude" -> exclude,
    "config" -> config.json
  )
  def matches(request: RequestHeader): Boolean = {
    val uri = request.thePath
    if (!(include.isEmpty && exclude.isEmpty)) {
      !exclude.exists(p => otoroshi.utils.RegexPool.regex(p).matches(uri)) && include.exists(p =>
        otoroshi.utils.RegexPool.regex(p).matches(uri)
      )
    } else {
      true
    }
  }
  def getPlugin[A](implicit ec: ExecutionContext, env: Env, ct: ClassTag[A]): Option[A] = {
    env.scriptManager.getAnyScript[NgNamedPlugin](plugin) match {
      case Right(validator) if ct.runtimeClass.isAssignableFrom(validator.getClass) => validator.asInstanceOf[A].some
      case _                                                                        => None
    }
  }
}

case class Plugins(slots: Seq[PluginInstance]) {

  def json: JsValue = JsArray(slots.map(_.json))

  def preRoutePlugins(request: RequestHeader)(implicit ec: ExecutionContext, env: Env): Seq[PluginWrapper[NgPreRouting]] = {
    slots
      .filter(_.enabled)
      .filter(_.matches(request))
      .map(inst => (inst, inst.getPlugin[NgPreRouting]))
      .collect {
        case (inst, Some(plugin)) => PluginWrapper(inst, plugin)
      }
  }
}
