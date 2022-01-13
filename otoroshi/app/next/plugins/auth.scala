package otoroshi.next.plugins

import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.models.PrivateAppsUserHelper
import otoroshi.next.plugins.api._
import otoroshi.utils.http.RequestImplicits._
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}

class AuthModule extends NgAccessValidator {
  // TODO: add name and config
  val logger = Logger("otoroshi-next-plugins-auth-module")

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val req = ctx.request
    val descriptor = ctx.route.serviceDescriptor
    ctx.attrs.get(otoroshi.plugins.Keys.UserKey) match {
      case Some(_) => NgAccess.NgAllowed.future
      case None => {
        ctx.config.select("auth_module").asOpt[String] match {
          case None =>
            Errors
              .craftResponseResult(
                "Auth. config. ref not found on the descriptor",
                Results.InternalServerError,
                ctx.request,
                ctx.route.serviceDescriptor.some,
                "errors.auth.config.ref.not.found".some,
                attrs = ctx.attrs
              )
              .map(NgAccess.NgDenied.apply)
          case Some(ref) => {
            // env.datastores.authConfigsDataStore.findById(ref).flatMap {
            env.proxyState.authModule(ref) match {
              case None       =>
                Errors
                  .craftResponseResult(
                    "Auth. config. not found on the descriptor",
                    Results.InternalServerError,
                    ctx.request,
                    ctx.route.serviceDescriptor.some,
                    "errors.auth.config.not.found".some,
                    attrs = ctx.attrs
                  )
                  .map(NgAccess.NgDenied.apply)
              case Some(auth) => {
                // here there is a datastore access (by key) to get the user session
                PrivateAppsUserHelper.isPrivateAppsSessionValidWithAuth(ctx.request, descriptor, auth).flatMap {
                  case Some(paUsr) =>
                    ctx.attrs.put(otoroshi.plugins.Keys.UserKey -> paUsr)
                    NgAccess.NgAllowed.future
                  case None => {
                    val redirect = req
                      .getQueryString("redirect")
                      .getOrElse(s"${req.theProtocol}://${req.theHost}${req.relativeUri}")
                    val redirectTo =
                      env.rootScheme + env.privateAppsHost + env.privateAppsPort + otoroshi.controllers.routes.AuthController
                        .confidentialAppLoginPage()
                        .url + s"?desc=${descriptor.id}&redirect=${redirect}"
                    logger.trace("should redirect to " + redirectTo)
                    NgAccess.NgDenied(Results
                      .Redirect(redirectTo)
                      .discardingCookies(
                        env.removePrivateSessionCookies(
                          req.theDomain,
                          descriptor,
                          auth
                        ): _*
                      )).future
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}
