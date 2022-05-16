package otoroshi.next.controllers

import otoroshi.actions.ApiAction
import otoroshi.env.Env
import otoroshi.next.plugins.api.{
  NgAccessValidator,
  NgBackendCall,
  NgNamedPlugin,
  NgPluginCategory,
  NgPluginVisibility,
  NgPreRouting,
  NgRequestSink,
  NgRequestSinkContext,
  NgRequestTransformer,
  NgRouteMatcher,
  NgStep,
  NgTunnelHandler
}
import play.api.libs.json._
import play.api.mvc.{AbstractController, ControllerComponents}

class NgPluginsController(
    ApiAction: ApiAction,
    cc: ControllerComponents
)(implicit
    env: Env
) extends AbstractController(cc) {

  implicit val ec = env.otoroshiExecutionContext

  def categories() = ApiAction {
    Ok(JsArray(NgPluginCategory.all.map(_.json)))
  }

  def steps() = ApiAction {
    Ok(JsArray(NgStep.all.map(_.json)))
  }

  def form() = ApiAction { ctx =>
    (for {
      name <- ctx.request.getQueryString("name")
      form <- env.openApiSchema.asForms.get(name)
    } yield {
      Ok(form.json)
    }) getOrElse {
      NotFound(Json.obj("error" -> s"form not found"))
    }
  }

  def forms() = ApiAction { ctx =>
    val forms = new JsObject(env.openApiSchema.asForms.mapValues(_.json))
    Ok(forms)
  }

  def plugins() = ApiAction {
    val plugins = env.scriptManager.ngNames.distinct
      .filterNot(_.contains(".NgMerged"))
      .flatMap(name => env.scriptManager.getAnyScript[NgNamedPlugin](s"cp:$name").toOption.map(o => (name, o)))

    Ok(JsArray(plugins.filter(_._2.visibility == NgPluginVisibility.NgUserLand).map { case (name, plugin) =>
      val onRequest  = plugin match {
        case a: NgRequestTransformer => a.transformsRequest
        case _: NgPreRouting         => true
        case _: NgAccessValidator    => true
        case _: NgRequestSink        => true
        case _: NgRouteMatcher       => true
        case _: NgTunnelHandler      => true
        case _                       => false
      }
      val onResponse = plugin match {
        case a: NgRequestTransformer => a.transformsResponse || a.transformsError
        case _: NgPreRouting         => false
        case _: NgAccessValidator    => false
        case _: NgRequestSink        => false
        case _: NgRouteMatcher       => false
        case _: NgTunnelHandler      => false
        case _                       => false
      }

      val form                      = env.openApiSchema.asForms.get(name)
      val pluginSchema: JsObject    = form.map(_.schema).getOrElse(Json.obj())
      val overridedSchema: JsObject = plugin.configSchema.getOrElse(Json.obj()).as[JsObject]

      Json.obj(
        "id"                            -> s"cp:$name",
        "name"                          -> plugin.name,
        "description"                   -> plugin.description
          .map(_.trim)
          .filter(_.nonEmpty)
          .map(JsString.apply)
          .getOrElse(JsNull)
          .as[JsValue],
        "default_config"                -> plugin.defaultConfig.getOrElse(JsNull).as[JsValue],
        "config_schema"                 -> pluginSchema.deepMerge(overridedSchema),
        "config_flow"                   -> JsArray((plugin.configFlow ++ form.map(_.flow).getOrElse(Set.empty)).map(JsString.apply)),
        "plugin_type"                   -> "ng",
        "plugin_visibility"             -> plugin.visibility.json,
        "plugin_categories"             -> JsArray(plugin.categories.map(_.json)),
        "plugin_steps"                  -> JsArray(plugin.steps.map(_.json)),
        "plugin_tags"                   -> JsArray(plugin.tags.map(JsString.apply)),
        "plugin_multi_inst"             -> plugin.multiInstance,
        "plugin_backend_call_delegates" -> (if (plugin.isInstanceOf[NgBackendCall])
                                              plugin.asInstanceOf[NgBackendCall].useDelegates
                                            else false),
        "on_request"                    -> onRequest,
        "on_response"                   -> onResponse
      )
    }))
  }
}
