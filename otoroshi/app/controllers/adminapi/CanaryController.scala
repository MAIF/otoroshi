package controllers.adminapi

import actions.ApiAction
import env.Env
import play.api.Logger
import play.api.libs.json.Json
import play.api.mvc.{AbstractController, ControllerComponents}
import otoroshi.utils.syntax.implicits._

class CanaryController(ApiAction: ApiAction, cc: ControllerComponents)(implicit env: Env)
  extends AbstractController(cc) {

  implicit lazy val ec = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-canary-api")

  def serviceCanaryMembers(serviceId: String) = ApiAction.async { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> "service not found")).future
      case Some(service) if !ctx.canUserRead(service) => Unauthorized(Json.obj("error" -> "service not accessible")).future
      case Some(service) => env.datastores.canaryDataStore.canaryCampaign(serviceId).map { campaign =>
        Ok(
          Json.obj(
            "canaryUsers"   -> campaign.canaryUsers,
            "standardUsers" -> campaign.standardUsers
          )
        )
      }
    }
  }

  def resetServiceCanaryMembers(serviceId: String) = ApiAction.async { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> "service not found")).future
      case Some(service) if !ctx.canUserWrite(service) => Unauthorized(Json.obj("error" -> "service not accessible")).future
      case Some(service) => env.datastores.canaryDataStore.destroyCanarySession(serviceId).map { done =>
        Ok(Json.obj("done" -> done))
      }
    }
  }
}