package otoroshi.next.models

import otoroshi.env.Env
import otoroshi.next.plugins.WasmJob
import otoroshi.next.plugins.api._
import otoroshi.utils.http.RequestImplicits._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.RequestHeader

import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

case class NgPluginInstanceConfig(raw: JsObject = Json.obj()) extends AnyVal {
  def json: JsValue = raw
}

case class PluginIndex(
    sink: Option[Double] = None,
    preRoute: Option[Double] = None,
    validateAccess: Option[Double] = None,
    transformRequest: Option[Double] = None,
    transformResponse: Option[Double] = None,
    matchRoute: Option[Double] = None,
    handlesTunnel: Option[Double] = None,
    callBackend: Option[Double] = None
) {
  def json: JsValue = PluginIndex.format.writes(this)
}

object PluginIndex {
  val format = new Format[PluginIndex] {
    override def reads(json: JsValue): JsResult[PluginIndex] = Try {
      PluginIndex(
        sink = json.select("sink").asOpt[Int].map(_.toDouble).orElse(json.select("sink").asOpt[Double]),
        preRoute = json.select("pre_route").asOpt[Int].map(_.toDouble).orElse(json.select("pre_route").asOpt[Double]),
        validateAccess = json
          .select("validate_access")
          .asOpt[Int]
          .map(_.toDouble)
          .orElse(json.select("validate_access").asOpt[Double]),
        transformRequest = json
          .select("transform_request")
          .asOpt[Int]
          .map(_.toDouble)
          .orElse(json.select("transform_request").asOpt[Double]),
        transformResponse = json
          .select("transform_response")
          .asOpt[Int]
          .map(_.toDouble)
          .orElse(json.select("transform_response").asOpt[Double]),
        matchRoute =
          json.select("match_route").asOpt[Int].map(_.toDouble).orElse(json.select("match_route").asOpt[Double]),
        handlesTunnel =
          json.select("handles_tunnel").asOpt[Int].map(_.toDouble).orElse(json.select("handles_tunnel").asOpt[Double]),
        callBackend =
          json.select("call_backend").asOpt[Int].map(_.toDouble).orElse(json.select("call_backend").asOpt[Double])
      )
    } match {
      case Failure(e) => JsError(e.getMessage())
      case Success(v) => JsSuccess(v)
    }
    override def writes(o: PluginIndex): JsValue             = Json
      .obj()
      .applyOnWithOpt(o.sink)((o, v) => o ++ Json.obj("sink" -> JsNumber(v)))
      .applyOnWithOpt(o.preRoute)((o, v) => o ++ Json.obj("pre_route" -> JsNumber(v)))
      .applyOnWithOpt(o.validateAccess)((o, v) => o ++ Json.obj("validate_access" -> JsNumber(v)))
      .applyOnWithOpt(o.transformRequest)((o, v) => o ++ Json.obj("transform_request" -> JsNumber(v)))
      .applyOnWithOpt(o.transformResponse)((o, v) => o ++ Json.obj("transform_response" -> JsNumber(v)))
      .applyOnWithOpt(o.matchRoute)((o, v) => o ++ Json.obj("match_route" -> JsNumber(v)))
      .applyOnWithOpt(o.handlesTunnel)((o, v) => o ++ Json.obj("handles_tunnel" -> JsNumber(v)))
  }
}

object NgPluginInstance {
  def readFrom(obj: JsValue): NgPluginInstance = {
    NgPluginInstance(
      plugin = obj.select("plugin").asString,
      debug = obj.select("debug").asOpt[Boolean].getOrElse(false),
      enabled = obj.select("enabled").asOpt[Boolean].getOrElse(true),
      include = obj.select("include").asOpt[Seq[String]].getOrElse(Seq.empty),
      exclude = obj.select("exclude").asOpt[Seq[String]].getOrElse(Seq.empty),
      config = NgPluginInstanceConfig(obj.select("config").asOpt[JsObject].getOrElse(Json.obj())),
      pluginIndex = obj.select("plugin_index").asOpt(PluginIndex.format)
    )
  }
}

