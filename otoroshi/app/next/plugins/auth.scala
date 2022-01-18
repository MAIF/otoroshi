package otoroshi.next.plugins

import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.models.PrivateAppsUserHelper
import otoroshi.next.plugins.api._
import otoroshi.utils.http.RequestImplicits._
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json.{Format, JsError, JsNull, JsObject, JsResult, JsString, JsSuccess, JsValue, Json, Reads}
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class NgAuthModuleConfig(module: Option[String] = None) {
  def json: JsValue = NgAuthModuleConfig.format.writes(this)
}

object NgAuthModuleConfig {
  val format = new Format[NgAuthModuleConfig] {
    override def reads(json: JsValue): JsResult[NgAuthModuleConfig] = Try {
      NgAuthModuleConfig(
        module = json.select("auth_module").asOpt[String].filter(_.nonEmpty)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: NgAuthModuleConfig): JsValue = Json.obj("auth_module" -> o.module.map(JsString.apply).getOrElse(JsNull).as[JsValue])
  }
}

class AuthModule extends NgAccessValidator {

  private val logger = Logger("otoroshi-next-plugins-auth-module")
  private val configReads: Reads[NgAuthModuleConfig] = NgAuthModuleConfig.format

  override def core: Boolean = true
  override def name: String = "Authentication"
  override def description: Option[String] = "This plugin applies an authentication module".some
  override def defaultConfig: Option[JsObject] = NgAuthModuleConfig().json.asObject.some

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val req = ctx.request
    val descriptor = ctx.route.serviceDescriptor
    ctx.attrs.get(otoroshi.plugins.Keys.UserKey) match {
      case Some(_) => NgAccess.NgAllowed.vfuture
      case None => {
        val NgAuthModuleConfig(module) = ctx.cachedConfig(internalName)(configReads).getOrElse(NgAuthModuleConfig())
        module match {
          case None =>
            Errors
              .craftResponseResult(
                "Auth. config. ref not found on the descriptor",
                Results.InternalServerError,
                ctx.request,
                None,
                "errors.auth.config.ref.not.found".some,
                attrs = ctx.attrs,
                maybeRoute = ctx.route.some,
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
                    None,
                    "errors.auth.config.not.found".some,
                    attrs = ctx.attrs,
                    maybeRoute = ctx.route.some,
                  )
                  .map(NgAccess.NgDenied.apply)
              case Some(auth) => {
                // here there is a datastore access (by key) to get the user session
                PrivateAppsUserHelper.isPrivateAppsSessionValidWithAuth(ctx.request, descriptor, auth).flatMap {
                  case Some(paUsr) =>
                    ctx.attrs.put(otoroshi.plugins.Keys.UserKey -> paUsr)
                    NgAccess.NgAllowed.vfuture
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
                      )).vfuture
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
