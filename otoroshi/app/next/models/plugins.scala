package otoroshi.next.models

import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.utils.http.RequestImplicits._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.RequestHeader

import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag

case class PluginInstanceConfig(raw: JsObject = Json.obj()) {
  def json: JsValue = raw
}

object PluginInstance {
  def readFrom(obj: JsValue): PluginInstance = {
    PluginInstance(
      plugin = obj.select("plugin").asString,
      enabled = obj.select("enabled").asOpt[Boolean].getOrElse(true),
      include = obj.select("include").asOpt[Seq[String]].getOrElse(Seq.empty),
      exclude = obj.select("exclude").asOpt[Seq[String]].getOrElse(Seq.empty),
      config = PluginInstanceConfig(obj.select("config").asOpt[JsObject].getOrElse(Json.obj()))
    )
  }
}

case class PluginInstance(plugin: String, enabled: Boolean = true, debug: Boolean = false, include: Seq[String] = Seq.empty, exclude: Seq[String] = Seq.empty, config: PluginInstanceConfig = PluginInstanceConfig()) {
  def json: JsValue = Json.obj(
    "enabled" -> enabled,
    "debug" -> debug,
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

  def getPluginByClass[A](implicit ct: ClassTag[A]): Option[PluginInstance] = {
    val name = s"cp:${ct.runtimeClass.getName}"
    slots.find(pi => pi.plugin == name).filter(_.enabled)
  }

  def allPlugins()(implicit ec: ExecutionContext, env: Env): Seq[PluginWrapper[NgNamedPlugin]] = {
    slots
      .map(inst => (inst, inst.getPlugin[NgNamedPlugin]))
      .collect {
        case (inst, Some(plugin)) => PluginWrapper(inst, plugin)
      } //.debug(seq => println(s"found ${seq.size} request-transformer plugins"))
  }

  def transformerPlugins(request: RequestHeader)(implicit ec: ExecutionContext, env: Env): Seq[PluginWrapper[NgRequestTransformer]] = {
    slots
      .filter(_.enabled)
      .filter(_.matches(request))
      .map(inst => (inst, inst.getPlugin[NgRequestTransformer]))
      .collect {
        case (inst, Some(plugin)) => PluginWrapper(inst, plugin)
      } //.debug(seq => println(s"found ${seq.size} request-transformer plugins"))
  }

  def preRoutePlugins(request: RequestHeader)(implicit ec: ExecutionContext, env: Env): Seq[PluginWrapper[NgPreRouting]] = {
    slots
      .filter(_.enabled)
      .filter(_.matches(request))
      .map(inst => (inst, inst.getPlugin[NgPreRouting]))
      .collect {
        case (inst, Some(plugin)) => PluginWrapper(inst, plugin)
      } //.debug(seq => println(s"found ${seq.size} pre-route plugins"))
  }

  def accessValidatorPlugins(request: RequestHeader)(implicit ec: ExecutionContext, env: Env): Seq[PluginWrapper[NgAccessValidator]] = {
    slots
      .filter(_.enabled)
      .filter(_.matches(request))
      .map(inst => (inst, inst.getPlugin[NgAccessValidator]))
      .collect {
        case (inst, Some(plugin)) => PluginWrapper(inst, plugin)
      } //.debug(seq => println(s"found ${seq.size} access-validator plugins"))
  }
}

object Plugins {
  def readFrom(lookup: JsLookupResult): Plugins = {
    lookup.asOpt[JsObject] match {
      case None => Plugins(Seq.empty)
      case Some(obj) => Plugins(
        slots = obj.select("slots").asOpt[Seq[JsValue]].map(_.map(PluginInstance.readFrom)).getOrElse(Seq.empty)
      )
    }
  }
}
