package otoroshi.next.analytics.controllers

import otoroshi.actions.{ApiAction, ApiActionContext}
import otoroshi.env.Env
import otoroshi.next.analytics.models.{UserDashboard, UserDashboardDataStore}
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

import scala.concurrent.Future

class UserDashboardController(ApiAction: ApiAction, cc: ControllerComponents)(implicit env: Env)
    extends AbstractController(cc) {

  implicit lazy val ec = env.otoroshiExecutionContext

  private val logger = Logger("otoroshi-user-dashboard-api")

  private def requireSuperAdmin(ctx: ApiActionContext[_])(f: => Future[play.api.mvc.Result]): Future[play.api.mvc.Result] = {
    if (ctx.userIsSuperAdmin) f
    else Forbidden(Json.obj("error" -> "super admin only")).future
  }

  // ----- POST /api/analytics/dashboards/_restore-defaults (deferred to E) ---

  def restoreDefaults: Action[AnyContent] = ApiAction.async { ctx =>
    requireSuperAdmin(ctx) {
      NotImplemented(Json.obj("error" -> "default dashboards not yet implemented")).future
    }
  }
}
