package otoroshi.controllers.adminapi

import org.apache.pekko.stream.Materializer
import otoroshi.actions.ApiAction
import otoroshi.env.Env
import play.api.Logger
import play.api.libs.json.Json
import play.api.mvc.{AbstractController, ControllerComponents}

import scala.concurrent.ExecutionContext
import play.api.mvc
import play.api.mvc.AnyContent

class CanaryController(ApiAction: ApiAction, cc: ControllerComponents)(using env: Env)
    extends AbstractController(cc) {

  implicit lazy val ec: ExecutionContext = env.otoroshiExecutionContext
  implicit lazy val mat: Materializer    = env.otoroshiMaterializer

  lazy val logger: Logger = Logger("otoroshi-canary-api")

  def serviceCanaryMembers(serviceId: String): mvc.Action[AnyContent] =
    ApiAction.async { ctx =>
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

  def resetServiceCanaryMembers(serviceId: String): mvc.Action[AnyContent] =
    ApiAction.async { ctx =>
      ctx.canWriteService(serviceId) {
        env.datastores.canaryDataStore.destroyCanarySession(serviceId).map { done =>
          Ok(Json.obj("done" -> done))
        }
      }
    }
}
