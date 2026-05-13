package otoroshi.controllers.adminapi

import org.apache.pekko.stream.Materializer
import org.apache.pekko.util.ByteString
import otoroshi.actions.{ApiAction, UnAuthApiAction}
import otoroshi.env.Env
import otoroshi.models.RightsChecker
import otoroshi.utils.controllers.{AdminApiHelper, JsonApiError, SendAuditAndAlert}
import otoroshi.utils.syntax.implicits.given
import play.api.libs.json.Json
import play.api.{Logger, mvc}
import play.api.mvc.{AbstractController, AnyContent, ControllerComponents}

import scala.concurrent.ExecutionContext

class EventsController(ApiAction: ApiAction, cc: ControllerComponents)(using env: Env)
    extends AbstractController(cc)
    with AdminApiHelper {

  implicit lazy val ec: ExecutionContext = env.otoroshiExecutionContext
  implicit lazy val mat: Materializer    = env.otoroshiMaterializer

  lazy val logger: Logger = Logger("otoroshi-events-api")

  def auditEvents(): mvc.Action[AnyContent] =
    ApiAction.async { ctx =>
      ctx.checkRights(RightsChecker.SuperAdminOnly) {
        val options = SendAuditAndAlert("ACCESS_AUDIT_EVENTS", s"User accessed audit events", None, Json.obj(), ctx)
        fetchWithPaginationAndFilteringAsResult(
          ctx,
          "filter.".some,
          (e: ByteString) => Json.parse(e.utf8String),
          options
        ) {
          env.datastores.auditDataStore.findAllRaw().fright[JsonApiError]
        }
      }
    }

  def alertEvents(): mvc.Action[AnyContent] =
    ApiAction.async { ctx =>
      ctx.checkRights(RightsChecker.SuperAdminOnly) {
        val options = SendAuditAndAlert("ACCESS_ALERT_EVENTS", s"User accessed alert events", None, Json.obj(), ctx)
        fetchWithPaginationAndFilteringAsResult(
          ctx,
          "filter.".some,
          (e: ByteString) => Json.parse(e.utf8String),
          options
        ) {
          env.datastores.alertDataStore.findAllRaw().fright[JsonApiError]
        }
      }
    }
}
