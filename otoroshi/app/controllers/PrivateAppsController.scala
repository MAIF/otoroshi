package controllers

import actions.PrivateAppsAction
import env.Env
import play.api.mvc._

class PrivateAppsController(env: Env, PrivateAppsAction: PrivateAppsAction, cc: ControllerComponents)
    extends AbstractController(cc) {

  implicit lazy val ec = env.otoroshiExecutionContext

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
    Ok(views.html.otoroshi.error(message.getOrElse(""), env))
  }
}
