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

case class NgPluginInstance(
    plugin: String,
    enabled: Boolean = true,
    debug: Boolean = false,
    include: Seq[String] = Seq.empty,
    exclude: Seq[String] = Seq.empty,
    config: NgPluginInstanceConfig = NgPluginInstanceConfig()
) {
  def json: JsValue = Json.obj(
    "enabled" -> enabled,
    "debug"   -> debug,
    "plugin"  -> plugin,
    "include" -> include,
    "exclude" -> exclude,
    "config"  -> config.json
  )
  def matches(request: RequestHeader): Boolean = {
    val uri = request.thePath
    if (!(include.isEmpty && exclude.isEmpty)) {
      val canpass    = if (include.isEmpty) true else include.exists(p => otoroshi.utils.RegexPool.regex(p).matches(uri))
      val cannotpass =
        if (exclude.isEmpty) false else exclude.exists(p => otoroshi.utils.RegexPool.regex(p).matches(uri))
      canpass && !cannotpass
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

  def add(plugin: NgPluginInstance): NgPlugins = copy(slots = slots :+ plugin)

  def json: JsValue = JsArray(slots.map(_.json))

  def getPluginByClass[A](implicit ct: ClassTag[A]): Option[NgPluginInstance] = {
    val name = s"cp:${ct.runtimeClass.getName}"
    slots.find(pi => pi.plugin == name).filter(_.enabled)
  }

  def allPlugins()(implicit ec: ExecutionContext, env: Env): Seq[NgPluginWrapper[NgNamedPlugin]] = {
    slots
      .map(inst => (inst, inst.getPlugin[NgNamedPlugin]))
      .collect { case (inst, Some(plugin)) =>
        NgPluginWrapper.NgSimplePluginWrapper(inst, plugin)
      }
  }

  def requestSinkPlugins(
      request: RequestHeader
  )(implicit ec: ExecutionContext, env: Env): Seq[NgPluginWrapper[NgRequestSink]] = {
    slots
      .filter(_.enabled)
      .filter(_.matches(request))
      .map(inst => (inst, inst.getPlugin[NgRequestSink]))
      .collect { case (inst, Some(plugin)) =>
        NgPluginWrapper.NgSimplePluginWrapper(inst, plugin)
      }
  }

  def transformerPlugins(
      request: RequestHeader
  )(implicit ec: ExecutionContext, env: Env): Seq[NgPluginWrapper[NgRequestTransformer]] = {
    slots
      .filter(_.enabled)
      .filter(_.matches(request))
      .map(inst => (inst, inst.getPlugin[NgRequestTransformer]))
      .collect { case (inst, Some(plugin)) =>
        NgPluginWrapper.NgSimplePluginWrapper(inst, plugin)
      }
  }

  def transformerPluginsWithCallbacks(
      request: RequestHeader
  )(implicit ec: ExecutionContext, env: Env): Seq[NgPluginWrapper[NgRequestTransformer]] = {
    transformerPlugins(request).filter(_.plugin.usesCallbacks)
  }

  def transformerPluginsThatTransformsRequest(
      request: RequestHeader
  )(implicit ec: ExecutionContext, env: Env): Seq[NgPluginWrapper[NgRequestTransformer]] = {
    transformerPlugins(request).filter(_.plugin.transformsRequest)
  }

  def transformerPluginsThatTransformsResponse(
      request: RequestHeader
  )(implicit ec: ExecutionContext, env: Env): Seq[NgPluginWrapper[NgRequestTransformer]] = {
    transformerPlugins(request).filter(_.plugin.transformsResponse)
  }

  def transformerPluginsThatTransformsError(
      request: RequestHeader
  )(implicit ec: ExecutionContext, env: Env): Seq[NgPluginWrapper[NgRequestTransformer]] = {
    transformerPlugins(request).filter(_.plugin.transformsError)
  }

  def preRoutePlugins(
      request: RequestHeader
  )(implicit ec: ExecutionContext, env: Env): Seq[NgPluginWrapper[NgPreRouting]] = {
    slots
      .filter(_.enabled)
      .filter(_.matches(request))
      .map(inst => (inst, inst.getPlugin[NgPreRouting]))
      .collect { case (inst, Some(plugin)) =>
        NgPluginWrapper.NgSimplePluginWrapper(inst, plugin)
      }
  }

  def accessValidatorPlugins(
      request: RequestHeader
  )(implicit ec: ExecutionContext, env: Env): Seq[NgPluginWrapper[NgAccessValidator]] = {
    slots
      .filter(_.enabled)
      .filter(_.matches(request))
      .map(inst => (inst, inst.getPlugin[NgAccessValidator]))
      .collect { case (inst, Some(plugin)) =>
        NgPluginWrapper.NgSimplePluginWrapper(inst, plugin)
      }
  }

  def routeMatcherPlugins(
      request: RequestHeader
  )(implicit ec: ExecutionContext, env: Env): Seq[NgPluginWrapper[NgRouteMatcher]] = {
    slots
      .filter(_.enabled)
      .filter(_.matches(request))
      .map(inst => (inst, inst.getPlugin[NgRouteMatcher]))
      .collect { case (inst, Some(plugin)) =>
        NgPluginWrapper.NgSimplePluginWrapper(inst, plugin)
      }
  }

  def tunnelHandlerPlugins(
      request: RequestHeader
  )(implicit ec: ExecutionContext, env: Env): Seq[NgPluginWrapper[NgTunnelHandler]] = {
    slots
      .filter(_.enabled)
      .filter(_.matches(request))
      .map(inst => (inst, inst.getPlugin[NgTunnelHandler]))
      .collect { case (inst, Some(plugin)) =>
        NgPluginWrapper.NgSimplePluginWrapper(inst, plugin)
      }
  }
}

object NgPlugins {
  def readFrom(lookup: JsLookupResult): NgPlugins = {
    lookup.asOpt[JsArray] match {
      case None      => NgPlugins(Seq.empty)
      case Some(arr) =>
        NgPlugins(
          slots = arr.asOpt[Seq[JsValue]].map(_.map(NgPluginInstance.readFrom)).getOrElse(Seq.empty)
        )
    }
  }
}

case class NgContextualPlugins(
    plugins: NgPlugins,
    global_plugins: NgPlugins,
    request: RequestHeader,
    nextPluginsMerge: Boolean,
    _env: Env,
    _ec: ExecutionContext
) {

  implicit val env: Env             = _env
  implicit val ec: ExecutionContext = _ec

  lazy val (enabledPlugins, disabledPlugins) = (global_plugins.slots ++ plugins.slots)
    .partition(_.enabled)

  lazy val (allPlugins, filteredPlugins) = enabledPlugins
    .partition(_.matches(request))

  lazy val requestSinkPlugins = allPlugins
    .map(inst => (inst, inst.getPlugin[NgRequestSink]))
    .collect { case (inst, Some(plugin)) =>
      NgPluginWrapper.NgSimplePluginWrapper(inst, plugin)
    }

  lazy val transformerPlugins = allPlugins
    .map(inst => (inst, inst.getPlugin[NgRequestTransformer]))
    .collect { case (inst, Some(plugin)) =>
      NgPluginWrapper.NgSimplePluginWrapper(inst, plugin)
    }

  lazy val (transformerPluginsWithCallbacks, tpwoCallbacks)    = transformerPlugins.partition(_.plugin.usesCallbacks)
  lazy val (transformerPluginsThatTransformsRequest, tpwoRequest) = {
    val (plugs, b) = transformerPlugins.partition(_.plugin.transformsRequest)
    if (nextPluginsMerge && plugs.size > 1) {
      val new_plugins = plugs
        .foldLeft((true, Seq.empty[NgPluginWrapper[NgRequestTransformer]])) {
          case ((latestAsync, coll), plug) => {
            if (plug.plugin.isTransformRequestAsync) {
              (true, coll :+ plug)
            } else {
              if (!latestAsync) {
                coll.last match {
                  case wrap @ NgPluginWrapper.NgSimplePluginWrapper(_, _)               =>
                    (false, coll.init :+ NgPluginWrapper.NgMergedRequestTransformerPluginWrapper(Seq(wrap, plug)))
                  case NgPluginWrapper.NgMergedRequestTransformerPluginWrapper(plugins) =>
                    (false, coll.init :+ NgPluginWrapper.NgMergedRequestTransformerPluginWrapper(plugins :+ plug))
                  case _                                                                => (true, coll :+ plug)
                }
              } else {
                (false, coll :+ plug)
              }
            }
          }
        }
        ._2
      (new_plugins, b)
    } else {
      (plugs, b)
    }
  }
  lazy val (transformerPluginsThatTransformsResponse, tpwoResponse) = {
    val (plugs, b) = transformerPlugins.partition(_.plugin.transformsResponse)
    if (nextPluginsMerge && plugs.size > 1) {
      val new_plugins = plugs
        .foldLeft((true, Seq.empty[NgPluginWrapper[NgRequestTransformer]])) {
          case ((latestAsync, coll), plug) => {
            if (plug.plugin.isTransformResponseAsync) {
              (true, coll :+ plug)
            } else {
              if (!latestAsync) {
                coll.last match {
                  case wrap @ NgPluginWrapper.NgSimplePluginWrapper(_, _)               =>
                    (false, coll.init :+ NgPluginWrapper.NgMergedRequestTransformerPluginWrapper(Seq(wrap, plug)))
                  case NgPluginWrapper.NgMergedRequestTransformerPluginWrapper(plugins) =>
                    (false, coll.init :+ NgPluginWrapper.NgMergedRequestTransformerPluginWrapper(plugins :+ plug))
                  case _                                                                => (true, coll :+ plug)
                }
              } else {
                (false, coll :+ plug)
              }
            }
          }
        }
        ._2
      (new_plugins, b)
    } else {
      (plugs, b)
    }
  }
  lazy val (transformerPluginsThatTransformsError, tpwoErrors) = transformerPlugins.partition(_.plugin.transformsError)

  lazy val preRoutePlugins = {
    val plugs = allPlugins
      .map(inst => (inst, inst.getPlugin[NgPreRouting]))
      .collect { case (inst, Some(plugin)) =>
        NgPluginWrapper.NgSimplePluginWrapper(inst, plugin)
      }
    if (nextPluginsMerge && plugs.size > 1) {
      val new_plugins = plugs
        .foldLeft((true, Seq.empty[NgPluginWrapper[NgPreRouting]])) {
          case ((latestAsync, coll), plug) => {
            if (plug.plugin.isPreRouteAsync) {
              (true, coll :+ plug)
            } else {
              if (!latestAsync) {
                coll.last match {
                  case wrap @ NgPluginWrapper.NgSimplePluginWrapper(_, _)       =>
                    (false, coll.init :+ NgPluginWrapper.NgMergedPreRoutingPluginWrapper(Seq(wrap, plug)))
                  case NgPluginWrapper.NgMergedPreRoutingPluginWrapper(plugins) =>
                    (false, coll.init :+ NgPluginWrapper.NgMergedPreRoutingPluginWrapper(plugins :+ plug))
                  case _                                                        => (true, coll :+ plug)
                }
              } else {
                (false, coll :+ plug)
              }
            }
          }
        }
        ._2
      new_plugins
    } else {
      plugs
    }
  }

  lazy val accessValidatorPlugins = {
    val plugs = allPlugins
      .map(inst => (inst, inst.getPlugin[NgAccessValidator]))
      .collect { case (inst, Some(plugin)) =>
        NgPluginWrapper.NgSimplePluginWrapper(inst, plugin)
      }
    if (nextPluginsMerge && plugs.size > 1) {
      val new_plugins = plugs
        .foldLeft((true, Seq.empty[NgPluginWrapper[NgAccessValidator]])) {
          case ((latestAsync, coll), plug) => {
            if (plug.plugin.isAccessAsync) {
              (true, coll :+ plug)
            } else {
              if (!latestAsync) {
                coll.last match {
                  case wrap @ NgPluginWrapper.NgSimplePluginWrapper(_, _)            =>
                    (false, coll.init :+ NgPluginWrapper.NgMergedAccessValidatorPluginWrapper(Seq(wrap, plug)))
                  case NgPluginWrapper.NgMergedAccessValidatorPluginWrapper(plugins) =>
                    (false, coll.init :+ NgPluginWrapper.NgMergedAccessValidatorPluginWrapper(plugins :+ plug))
                  case _                                                             => (true, coll :+ plug)
                }
              } else {
                (false, coll :+ plug)
              }
            }
          }
        }
        ._2
      new_plugins
    } else {
      plugs
    }
  }

  lazy val routeMatcherPlugins = allPlugins
    .map(inst => (inst, inst.getPlugin[NgRouteMatcher]))
    .collect { case (inst, Some(plugin)) =>
      NgPluginWrapper.NgSimplePluginWrapper(inst, plugin)
    }

  lazy val tunnelHandlerPlugins = allPlugins
    .map(inst => (inst, inst.getPlugin[NgTunnelHandler]))
    .collect { case (inst, Some(plugin)) =>
      NgPluginWrapper.NgSimplePluginWrapper(inst, plugin)
    }
}