case class NgPluginInstance(
    plugin: String,
    enabled: Boolean = true,
    debug: Boolean = false,
    include: Seq[String] = Seq.empty,
    exclude: Seq[String] = Seq.empty,
    config: NgPluginInstanceConfig = NgPluginInstanceConfig(),
    pluginIndex: Option[PluginIndex] = None
) {
  def json: JsValue = Json
    .obj(
      "enabled" -> enabled,
      "debug"   -> debug,
      "plugin"  -> plugin,
      "include" -> include,
      "exclude" -> exclude,
      "config"  -> config.json
    )
    .applyOnWithOpt(pluginIndex)((o, v) => o ++ Json.obj("plugin_index" -> v.json))
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

  def hasPlugin[A](implicit ct: ClassTag[A]): Boolean = getPluginByClass[A](ct).isDefined

  def getPluginByClass[A](implicit ct: ClassTag[A]): Option[NgPluginInstance] = {
    val name = s"cp:${ct.runtimeClass.getName}"
    slots.find(pi => pi.plugin == name).filter(_.enabled)
  }

  def routerPlugins(
    request: RequestHeader
  )(implicit ec: ExecutionContext, env: Env): Seq[NgPluginWrapper[NgRouter]] = {
    val pls = slots
      .filter(_.enabled)
      .filter(_.matches(request))
      .map(inst => (inst, inst.getPlugin[NgRouter]))
      .collect { case (inst, Some(plugin)) =>
        NgPluginWrapper.NgSimplePluginWrapper(inst, plugin)
      }
    val (plsWithIndex, plsWithoutIndex) = pls.partition(_.instance.pluginIndex.exists(_.sink.isDefined))
    plsWithIndex.sortWith((a, b) =>
      a.instance.pluginIndex.get.sink.get.compareTo(b.instance.pluginIndex.get.sink.get) < 0
    ) ++ plsWithoutIndex
  }

  def requestSinkPlugins(
      request: RequestHeader
  )(implicit ec: ExecutionContext, env: Env): Seq[NgPluginWrapper[NgRequestSink]] = {
    val pls                             = slots
      .filter(_.enabled)
      .filter(_.matches(request))
      .map(inst => (inst, inst.getPlugin[NgRequestSink]))
      .collect { case (inst, Some(plugin)) =>
        NgPluginWrapper.NgSimplePluginWrapper(inst, plugin)
      }
    val (plsWithIndex, plsWithoutIndex) = pls.partition(_.instance.pluginIndex.exists(_.sink.isDefined))
    plsWithIndex.sortWith((a, b) =>
      a.instance.pluginIndex.get.sink.get.compareTo(b.instance.pluginIndex.get.sink.get) < 0
    ) ++ plsWithoutIndex
  }

  def routeMatcherPlugins(
      request: RequestHeader
  )(implicit ec: ExecutionContext, env: Env): Seq[NgPluginWrapper[NgRouteMatcher]] = {
    val pls                             = slots
      .filter(_.enabled)
      .filter(_.matches(request))
      .map(inst => (inst, inst.getPlugin[NgRouteMatcher]))
      .collect { case (inst, Some(plugin)) =>
        NgPluginWrapper.NgSimplePluginWrapper(inst, plugin)
      }
    val (plsWithIndex, plsWithoutIndex) = pls.partition(_.instance.pluginIndex.exists(_.matchRoute.isDefined))
    plsWithIndex.sortWith((a, b) =>
      a.instance.pluginIndex.get.matchRoute.get.compareTo(b.instance.pluginIndex.get.matchRoute.get) < 0
    ) ++ plsWithoutIndex
  }

  def transformerPlugins(
      request: RequestHeader
  )(implicit ec: ExecutionContext, env: Env): Seq[NgPluginWrapper[NgRequestTransformer]] = {
    val pls                                = slots
      .filter(_.enabled)
      .filter(_.matches(request))
      .map(inst => (inst, inst.getPlugin[NgRequestTransformer]))
      .collect { case (inst, Some(plugin)) =>
        NgPluginWrapper.NgSimplePluginWrapper(inst, plugin)
      }
    val (plsWithIndexAll, plsWithoutIndex) =
      pls.partition(_.instance.pluginIndex.exists(p => p.transformRequest.isDefined || p.transformResponse.isDefined))
    val plsReq                             = plsWithIndexAll
      .filter(v => v.instance.pluginIndex.exists(_.transformRequest.isDefined) && v.plugin.transformsRequest)
      .sortWith((a, b) =>
        a.instance.pluginIndex.get.transformRequest.get.compareTo(b.instance.pluginIndex.get.transformRequest.get) < 0
      )
    val plsResp                            = plsWithIndexAll
      .filter(v => v.instance.pluginIndex.exists(_.transformResponse.isDefined) && v.plugin.transformsResponse)
      .sortWith((a, b) =>
        a.instance.pluginIndex.get.transformResponse.get.compareTo(b.instance.pluginIndex.get.transformResponse.get) < 0
      )
    val plsErr                             = plsWithIndexAll
      .filter(v => v.instance.pluginIndex.exists(_.transformResponse.isDefined) && v.plugin.transformsError)
      .sortWith((a, b) =>
        a.instance.pluginIndex.get.transformResponse.get.compareTo(b.instance.pluginIndex.get.transformResponse.get) < 0
      )
    plsReq ++ plsResp ++ plsErr ++ plsWithoutIndex
  }

  def transformerPluginsThatTransformsError(
      request: RequestHeader
  )(implicit ec: ExecutionContext, env: Env): Seq[NgPluginWrapper[NgRequestTransformer]] = {
    transformerPlugins(request).filter(_.plugin.transformsError)
  }

  // def allPlugins()(implicit ec: ExecutionContext, env: Env): Seq[NgPluginWrapper[NgNamedPlugin]] = {
  //   slots
  //     .map(inst => (inst, inst.getPlugin[NgNamedPlugin]))
  //     .collect { case (inst, Some(plugin)) =>
  //       NgPluginWrapper.NgSimplePluginWrapper(inst, plugin)
  //     }
  // }
  // def transformerPluginsWithCallbacks(
  //     request: RequestHeader
  // )(implicit ec: ExecutionContext, env: Env): Seq[NgPluginWrapper[NgRequestTransformer]] = {
  //   transformerPlugins(request).filter(_.plugin.usesCallbacks)
  // }
  // def transformerPluginsThatTransformsRequest(
  //     request: RequestHeader
  // )(implicit ec: ExecutionContext, env: Env): Seq[NgPluginWrapper[NgRequestTransformer]] = {
  //   transformerPlugins(request).filter(_.plugin.transformsRequest)
  // }
  // def transformerPluginsThatTransformsResponse(
  //     request: RequestHeader
  // )(implicit ec: ExecutionContext, env: Env): Seq[NgPluginWrapper[NgRequestTransformer]] = {
  //   transformerPlugins(request).filter(_.plugin.transformsResponse)
  // }
  // def preRoutePlugins(
  //     request: RequestHeader
  // )(implicit ec: ExecutionContext, env: Env): Seq[NgPluginWrapper[NgPreRouting]] = {
  //   slots
  //     .filter(_.enabled)
  //     .filter(_.matches(request))
  //     .map(inst => (inst, inst.getPlugin[NgPreRouting]))
  //     .collect { case (inst, Some(plugin)) =>
  //       NgPluginWrapper.NgSimplePluginWrapper(inst, plugin)
  //     }
  // }
  // def accessValidatorPlugins(
  //     request: RequestHeader
  // )(implicit ec: ExecutionContext, env: Env): Seq[NgPluginWrapper[NgAccessValidator]] = {
  //   slots
  //     .filter(_.enabled)
  //     .filter(_.matches(request))
  //     .map(inst => (inst, inst.getPlugin[NgAccessValidator]))
  //     .collect { case (inst, Some(plugin)) =>
  //       NgPluginWrapper.NgSimplePluginWrapper(inst, plugin)
  //     }
  // }
  // def tunnelHandlerPlugins(
  //     request: RequestHeader
  // )(implicit ec: ExecutionContext, env: Env): Seq[NgPluginWrapper[NgTunnelHandler]] = {
  //   slots
  //     .filter(_.enabled)
  //     .filter(_.matches(request))
  //     .map(inst => (inst, inst.getPlugin[NgTunnelHandler]))
  //     .collect { case (inst, Some(plugin)) =>
  //       NgPluginWrapper.NgSimplePluginWrapper(inst, plugin)
  //     }
  // }
}

