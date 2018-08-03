package controllers

import java.util.concurrent.TimeUnit

import actions.{BackOfficeAction, BackOfficeActionAuth, PrivateAppsAction}
import akka.http.scaladsl.util.FastFuture
import env.Env
import events.{AdminFirstLogin, AdminLoggedInAlert, AdminLoggedOutAlert, Alerts}
import models.BackOfficeUser
import play.api.Logger
import play.api.http.MimeTypes
import play.api.libs.json.{JsValue, Json}
import play.api.mvc._
import play.mvc.Http.HeaderNames
import security.{Auth0Config, IdGenerator}

import scala.concurrent.Future
import scala.concurrent.duration.Duration

class Auth0Controller(BackOfficeActionAuth: BackOfficeActionAuth,
                      PrivateAppsAction: PrivateAppsAction,
                      BackOfficeAction: BackOfficeAction,
                      cc: ControllerComponents)(implicit env: Env)
    extends AbstractController(cc) {

  implicit lazy val ec = env.otoroshiExecutionContext

  lazy val logger = Logger("otoroshi-auth0")


  def confidentialAppLoginPage() = PrivateAppsAction.async { ctx =>

    import utils.future.Implicits._

    ctx.request.getQueryString("desc") match {
      case None => NotFound(views.html.otoroshi.error("Service not found", env)).asFuture
      case Some(serviceId) => {
        env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
          case None => NotFound(views.html.otoroshi.error("Service not found", env)).asFuture
          case Some(descriptor) if !descriptor.privateApp => NotFound(views.html.otoroshi.error("Private apps are not configured", env)).asFuture
          case Some(descriptor) if descriptor.privateApp && descriptor.id != env.backOfficeDescriptor.id => {
            descriptor.privateAppSettings.authModule(ctx.globalConfig).loginPage(ctx.request, ctx.globalConfig, descriptor)
          }
          case _ => NotFound(views.html.otoroshi.error("Private apps are not configured", env)).asFuture
        }
      }
    }
  }

  def confidentialAppLogout() = PrivateAppsAction.async { ctx =>

    import utils.future.Implicits._

    ctx.request.getQueryString("desc") match {
      case None => NotFound(views.html.otoroshi.error("Service not found", env)).asFuture
      case Some(serviceId) => {
        env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
          case None => NotFound(views.html.otoroshi.error("Service not found", env)).asFuture
          case Some(descriptor) if !descriptor.privateApp => NotFound(views.html.otoroshi.error("Private apps are not configured", env)).asFuture
          case Some(descriptor) if descriptor.privateApp && descriptor.id != env.backOfficeDescriptor.id => {
            descriptor.privateAppSettings.authModule(ctx.globalConfig).logout(ctx.request, ctx.globalConfig, descriptor).map { _ =>
              implicit val request = ctx.request
              val redirect = ctx.request.getQueryString("redirect")
              ctx.user.foreach(_.delete())
              redirect match {
                case Some(url) =>
                  val host = new java.net.URL(url).getHost
                  Redirect(url)
                    .removingFromSession("pa-redirect-after-login")
                    .discardingCookies(env.removePrivateSessionCookies(host, descriptor): _*)
                case None =>
                  Redirect(routes.PrivateAppsController.home())
                    .removingFromSession("pa-redirect-after-login")
                    .discardingCookies(env.removePrivateSessionCookies(request.host, descriptor): _*)
              }
            }
          }
          case _ => NotFound(views.html.otoroshi.error("Private apps are not configured", env)).asFuture
        }
      }
    }
  }

  def confidentialAppCallback() = PrivateAppsAction.async { ctx =>

    import utils.future.Implicits._

    implicit val req = ctx.request

    ctx.request.getQueryString("desc") match {
      case None => NotFound(views.html.otoroshi.error("Service not found", env)).asFuture
      case Some(serviceId) => {
        env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
          case None => NotFound(views.html.otoroshi.error("Service not found", env)).asFuture
          case Some(descriptor) if !descriptor.privateApp => NotFound(views.html.otoroshi.error("Private apps are not configured", env)).asFuture
          case Some(descriptor) if descriptor.privateApp && descriptor.id != env.backOfficeDescriptor.id => {
            descriptor.privateAppSettings.authModule(ctx.globalConfig).callback(ctx.request, ctx.globalConfig, descriptor).flatMap {
              case Left(error) => {
                BadRequest(
                  views.html.otoroshi
                    .error(message = s"You're not authorized here: ${error}", _env = env, title = "Authorization error")
                ).asFuture
              }
              case Right(user) => {
                user.save(Duration(env.privateAppsSessionExp, TimeUnit.MILLISECONDS))
                  .map { paUser =>
                    val redirectTo = ctx.request.session
                      .get("pa-redirect-after-login")
                      .getOrElse(
                        routes.PrivateAppsController.home().absoluteURL(env.isProd && env.exposedRootSchemeIsHttps)
                      )
                    val url = new java.net.URL(redirectTo)
                    val host = url.getHost
                    val scheme = url.getProtocol
                    val path = url.getPath
                    val query = Option(url.getQuery).map(q => s"?$q").getOrElse(s"")
                    val setCookiesRedirect = url.getPort match {
                      case -1 =>
                        s"$scheme://$host/__otoroshi_private_apps_login?sessionId=${paUser.randomId}&redirectTo=$redirectTo&host=$host&cp=${descriptor.privateAppSettings.cookieSuffix(descriptor)}"
                      case port =>
                        s"$scheme://$host:$port/__otoroshi_private_apps_login?sessionId=${paUser.randomId}&redirectTo=$redirectTo&host=$host&cp=${descriptor.privateAppSettings.cookieSuffix(descriptor)}"
                    }
                    Redirect(setCookiesRedirect)
                      .removingFromSession("pa-redirect-after-login")
                      .withCookies(env.createPrivateSessionCookies(host, paUser.randomId, descriptor): _*)
                  }
              }
            }
          }
          case _ => NotFound(views.html.otoroshi.error("Private apps are not configured", env)).asFuture
        }
      }
    }
  }


  def auth0error(error: Option[String], error_description: Option[String]) = BackOfficeAction { ctx =>
    val errorId = IdGenerator.token(16)
    logger.error(s"[AUTH0 ERROR] error_id: $errorId => ${error.getOrElse("--")} : ${error_description.getOrElse("--")}")
    Redirect(routes.BackOfficeController.error(Some(s"Auth0 error - logged with id: $errorId")))
  }

  def backOfficeLogin(redirect: Option[String]) = BackOfficeAction.async { ctx =>
    implicit val request = ctx.request
    env.datastores.globalConfigDataStore.singleton().map {
      case config if !(config.u2fLoginOnly || config.backofficeAuth0Config.isEmpty) => {
        config.backofficeAuth0Config match {
          case None => Redirect(controllers.routes.BackOfficeController.index())
          case Some(aconf) => {
            if (env.useUniversalLogin) {
              Redirect(
                s"https://${aconf.domain}/authorize?response_type=code&client_id=${aconf.clientId}&scope=openid+profile+email+name&redirect_uri=${aconf.callbackURL}"
              ).addingToSession(
                "bo-redirect-after-login" -> redirect.getOrElse(
                  routes.PrivateAppsController.home().absoluteURL(env.isProd && env.exposedRootSchemeIsHttps)
                )
              )
            } else {
              Ok(views.html.backoffice.login(env, aconf)).addingToSession(
                "bo-redirect-after-login" -> redirect
                  .orElse(ctx.request.session.get("bo-redirect-after-login"))
                  .getOrElse(
                    routes.BackOfficeController.dashboard().absoluteURL(env.isProd && env.exposedRootSchemeIsHttps)
                  )
              )
            }
          }
        }
      }
      case config if (config.u2fLoginOnly || config.backofficeAuth0Config.isEmpty) =>
        Redirect(controllers.routes.BackOfficeController.index())
    }
  }

  def backOfficeLogout(redirect: Option[String]) = BackOfficeActionAuth.async { ctx =>
    implicit val request = ctx.request
    ctx.user.delete().map { _ =>
      Alerts.send(AdminLoggedOutAlert(env.snowflakeGenerator.nextIdStr(), env.env, ctx.user))
      redirect match {
        case Some(url) => Redirect(url).removingFromSession("bousr", "bo-redirect-after-login")
        case None =>
          Redirect(routes.BackOfficeController.index()).removingFromSession("bousr", "bo-redirect-after-login")
      }
    }
  }

  def backOfficeCallback(codeOpt: Option[String] = None,
                         error: Option[String] = None,
                         error_description: Option[String] = None) = BackOfficeAction.async { ctx =>
    implicit val request = ctx.request
    env.datastores.globalConfigDataStore.singleton().flatMap {
      case config if (config.u2fLoginOnly || config.backofficeAuth0Config.isEmpty) =>
        FastFuture.successful(Redirect(controllers.routes.BackOfficeController.index()))
      case config if !(config.u2fLoginOnly || config.backofficeAuth0Config.isEmpty) => {

        config.backofficeAuth0Config match {
          case None =>
            FastFuture.successful(NotFound(views.html.otoroshi.error("Private apps are not configured", env)))
          case Some(backOfficeAuth0Config) => {
            (codeOpt, error) match {
              case (None, Some(err)) => FastFuture.successful(BadRequest(views.html.backoffice.unauthorized(env)))
              case (Some(code), None) =>
                getToken(code, backOfficeAuth0Config)
                  .flatMap {
                    case (idToken, accessToken) =>
                      getUser(accessToken, backOfficeAuth0Config).flatMap { user =>
                        val name  = (user \ "name").as[String]
                        val email = (user \ "email").as[String]
                        logger.info(s"Login successful for user '$email'")
                        BackOfficeUser(IdGenerator.token(64), name, email, user, None) // TODO : get from app_meta
                          .save(Duration(env.backOfficeSessionExp, TimeUnit.MILLISECONDS))
                          .map { boUser =>
                            env.datastores.backOfficeUserDataStore.hasAlreadyLoggedIn(email).map {
                              case false => {
                                env.datastores.backOfficeUserDataStore.alreadyLoggedIn(email)
                                Alerts.send(AdminFirstLogin(env.snowflakeGenerator.nextIdStr(), env.env, boUser))
                              }
                              case true => {
                                Alerts
                                  .send(AdminLoggedInAlert(env.snowflakeGenerator.nextIdStr(), env.env, boUser))
                              }
                            }
                            Redirect(
                              request.session
                                .get("bo-redirect-after-login")
                                .getOrElse(
                                  routes.BackOfficeController
                                    .index()
                                    .absoluteURL(env.isProd && env.exposedRootSchemeIsHttps)
                                )
                            ).removingFromSession("bo-redirect-after-login")
                              .addingToSession("bousr" -> boUser.randomId)
                          }
                      }
                  }
                  .recover {
                    case ex: IllegalStateException => Unauthorized(views.html.otoroshi.error(ex.getMessage, env))
                  }
              case _ => FastFuture.successful(BadRequest(views.html.otoroshi.error("No parameters supplied", env)))
            }
          }
        }
      }
    }
  }

  def getToken(code: String, config: Auth0Config): Future[(String, String)] = {
    val Auth0Config(clientSecret, clientId, callback, domain) = config
    val tokenResponse = env.Ws
      .url(s"https://$domain/oauth/token")
      .withHttpHeaders(HeaderNames.ACCEPT -> MimeTypes.JSON)
      .post(
        Json.obj(
          "client_id"     -> clientId,
          "client_secret" -> clientSecret,
          "redirect_uri"  -> callback,
          "code"          -> code,
          "grant_type"    -> "authorization_code"
        )
      )
    tokenResponse.flatMap { response =>
      (for {
        idToken     <- (response.json \ "id_token").asOpt[String]
        accessToken <- (response.json \ "access_token").asOpt[String]
      } yield {
        FastFuture.successful((idToken, accessToken))
      }).getOrElse(Future.failed[(String, String)](new IllegalStateException("Tokens not sent")))
    }

  }

  def getUser(accessToken: String, config: Auth0Config): Future[JsValue] = {
    val Auth0Config(_, _, _, domain) = config
    val userResponse = env.Ws
      .url(s"https://$domain/userinfo")
      .withQueryStringParameters("access_token" -> accessToken)
      .get()
    userResponse.flatMap(response => FastFuture.successful(response.json))
  }
}
