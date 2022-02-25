package otoroshi.next.controllers

import otoroshi.actions.ApiAction
import otoroshi.env.Env
import otoroshi.next.plugins.api.{NgNamedPlugin, NgPluginCategory, NgPluginVisibility, NgStep}
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

  def plugins() = ApiAction {
    val plugins = env.scriptManager.ngNames.filterNot(_.contains(".NgMerged")).flatMap(name => env.scriptManager.getAnyScript[NgNamedPlugin](s"cp:$name").toOption.map(o => (name, o)))
    Ok(JsArray(plugins.filter(_._2.visibility == NgPluginVisibility.NgUserLand).map {
      case (name, plugin) =>
        Json.obj(
          "id"            -> name,
          "name"              -> plugin.name,
          "description"       -> plugin.description
            .map(_.trim)
            .filter(_.nonEmpty)
            .map(JsString.apply)
            .getOrElse(JsNull)
            .as[JsValue],
          "default_config"    -> plugin.defaultConfig.getOrElse(JsNull).as[JsValue],
          "config_schema"     -> plugin.configSchema.getOrElse(JsNull).as[JsValue],
          "config_flow"       -> JsArray(plugin.configFlow.map(JsString.apply)),
          "plugin_type"       -> "ng",
          "plugin_visibility" -> plugin.visibility.json,
          "plugin_categories" -> JsArray(plugin.categories.map(_.json)),
          "plugin_steps"      -> JsArray(plugin.steps.map(_.json)),
          "plugin_tags"       -> JsArray(plugin.tags.map(JsString.apply)),
        )
    }))
  }
}