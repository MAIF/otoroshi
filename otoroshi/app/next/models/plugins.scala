package otoroshi.next.models

import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.utils.http.RequestImplicits._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.RequestHeader

import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag

case class NgPluginInstanceConfig(raw: JsObject = Json.obj()) extends AnyVal {
  def json: JsValue = raw
}

object NgPluginInstance {
  def readFrom(obj: JsValue): NgPluginInstance = {
    NgPluginInstance(
      plugin = obj.select("plugin").asString,
      enabled = obj.select("enabled").asOpt[Boolean].getOrElse(true),
      include = obj.select("include").asOpt[Seq[String]].getOrElse(Seq.empty),
      exclude = obj.select("exclude").asOpt[Seq[String]].getOrElse(Seq.empty),
      config = NgPluginInstanceConfig(obj.select("config").asOpt[JsObject].getOrElse(Json.obj()))
    )
  }
}

case class NgPluginInstance(plugin: String, enabled: Boolean = true, debug: Boolean = false, include: Seq[String] = Seq.empty, exclude: Seq[String] = Seq.empty, config: NgPluginInstanceConfig = NgPluginInstanceConfig()) {
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

case class NgPlugins(slots: Seq[NgPluginInstance]) extends AnyVal {

  def json: JsValue = JsArray(slots.map(_.json))

  def getPluginByClass[A](implicit ct: ClassTag[A]): Option[NgPluginInstance] = {
    val name = s"cp:${ct.runtimeClass.getName}"
    slots.find(pi => pi.plugin == name).filter(_.enabled)
  }

  def allPlugins()(implicit ec: ExecutionContext, env: Env): Seq[NgPluginWrapper[NgNamedPlugin]] = {
    slots
      .map(inst => (inst, inst.getPlugin[NgNamedPlugin]))
      .collect {
        case (inst, Some(plugin)) => NgPluginWrapper(inst, plugin)
      }
  }

  def requestSinkPlugins(request: RequestHeader)(implicit ec: ExecutionContext, env: Env): Seq[NgPluginWrapper[NgRequestSink]] = {
    slots
      .filter(_.enabled)
      .filter(_.matches(request))
      .map(inst => (inst, inst.getPlugin[NgRequestSink]))
      .collect {
        case (inst, Some(plugin)) => NgPluginWrapper(inst, plugin)
      }
  }

  def transformerPlugins(request: RequestHeader)(implicit ec: ExecutionContext, env: Env): Seq[NgPluginWrapper[NgRequestTransformer]] = {
    slots
      .filter(_.enabled)
      .filter(_.matches(request))
      .map(inst => (inst, inst.getPlugin[NgRequestTransformer]))
      .collect {
        case (inst, Some(plugin)) => NgPluginWrapper(inst, plugin)
      }
  }

  def transformerPluginsWithCallbacks(request: RequestHeader)(implicit ec: ExecutionContext, env: Env): Seq[NgPluginWrapper[NgRequestTransformer]] = {
    transformerPlugins(request).filter(_.plugin.usesCallbacks)
  }

  def transformerPluginsThatTransformsRequest(request: RequestHeader)(implicit ec: ExecutionContext, env: Env): Seq[NgPluginWrapper[NgRequestTransformer]] = {
    transformerPlugins(request).filter(_.plugin.transformsRequest)
  }

  def transformerPluginsThatTransformsResponse(request: RequestHeader)(implicit ec: ExecutionContext, env: Env): Seq[NgPluginWrapper[NgRequestTransformer]] = {
    transformerPlugins(request).filter(_.plugin.transformsResponse)
  }

  def transformerPluginsThatTransformsError(request: RequestHeader)(implicit ec: ExecutionContext, env: Env): Seq[NgPluginWrapper[NgRequestTransformer]] = {
    transformerPlugins(request).filter(_.plugin.transformsError)
  }

  def preRoutePlugins(request: RequestHeader)(implicit ec: ExecutionContext, env: Env): Seq[NgPluginWrapper[NgPreRouting]] = {
    slots
      .filter(_.enabled)
      .filter(_.matches(request))
      .map(inst => (inst, inst.getPlugin[NgPreRouting]))
      .collect {
        case (inst, Some(plugin)) => NgPluginWrapper(inst, plugin)
      }
  }

  def accessValidatorPlugins(request: RequestHeader)(implicit ec: ExecutionContext, env: Env): Seq[NgPluginWrapper[NgAccessValidator]] = {
    slots
      .filter(_.enabled)
      .filter(_.matches(request))
      .map(inst => (inst, inst.getPlugin[NgAccessValidator]))
      .collect {
        case (inst, Some(plugin)) => NgPluginWrapper(inst, plugin)
      }
  }

  def routeMatcherPlugins(request: RequestHeader)(implicit ec: ExecutionContext, env: Env): Seq[NgPluginWrapper[NgRouteMatcher]] = {
    slots
      .filter(_.enabled)
      .filter(_.matches(request))
      .map(inst => (inst, inst.getPlugin[NgRouteMatcher]))
      .collect {
        case (inst, Some(plugin)) => NgPluginWrapper(inst, plugin)
      }
  }

  def tunnelHandlerPlugins(request: RequestHeader)(implicit ec: ExecutionContext, env: Env): Seq[NgPluginWrapper[NgTunnelHandler]] = {
    slots
      .filter(_.enabled)
      .filter(_.matches(request))
      .map(inst => (inst, inst.getPlugin[NgTunnelHandler]))
      .collect {
        case (inst, Some(plugin)) => NgPluginWrapper(inst, plugin)
      }
  }
}

object NgPlugins {
  def readFrom(lookup: JsLookupResult): NgPlugins = {
    lookup.asOpt[JsObject] match {
      case None => NgPlugins(Seq.empty)
      case Some(obj) => NgPlugins(
        slots = obj.select("slots").asOpt[Seq[JsValue]].map(_.map(NgPluginInstance.readFrom)).getOrElse(Seq.empty)
      )
    }
  }
}

case class NgContextualPlugins(plugins: NgPlugins, global_plugins: NgPlugins, request: RequestHeader, _env: Env, _ec: ExecutionContext) {

  implicit val env: Env = _env
  implicit val ec: ExecutionContext = _ec

  lazy val (enabledPlugins, disabledPlugins) = (global_plugins.slots ++ plugins.slots)
    .partition(_.enabled)

  lazy val (allPlugins, filteredPlugins) = enabledPlugins
    .partition(_.matches(request))

  lazy val requestSinkPlugins = allPlugins
    .map(inst => (inst, inst.getPlugin[NgRequestSink]))
    .collect {
      case (inst, Some(plugin)) => NgPluginWrapper(inst, plugin)
    }

  lazy val transformerPlugins = allPlugins
    .map(inst => (inst, inst.getPlugin[NgRequestTransformer]))
    .collect {
      case (inst, Some(plugin)) => NgPluginWrapper(inst, plugin)
    }

  lazy val (transformerPluginsWithCallbacks, tpwoCallbacks) = transformerPlugins.partition(_.plugin.usesCallbacks)
  lazy val (transformerPluginsThatTransformsRequest, tpwoRequest) = transformerPlugins.partition(_.plugin.transformsRequest)
  lazy val (transformerPluginsThatTransformsResponse, tpwoResponse) = transformerPlugins.partition(_.plugin.transformsResponse)
  lazy val (transformerPluginsThatTransformsError, tpwoErrors) = transformerPlugins.partition(_.plugin.transformsError)

  lazy val preRoutePlugins = allPlugins
    .map(inst => (inst, inst.getPlugin[NgPreRouting]))
    .collect {
      case (inst, Some(plugin)) => NgPluginWrapper(inst, plugin)
    }

  lazy val accessValidatorPlugins = allPlugins
    .map(inst => (inst, inst.getPlugin[NgAccessValidator]))
    .collect {
      case (inst, Some(plugin)) => NgPluginWrapper(inst, plugin)
    }

  lazy val routeMatcherPlugins = allPlugins
    .map(inst => (inst, inst.getPlugin[NgRouteMatcher]))
    .collect {
      case (inst, Some(plugin)) => NgPluginWrapper(inst, plugin)
    }

  lazy val tunnelHandlerPlugins = allPlugins
    .map(inst => (inst, inst.getPlugin[NgTunnelHandler]))
    .collect {
      case (inst, Some(plugin)) => NgPluginWrapper(inst, plugin)
    }
}
