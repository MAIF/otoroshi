package controllers.adminapi

import otoroshi.actions.ApiAction
import otoroshi.env.Env
import otoroshi.script.Script
import otoroshi.utils.controllers.{BulkControllerHelper, CrudControllerHelper}
import play.api.Logger
import play.api.libs.json.JsValue
import play.api.mvc.{AbstractController, ControllerComponents}

class InfosController(val ApiAction: ApiAction, val cc: ControllerComponents)(implicit val env: Env)
  extends AbstractController(cc) {

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  val logger = Logger("otoroshi-infos-api")

}
