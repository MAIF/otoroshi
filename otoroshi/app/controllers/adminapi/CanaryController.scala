package otoroshi.controllers.adminapi

import otoroshi.actions.ApiAction
import otoroshi.env.Env
import play.api.Logger
import play.api.libs.json.Json
import play.api.mvc.{AbstractController, ControllerComponents}

class CanaryController(ApiAction: ApiAction, cc: ControllerComponents)(implicit env: Env)
  extends AbstractController(cc) {

  implicit lazy val ec = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-canary-api")

  def serviceCanaryMembers(serviceId: String) = ApiAction.async { ctx =>
    ctx.canReadService(serviceId) {
      env.datastores.canaryDataStore.canaryCampaign(serviceId).map { campaign =>
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
    ctx.canWriteService(serviceId) {
      env.datastores.canaryDataStore.destroyCanarySession(serviceId).map { done =>
        Ok(Json.obj("done" -> done))
      }
    }
  }
}