object NgPlugins {
  def empty: NgPlugins = NgPlugins(Seq.empty)
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
    .filterNot(_.plugin.endsWith(classOf[WasmJob].getName))
    .partition(_.matches(request))

  lazy val requestSinkPlugins = {
    val pls                             = allPlugins
      .map(inst => (inst, inst.getPlugin[NgRequestSink]))
      .collect { case (inst, Some(plugin)) =>
        NgPluginWrapper.NgSimplePluginWrapper(inst, plugin)
      }
    val (plsWithIndex, plsWithoutIndex) = pls.partition(_.instance.pluginIndex.exists(_.sink.isDefined))
    plsWithIndex.sortWith((a, b) =>
      a.instance.pluginIndex.get.sink.get.compareTo(b.instance.pluginIndex.get.sink.get) < 0
    ) ++ plsWithoutIndex
  }

  lazy val transformerPlugins = {
    val pls                                = allPlugins
      .map(inst => (inst, inst.getPlugin[NgRequestTransformer]))
      .collect { case (inst, Some(plugin)) =>
        NgPluginWrapper.NgSimplePluginWrapper(inst, plugin)
      }
    val (plsWithIndexAll, plsWithoutIndex) =
      pls.partition(_.instance.pluginIndex.exists(p => p.transformRequest.isDefined || p.transformResponse.isDefined))
    val plsReq                             = plsWithIndexAll
      .filter(v => v.instance.pluginIndex.exists(_.transformRequest.isDefined) && v.plugin.transformsRequest)
      .sortWith((a, b) =>
        a.instance.pluginIndex.get.transformRequest.get.compareTo(b.instance.pluginIndex.get.transformRequest.get) < 0
      )
    val plsResp                            = plsWithIndexAll
      .filter(v => v.instance.pluginIndex.exists(_.transformResponse.isDefined) && v.plugin.transformsResponse)
      .sortWith((a, b) =>
        a.instance.pluginIndex.get.transformResponse.get.compareTo(b.instance.pluginIndex.get.transformResponse.get) < 0
      )
    val plsErr                             = plsWithIndexAll
      .filter(v => v.instance.pluginIndex.exists(_.transformResponse.isDefined) && v.plugin.transformsError)
      .sortWith((a, b) =>
        a.instance.pluginIndex.get.transformResponse.get.compareTo(b.instance.pluginIndex.get.transformResponse.get) < 0
      )
    plsReq ++ plsResp ++ plsErr ++ plsWithoutIndex
  }

