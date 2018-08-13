package controllers

import java.util.concurrent.TimeUnit

import actions.{BackOfficeAction, BackOfficeActionAuth, PrivateAppsAction}
import akka.http.scaladsl.util.FastFuture
import auth.AuthModuleConfig
import env.Env
import events.{AdminFirstLogin, AdminLoggedInAlert, AdminLoggedOutAlert, Alerts}
import gateway.Errors
import models.ServiceDescriptor
import play.api.Logger
import play.api.mvc._
import security.IdGenerator

import scala.concurrent.Future
import scala.concurrent.duration.Duration

class Auth0Controller(BackOfficeActionAuth: BackOfficeActionAuth,
                      PrivateAppsAction: PrivateAppsAction,
                      BackOfficeAction: BackOfficeAction,
                      cc: ControllerComponents)(implicit env: Env)
    extends AbstractController(cc) {

  implicit lazy val ec = env.otoroshiExecutionContext

  lazy val logger = Logger("otoroshi-auth0")

  def withAuthConfig(descriptor: ServiceDescriptor, req: RequestHeader)(f: AuthModuleConfig => Future[Result]): Future[Result] = {
    descriptor.authConfigRef match {
      case None => Errors.craftResponseResult(
        "Auth. config. ref not found on the descriptor",
        Results.InternalServerError,
        req,
        Some(descriptor),
        Some("errors.auth.config.ref.not.found")
      )
      case Some(ref) => {
        env.datastores.authConfigsDataStore.findById(ref).flatMap {
          case None => Errors.craftResponseResult(
            "Auth. config. not found on the descriptor",
            Results.InternalServerError,
            req,
            Some(descriptor),
            Some("errors.auth.config.not.found")
          )
          case Some(auth) => f(auth)
        }
      }
    }
  }


  def confidentialAppLoginPage() = PrivateAppsAction.async { ctx =>

    import utils.future.Implicits._

    implicit val req = ctx.request

    ctx.request.getQueryString("desc") match {
      case None => NotFound(views.html.otoroshi.error("Service not found", env)).asFuture
      case Some(serviceId) => {
        env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
          case None => NotFound(views.html.otoroshi.error("Service not found", env)).asFuture
          case Some(descriptor) if !descriptor.privateApp => NotFound(views.html.otoroshi.error("Private apps are not configured", env)).asFuture
          case Some(descriptor) if descriptor.privateApp && descriptor.id != env.backOfficeDescriptor.id => {
            withAuthConfig(descriptor, ctx.request) { auth =>
              val expectedCookieName = s"oto-papps-${auth.cookieSuffix(descriptor)}"
              ctx.request.cookies.find(c => c.name == expectedCookieName) match {
                case Some(cookie) => {
                  env.extractPrivateSessionId(cookie) match {
                    case None =>
                      auth.authModule(ctx.globalConfig).paLoginPage(ctx.request, ctx.globalConfig, descriptor)
                    case Some(sessionId) => {
                      env.datastores.privateAppsUserDataStore.findById(sessionId).flatMap {
                        case None =>
                          auth.authModule(ctx.globalConfig).paLoginPage(ctx.request, ctx.globalConfig, descriptor)
                        case Some(user) =>
                          val redirectTo = ctx.request.getQueryString("redirect").get
                          val url = new java.net.URL(redirectTo)
                          val host = url.getHost
                          val scheme = url.getProtocol
                          val setCookiesRedirect = url.getPort match {
                            case -1 =>
                              s"$scheme://$host/__otoroshi_private_apps_login?sessionId=${user.randomId}&redirectTo=$redirectTo&host=$host&cp=${auth.cookieSuffix(descriptor)}"
                            case port =>
                              s"$scheme://$host:$port/__otoroshi_private_apps_login?sessionId=${user.randomId}&redirectTo=$redirectTo&host=$host&cp=${auth.cookieSuffix(descriptor)}"
                          }
                          FastFuture.successful(Redirect(setCookiesRedirect)
                            .removingFromSession("pa-redirect-after-login")
                            .withCookies(env.createPrivateSessionCookies(host, user.randomId, descriptor, auth): _*))
                      }
                    }
                  }
                }
                case None =>
                  auth.authModule(ctx.globalConfig).paLoginPage(ctx.request, ctx.globalConfig, descriptor)
              }
            }
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
            withAuthConfig(descriptor, ctx.request) { auth =>
              auth.authModule(ctx.globalConfig).paLogout(ctx.request, ctx.globalConfig, descriptor).map { _ =>
                implicit val request = ctx.request
                val redirect = ctx.request.getQueryString("redirect")
                ctx.user.foreach(_.delete())
                redirect match {
                  case Some(url) =>
                    val host = new java.net.URL(url).getHost
                    Redirect(url)
                      .removingFromSession("pa-redirect-after-login")
                      .discardingCookies(env.removePrivateSessionCookies(host, descriptor, auth): _*)
                  case None =>
                    Redirect(routes.PrivateAppsController.home())
                      .removingFromSession("pa-redirect-after-login")
                      .discardingCookies(env.removePrivateSessionCookies(request.host, descriptor, auth): _*)
                }
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
            withAuthConfig(descriptor, ctx.request) { auth =>
              auth.authModule(ctx.globalConfig).paCallback(ctx.request, ctx.globalConfig, descriptor).flatMap {
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
                          s"$scheme://$host/__otoroshi_private_apps_login?sessionId=${paUser.randomId}&redirectTo=$redirectTo&host=$host&cp=${auth.cookieSuffix(descriptor)}"
                        case port =>
                          s"$scheme://$host:$port/__otoroshi_private_apps_login?sessionId=${paUser.randomId}&redirectTo=$redirectTo&host=$host&cp=${auth.cookieSuffix(descriptor)}"
                      }
                      Redirect(setCookiesRedirect)
                        .removingFromSession("pa-redirect-after-login")
                        .withCookies(env.createPrivateSessionCookies(host, paUser.randomId, descriptor, auth): _*)
                    }
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

  def backOfficeLogin() = BackOfficeAction.async { ctx =>
    implicit val request = ctx.request
    env.datastores.globalConfigDataStore.singleton().flatMap {
      case config if !(config.u2fLoginOnly || config.backOfficeAuthRef.isEmpty) => {
        config.backOfficeAuthRef match {
          case None => FastFuture.successful(Redirect(controllers.routes.BackOfficeController.index()))
          case Some(aconf) => {
            env.datastores.authConfigsDataStore.findById(aconf).flatMap {
              case None => FastFuture.successful(NotFound(views.html.otoroshi.error("BackOffice Oauth is not configured", env)))
              case Some(oauth) => oauth.authModule(config).boLoginPage(ctx.request, config)
            }
          }
        }
      }
      case config if config.u2fLoginOnly || config.backOfficeAuthRef.isEmpty =>
        FastFuture.successful(Redirect(controllers.routes.BackOfficeController.index()))
    }
  }

  def backOfficeLogout() = BackOfficeActionAuth.async { ctx =>
    implicit val request = ctx.request
    val redirect = request.getQueryString("redirect")
    env.datastores.globalConfigDataStore.singleton().flatMap { config =>
      config.backOfficeAuthRef match {
        case None => FastFuture.successful(Redirect(controllers.routes.BackOfficeController.index()))
        case Some(aconf) => {
          env.datastores.authConfigsDataStore.findById(aconf).flatMap {
            case None => FastFuture.successful(NotFound(views.html.otoroshi.error("BackOffice Oauth is not configured", env)))
            case Some(oauth) => oauth.authModule(config).boLogout(ctx.request, config).flatMap { _ =>
              ctx.user.delete().map { _ =>
                Alerts.send(AdminLoggedOutAlert(env.snowflakeGenerator.nextIdStr(), env.env, ctx.user))
                redirect match {
                  case Some(url) => Redirect(url).removingFromSession("bousr", "bo-redirect-after-login")
                  case None =>
                    Redirect(routes.BackOfficeController.index()).removingFromSession("bousr", "bo-redirect-after-login")
                }
              }
            }
          }
        }
      }
    }
  }

  def backOfficeCallback(error: Option[String] = None,
                         error_description: Option[String] = None) = BackOfficeAction.async { ctx =>
    implicit val request = ctx.request

    error match {
      case Some(e) => FastFuture.successful(BadRequest(views.html.backoffice.unauthorized(env)))
      case None => {
        env.datastores.globalConfigDataStore.singleton().flatMap {
          case config if (config.u2fLoginOnly || config.backOfficeAuthRef.isEmpty) =>
            FastFuture.successful(Redirect(controllers.routes.BackOfficeController.index()))
          case config if !(config.u2fLoginOnly || config.backOfficeAuthRef.isEmpty) => {

            config.backOfficeAuthRef match {
              case None =>
                FastFuture.successful(NotFound(views.html.otoroshi.error("BackOffice OAuth is not configured", env)))
              case Some(backOfficeAuth0Config) => {
                env.datastores.authConfigsDataStore.findById(backOfficeAuth0Config).flatMap {
                  case None => FastFuture.successful(NotFound(views.html.otoroshi.error("BackOffice OAuth is not found", env)))
                  case Some(oauth) => oauth.authModule(config).boCallback(ctx.request, config).flatMap {
                    case Left(err) => {
                      FastFuture.successful(BadRequest(
                        views.html.otoroshi
                          .error(message = s"You're not authorized here: ${error}", _env = env, title = "Authorization error")
                      ))
                    }
                    case Right(user) => {
                      logger.info(s"Login successful for user '${user.email}'")
                      user.save(Duration(env.backOfficeSessionExp, TimeUnit.MILLISECONDS))
                        .map { boUser =>
                          env.datastores.backOfficeUserDataStore.hasAlreadyLoggedIn(user.email).map {
                            case false => {
                              env.datastores.backOfficeUserDataStore.alreadyLoggedIn(user.email)
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
                }
              }
            }
          }
        }
      }
    }
  }
}
