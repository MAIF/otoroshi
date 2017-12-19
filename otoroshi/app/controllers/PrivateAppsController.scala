package controllers

import actions.PrivateAppsAction
import env.Env
import play.api.mvc.Controller

class PrivateAppsController(env: Env, PrivateAppsAction: PrivateAppsAction) extends Controller {

  implicit lazy val ec = env.privateAppsExecutionContext

  def home = PrivateAppsAction { ctx =>
    Ok(views.html.privateapps.home(ctx.user, env))
  }

  def redirect = PrivateAppsAction { ctx =>
    implicit val request = ctx.request
    Redirect(
      request.session
        .get("pa-redirect-after-login")
        .getOrElse(routes.PrivateAppsController.home().absoluteURL(env.isProd && env.exposedRootSchemeIsHttps))
    ).removingFromSession("pa-redirect-after-login")
  }

  def error(message: Option[String] = None) = PrivateAppsAction { ctx =>
    Ok(views.html.opunapps.error(message.getOrElse(""), env))
  }
}
