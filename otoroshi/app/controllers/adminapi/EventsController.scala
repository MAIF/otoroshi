package controllers.adminapi

import otoroshi.actions.{ApiAction, UnAuthApiAction}
import akka.util.ByteString
import env.Env
import otoroshi.models.RightsChecker
import otoroshi.utils.controllers.{AdminApiHelper, JsonApiError, SendAuditAndAlert}
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json.Json
import play.api.mvc.{AbstractController, ControllerComponents}

class EventsController(ApiAction: ApiAction, cc: ControllerComponents)(implicit env: Env)
  extends AbstractController(cc) with AdminApiHelper {

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-events-api")

  def auditEvents() = ApiAction.async { ctx =>
    ctx.checkRights(RightsChecker.SuperAdminOnly) {
      val options = SendAuditAndAlert("ACCESS_AUDIT_EVENTS", s"User accessed audit events", None, Json.obj(), ctx)
      fetchWithPaginationAndFilteringAsResult(ctx, "filter.".some, (e: ByteString) => Json.parse(e.utf8String), options) {
        env.datastores.auditDataStore.findAllRaw().fright[JsonApiError]
      }
    }
  }

  def alertEvents() = ApiAction.async { ctx =>
    ctx.checkRights(RightsChecker.SuperAdminOnly) {
      val options = SendAuditAndAlert("ACCESS_ALERT_EVENTS", s"User accessed alert events", None, Json.obj(), ctx)
      fetchWithPaginationAndFilteringAsResult(ctx, "filter.".some, (e: ByteString) => Json.parse(e.utf8String), options) {
        env.datastores.alertDataStore.findAllRaw().fright[JsonApiError]
      }
    }
  }
}
