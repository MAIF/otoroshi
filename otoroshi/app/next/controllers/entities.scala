package otoroshi.next.controllers.adminapi

import otoroshi.actions.ApiAction
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json.Json
import play.api.mvc._

class EntitiesController(ApiAction: ApiAction, cc: ControllerComponents)(implicit env: Env)
    extends AbstractController(cc) {

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-entities-controller");

  def getEntityGraph(entity: String, id: String) =
    ApiAction.async { ctx =>
      entity match {
        case "jwt-verifiers" =>
          Ok(Json.obj(
            "routes" -> env.proxyState
              .allRoutes()
              .filter(route => Json.stringify(Json.arr(route.plugins.slots.map(p => p.config.raw))).contains(id))
              .map(_.json)
          )).as("application/json").vfuture
        case _ =>
          Ok(Json.obj()).as("application/json").vfuture
      }
    }

}