  lazy val (transformerPluginsWithCallbacks, tpwoCallbacks)    = transformerPlugins.partition(_.plugin.usesCallbacks)
  lazy val (transformerPluginsThatTransformsRequest, tpwoRequest) = {
    val (plugs, b) = transformerPlugins.partition(_.plugin.transformsRequest)
    if (nextPluginsMerge && plugs.size > 1) {
      val new_plugins = plugs
        .foldLeft((true, Seq.empty[NgPluginWrapper[NgRequestTransformer]])) {
          case ((latestAsync, coll), plug) => {
            // if (plug.plugin.isTransformRequestAsync) {
            (true, coll :+ plug)
            // } else {
            //   if (!latestAsync) {
            //     coll.last match {
            //       case wrap @ NgPluginWrapper.NgSimplePluginWrapper(_, _)               =>
            //         (false, coll.init :+ NgPluginWrapper.NgMergedRequestTransformerPluginWrapper(Seq(wrap, plug)))
            //       case NgPluginWrapper.NgMergedRequestTransformerPluginWrapper(plugins) =>
            //         (false, coll.init :+ NgPluginWrapper.NgMergedRequestTransformerPluginWrapper(plugins :+ plug))
            //       case _                                                                => (true, coll :+ plug)
            //     }
            //   } else {
            //     (false, coll :+ plug)
            //   }
            // }
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
            // if (plug.plugin.isTransformResponseAsync) {
            (true, coll :+ plug)
            // } else {
            //   if (!latestAsync) {
            //     coll.last match {
            //       case wrap @ NgPluginWrapper.NgSimplePluginWrapper(_, _)               =>
            //         (false, coll.init :+ NgPluginWrapper.NgMergedRequestTransformerPluginWrapper(Seq(wrap, plug)))
            //       case NgPluginWrapper.NgMergedRequestTransformerPluginWrapper(plugins) =>
            //         (false, coll.init :+ NgPluginWrapper.NgMergedRequestTransformerPluginWrapper(plugins :+ plug))
            //       case _                                                                => (true, coll :+ plug)
            //     }
            //   } else {
            //     (false, coll :+ plug)
            //   }
            // }
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
    val plugs = {
      val pls                             = allPlugins
        .map(inst => (inst, inst.getPlugin[NgPreRouting]))
        .collect { case (inst, Some(plugin)) =>
          NgPluginWrapper.NgSimplePluginWrapper(inst, plugin)
        }
      val (plsWithIndex, plsWithoutIndex) = pls.partition(_.instance.pluginIndex.exists(_.preRoute.isDefined))
      plsWithIndex.sortWith((a, b) =>
        a.instance.pluginIndex.get.preRoute.get.compareTo(b.instance.pluginIndex.get.preRoute.get) < 0
      ) ++ plsWithoutIndex
    }
    if (nextPluginsMerge && plugs.size > 1) {
      val new_plugins = plugs
        .foldLeft((true, Seq.empty[NgPluginWrapper[NgPreRouting]])) {
          case ((latestAsync, coll), plug) => {
            //  if (plug.plugin.isPreRouteAsync) {
            (true, coll :+ plug)
            //   } else {
            //     if (!latestAsync) {
            //       coll.last match {
            //         case wrap @ NgPluginWrapper.NgSimplePluginWrapper(_, _)       =>
            //           (false, coll.init :+ NgPluginWrapper.NgMergedPreRoutingPluginWrapper(Seq(wrap, plug)))
            //         case NgPluginWrapper.NgMergedPreRoutingPluginWrapper(plugins) =>
            //           (false, coll.init :+ NgPluginWrapper.NgMergedPreRoutingPluginWrapper(plugins :+ plug))
            //         case _                                                        => (true, coll :+ plug)
            //       }
            //     } else {
            //       (false, coll :+ plug)
            //     }
            //   }
          }
        }
        ._2
      new_plugins
    } else {
      plugs
    }
  }

  lazy val accessValidatorPlugins = {
    val plugs = {
      val pls                             = allPlugins
        .map(inst => (inst, inst.getPlugin[NgAccessValidator]))
        .collect { case (inst, Some(plugin)) =>
          NgPluginWrapper.NgSimplePluginWrapper(inst, plugin)
        }
      val (plsWithIndex, plsWithoutIndex) = pls.partition(_.instance.pluginIndex.exists(_.validateAccess.isDefined))
      plsWithIndex.sortWith((a, b) =>
        a.instance.pluginIndex.get.validateAccess.get.compareTo(b.instance.pluginIndex.get.validateAccess.get) < 0
      ) ++ plsWithoutIndex
    }
    if (nextPluginsMerge && plugs.size > 1) {
      val new_plugins = plugs
        .foldLeft((true, Seq.empty[NgPluginWrapper[NgAccessValidator]])) {
          case ((latestAsync, coll), plug) => {
            // if (plug.plugin.isAccessAsync) {
            (true, coll :+ plug)
            // } else {
            //   if (!latestAsync) {
            //     coll.last match {
            //       case wrap @ NgPluginWrapper.NgSimplePluginWrapper(_, _)            =>
            //         (false, coll.init :+ NgPluginWrapper.NgMergedAccessValidatorPluginWrapper(Seq(wrap, plug)))
            //       case NgPluginWrapper.NgMergedAccessValidatorPluginWrapper(plugins) =>
            //         (false, coll.init :+ NgPluginWrapper.NgMergedAccessValidatorPluginWrapper(plugins :+ plug))
            //       case _                                                             => (true, coll :+ plug)
            //     }
            //   } else {
            //     (false, coll :+ plug)
            //   }
            // }
          }
        }
        ._2
      new_plugins
    } else {
      plugs
    }
  }

  lazy val routeMatcherPlugins = {
    val pls                             = allPlugins
      .map(inst => (inst, inst.getPlugin[NgRouteMatcher]))
      .collect { case (inst, Some(plugin)) =>
        NgPluginWrapper.NgSimplePluginWrapper(inst, plugin)
      }
    val (plsWithIndex, plsWithoutIndex) = pls.partition(_.instance.pluginIndex.exists(_.matchRoute.isDefined))
    plsWithIndex.sortWith((a, b) =>
      a.instance.pluginIndex.get.matchRoute.get.compareTo(b.instance.pluginIndex.get.matchRoute.get) < 0
    ) ++ plsWithoutIndex
  }

  lazy val tunnelHandlerPlugins = {
    val pls                             = allPlugins
      .map(inst => (inst, inst.getPlugin[NgTunnelHandler]))
      .collect { case (inst, Some(plugin)) =>
        NgPluginWrapper.NgSimplePluginWrapper(inst, plugin)
      }
    val (plsWithIndex, plsWithoutIndex) = pls.partition(_.instance.pluginIndex.exists(_.handlesTunnel.isDefined))
    plsWithIndex.sortWith((a, b) =>
      a.instance.pluginIndex.get.handlesTunnel.get.compareTo(b.instance.pluginIndex.get.handlesTunnel.get) < 0
    ) ++ plsWithoutIndex
  }

  lazy val hasTunnelHandlerPlugin    = tunnelHandlerPlugins.nonEmpty
  lazy val tunnelHandlerPlugin       = tunnelHandlerPlugins.head
  lazy val tunnelHandlerPluginOption = tunnelHandlerPlugins.headOption

  lazy val backendCallPlugins = {
    val pls                             = allPlugins
      .map(inst => (inst, inst.getPlugin[NgBackendCall]))
      .collect { case (inst, Some(plugin)) =>
        NgPluginWrapper.NgSimplePluginWrapper(inst, plugin)
      }
    val (plsWithIndex, plsWithoutIndex) = pls.partition(_.instance.pluginIndex.exists(_.callBackend.isDefined))
    plsWithIndex.sortWith((a, b) =>
      a.instance.pluginIndex.get.callBackend.get.compareTo(b.instance.pluginIndex.get.callBackend.get) < 0
    ) ++ plsWithoutIndex
  }

  lazy val hasBackendCallPlugin    = backendCallPlugins.nonEmpty
  lazy val backendCallPlugin       = backendCallPlugins.head
  lazy val backendCallPluginOption = backendCallPlugins.headOption
}
