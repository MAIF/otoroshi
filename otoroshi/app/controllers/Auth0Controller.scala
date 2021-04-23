package otoroshi.controllers

import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import otoroshi.actions.{BackOfficeAction, BackOfficeActionAuth, PrivateAppsAction}
import akka.http.scaladsl.util.FastFuture
import akka.util.ByteString
import auth.saml.SamlAuthModuleConfig
import otoroshi.auth.{AuthModuleConfig, BasicAuthModule, BasicAuthModuleConfig, GenericOauth2ModuleConfig}
import otoroshi.env.Env
import otoroshi.events._
import otoroshi.gateway.Errors
import otoroshi.models.{BackOfficeUser, CorsSettings, PrivateAppsUser, ServiceDescriptor}
import otoroshi.utils.TypedMap
import play.api.Logger
import play.api.libs.json.Json
import play.api.mvc._
import otoroshi.security.IdGenerator
import otoroshi.utils.future.Implicits._

import scala.concurrent.Future
import scala.concurrent.duration.Duration

class AuthController(
    BackOfficeActionAuth: BackOfficeActionAuth,
    PrivateAppsAction: PrivateAppsAction,
    BackOfficeAction: BackOfficeAction,
    cc: ControllerComponents
)(implicit env: Env)
    extends AbstractController(cc) {

  implicit lazy val ec = env.otoroshiExecutionContext

  lazy val logger = Logger("otoroshi-auth-controller")

  def verifyHash(descId: String, auth: AuthModuleConfig, req: RequestHeader)(
      f: AuthModuleConfig => Future[Result]
  ): Future[Result] = {
    val hash     = req.getQueryString("hash").orElse(req.session.get("hash")).getOrElse("--")
    val expected = env.sign(s"${auth.id}:::$descId")
    if ((hash != "--" && hash == expected) || auth.isInstanceOf[SamlAuthModuleConfig])
      f(auth)
    else
      Errors.craftResponseResult(
        "Auth. config. bad signature",
        Results.BadRequest,
        req,
        None,
        Some("errors.auth.bad.signature"),
        attrs = TypedMap.empty
      )
  }

  def withAuthConfig(descriptor: ServiceDescriptor, req: RequestHeader)(
      f: AuthModuleConfig => Future[Result]
  ): Future[Result] = {
    descriptor.authConfigRef match {
      case None      =>
        Errors.craftResponseResult(
          "Auth. config. ref not found on the descriptor",
          Results.InternalServerError,
          req,
          Some(descriptor),
          Some("errors.auth.config.ref.not.found"),
          attrs = TypedMap.empty
        )
      case Some(ref) => {
        env.datastores.authConfigsDataStore.findById(ref).flatMap {
          case None       =>
            Errors.craftResponseResult(
              "Auth. config. not found on the descriptor",
              Results.InternalServerError,
              req,
              Some(descriptor),
              Some("errors.auth.config.not.found"),
              attrs = TypedMap.empty
            )
          case Some(auth) => f(auth)
        }
      }
    }
  }

  def confidentialAppLoginPageOptions() =
    PrivateAppsAction.async { ctx =>
      val cors = CorsSettings(
        enabled = true,
        allowOrigin = s"${env.rootScheme}${env.privateAppsHost}",
        exposeHeaders = Seq.empty[String],
        allowHeaders = Seq.empty[String],
        allowMethods = Seq("GET")
      )
      if (cors.shouldNotPass(ctx.request)) {
        Errors.craftResponseResult(
          "Cors error",
          Results.BadRequest,
          ctx.request,
          None,
          Some("errors.cors.error"),
          attrs = TypedMap.empty
        )
      } else {
        FastFuture.successful(Results.Ok(ByteString.empty).withHeaders(cors.asHeaders(ctx.request): _*))
      }
    }

  def confidentialAppLoginPage() =
    PrivateAppsAction.async { ctx =>
      import otoroshi.utils.http.RequestImplicits._
      import otoroshi.utils.future.Implicits._

      implicit val req = ctx.request

      ctx.request.getQueryString("desc") match {
        case None            => NotFound(otoroshi.views.html.oto.error("Service not found", env)).asFuture
        case Some(serviceId) => {
          env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
            case None                                                                                      => NotFound(otoroshi.views.html.oto.error("Service not found", env)).asFuture
            case Some(descriptor) if !descriptor.privateApp                                                =>
              NotFound(otoroshi.views.html.oto.error("Private apps are not configured", env)).asFuture
            case Some(descriptor) if descriptor.privateApp && descriptor.id != env.backOfficeDescriptor.id => {
              withAuthConfig(descriptor, ctx.request) { auth =>
                val expectedCookieName = s"oto-papps-${auth.cookieSuffix(descriptor)}"
                ctx.request.cookies.find(c => c.name == expectedCookieName) match {
                  case Some(cookie) => {
                    env.extractPrivateSessionId(cookie) match {
                      case None            =>
                        auth.authModule(ctx.globalConfig).paLoginPage(ctx.request, ctx.globalConfig, descriptor)
                      case Some(sessionId) => {
                        env.datastores.privateAppsUserDataStore.findById(sessionId).flatMap {
                          case None       =>
                            auth.authModule(ctx.globalConfig).paLoginPage(ctx.request, ctx.globalConfig, descriptor)
                          case Some(user) =>
                            ctx.request
                              .getQueryString("redirect")
                              .getOrElse(s"${req.theProtocol}://${req.theHost}${req.relativeUri}") match {
                              case "urn:ietf:wg:oauth:2.0:oob" => {
                                val redirection =
                                  s"${req.theProtocol}://${req.theHost}/.well-known/otoroshi/login?sessionId=${user.randomId}&redirectTo=urn:ietf:wg:oauth:2.0:oob&host=${req.theHost}&cp=${auth
                                    .cookieSuffix(descriptor)}&ma=${auth.sessionMaxAge}&httpOnly=${auth.sessionCookieValues.httpOnly}&secure=${auth.sessionCookieValues.secure}"
                                val hash        = env.sign(redirection)
                                FastFuture.successful(
                                  Redirect(s"$redirection&hash=$hash")
                                    .removingFromSession(
                                      s"pa-redirect-after-login-${auth.cookieSuffix(descriptor)}",
                                      "desc"
                                    )
                                    .withCookies(
                                      env.createPrivateSessionCookies(req.theHost, user.randomId, descriptor, auth): _*
                                    )
                                )
                              }
                              case redirectTo                  => {
                                val url                = new java.net.URL(redirectTo)
                                val host               = url.getHost
                                val scheme             = url.getProtocol
                                val setCookiesRedirect = url.getPort match {
                                  case -1   =>
                                    val redirection =
                                      s"$scheme://$host/.well-known/otoroshi/login?sessionId=${user.randomId}&redirectTo=$redirectTo&host=$host&cp=${auth
                                        .cookieSuffix(descriptor)}&ma=${auth.sessionMaxAge}&httpOnly=${auth.sessionCookieValues.httpOnly}&secure=${auth.sessionCookieValues.secure}"
                                    val hash        = env.sign(redirection)
                                    s"$redirection&hash=$hash"
                                  case port =>
                                    val redirection =
                                      s"$scheme://$host:$port/.well-known/otoroshi/login?sessionId=${user.randomId}&redirectTo=$redirectTo&host=$host&cp=${auth
                                        .cookieSuffix(descriptor)}&ma=${auth.sessionMaxAge}&httpOnly=${auth.sessionCookieValues.httpOnly}&secure=${auth.sessionCookieValues.secure}"
                                    val hash        = env.sign(redirection)
                                    s"$redirection&hash=$hash"
                                }
                                FastFuture.successful(
                                  Redirect(setCookiesRedirect)
                                    .removingFromSession(
                                      s"pa-redirect-after-login-${auth.cookieSuffix(descriptor)}",
                                      "desc"
                                    )
                                    .withCookies(
                                      env.createPrivateSessionCookies(host, user.randomId, descriptor, auth): _*
                                    )
                                )
                              }
                            }
                        }
                      }
                    }
                  }
                  case None         =>
                    auth.authModule(ctx.globalConfig).paLoginPage(ctx.request, ctx.globalConfig, descriptor)
                }
              }
            }
            case _                                                                                         => NotFound(otoroshi.views.html.oto.error("Private apps are not configured", env)).asFuture
          }
        }
      }
    }

  def confidentialAppLogout() =
    PrivateAppsAction.async { ctx =>
      implicit val req                  = ctx.request
      val redirectToOpt: Option[String] = req.queryString.get("redirectTo").map(_.last)
      val hostOpt: Option[String]       = req.queryString.get("host").map(_.last)
      val cookiePrefOpt: Option[String] = req.queryString.get("cp").map(_.last)
      (redirectToOpt, hostOpt, cookiePrefOpt) match {
        case (Some(redirectTo), Some(host), Some(cp)) =>
          FastFuture.successful(
            Redirect(redirectTo).discardingCookies(env.removePrivateSessionCookiesWithSuffix(host, cp): _*)
          )
        case _                                        =>
          Errors.craftResponseResult(
            "Missing parameters",
            Results.BadRequest,
            req,
            None,
            Some("errors.missing.parameters"),
            attrs = TypedMap.empty
          )
      }
    }

  def confidentialAppCallback() =
    PrivateAppsAction.async { ctx =>
      import otoroshi.utils.http.RequestImplicits._
      import otoroshi.utils.future.Implicits._

      implicit val req = ctx.request

      def saveUser(user: PrivateAppsUser, auth: AuthModuleConfig, descriptor: ServiceDescriptor, webauthn: Boolean)(
          implicit req: RequestHeader
      ) = {
        user
          .save(Duration(auth.sessionMaxAge, TimeUnit.SECONDS))
          .map { paUser =>
            logger.debug(s"Auth callback, creating session on the leader ${paUser.email}")
            env.clusterAgent.createSession(paUser)
            Alerts.send(
              UserLoggedInAlert(env.snowflakeGenerator.nextIdStr(), env.env, paUser, ctx.from, ctx.ua, auth.id)
            )
            ctx.request.session
              .get(s"pa-redirect-after-login-${auth.cookieSuffix(descriptor)}")
              .getOrElse(
                routes.PrivateAppsController.home().absoluteURL(env.exposedRootSchemeIsHttps)
              ) match {
              case "urn:ietf:wg:oauth:2.0:oob" => {
                val redirection =
                  s"${req.theProtocol}://${req.theHost}/.well-known/otoroshi/login?sessionId=${paUser.randomId}&redirectTo=urn:ietf:wg:oauth:2.0:oob&host=${req.theHost}&cp=${auth
                    .cookieSuffix(descriptor)}&ma=${auth.sessionMaxAge}&httpOnly=${auth.sessionCookieValues.httpOnly}&secure=${auth.sessionCookieValues.secure}"
                val hash        = env.sign(redirection)
                Redirect(
                  s"$redirection&hash=$hash"
                ).removingFromSession(s"pa-redirect-after-login-${auth.cookieSuffix(descriptor)}", "desc")
                  .withCookies(env.createPrivateSessionCookies(req.theHost, user.randomId, descriptor, auth): _*)
              }
              case redirectTo                  => {
                val url                = new java.net.URL(redirectTo)
                val host               = url.getHost
                val scheme             = url.getProtocol
                val setCookiesRedirect = url.getPort match {
                  case -1   =>
                    val redirection =
                      s"$scheme://$host/.well-known/otoroshi/login?sessionId=${paUser.randomId}&redirectTo=$redirectTo&host=$host&cp=${auth
                        .cookieSuffix(descriptor)}&ma=${auth.sessionMaxAge}&httpOnly=${auth.sessionCookieValues.httpOnly}&secure=${auth.sessionCookieValues.secure}"
                    val hash        = env.sign(redirection)
                    s"$redirection&hash=$hash"
                  case port =>
                    val redirection =
                      s"$scheme://$host:$port/.well-known/otoroshi/login?sessionId=${paUser.randomId}&redirectTo=$redirectTo&host=$host&cp=${auth
                        .cookieSuffix(descriptor)}&ma=${auth.sessionMaxAge}&httpOnly=${auth.sessionCookieValues.httpOnly}&secure=${auth.sessionCookieValues.secure}"
                    val hash        = env.sign(redirection)
                    s"$redirection&hash=$hash"
                }
                if (webauthn) {
                  Ok(Json.obj("location" -> setCookiesRedirect))
                    .removingFromSession(s"pa-redirect-after-login-${auth.cookieSuffix(descriptor)}", "desc")
                    .withCookies(env.createPrivateSessionCookies(host, paUser.randomId, descriptor, auth): _*)
                } else {
                  Redirect(setCookiesRedirect)
                    .removingFromSession(s"pa-redirect-after-login-${auth.cookieSuffix(descriptor)}", "desc")
                    .withCookies(env.createPrivateSessionCookies(host, paUser.randomId, descriptor, auth): _*)
                }
              }
            }
          }
      }

      ctx.request.getQueryString("desc").orElse(ctx.request.session.get("desc")) match {
        case None            => NotFound(otoroshi.views.html.oto.error("Service not found", env)).asFuture
        case Some(serviceId) => {
          env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
            case None                                                                                      => NotFound(otoroshi.views.html.oto.error("Service not found", env)).asFuture
            case Some(descriptor) if !descriptor.privateApp                                                =>
              NotFound(otoroshi.views.html.oto.error("Private apps are not configured", env)).asFuture
            case Some(descriptor) if descriptor.privateApp && descriptor.id != env.backOfficeDescriptor.id => {
              withAuthConfig(descriptor, ctx.request) { _auth =>
                verifyHash(descriptor.id, _auth, ctx.request) {
                  case auth if auth.`type` == "basic" && auth.asInstanceOf[BasicAuthModuleConfig].webauthn => {
                    val authModule = auth.authModule(ctx.globalConfig).asInstanceOf[BasicAuthModule]
                    req.headers.get("WebAuthn-Login-Step") match {
                      case Some("start")  => {
                        authModule.webAuthnLoginStart(ctx.request.body.asJson.get, descriptor).map {
                          case Left(error) => BadRequest(Json.obj("error" -> error))
                          case Right(reg)  => Ok(reg)
                        }
                      }
                      case Some("finish") => {
                        authModule.webAuthnLoginFinish(ctx.request.body.asJson.get, descriptor).flatMap {
                          case Left(error) => BadRequest(Json.obj("error" -> error)).future
                          case Right(user) => saveUser(user, auth, descriptor, true)(ctx.request)
                        }
                      }
                      case _              =>
                        BadRequest(
                          otoroshi.views.html.oto
                            .error(message = s"Missing step", _env = env, title = "Authorization error")
                        ).asFuture
                    }
                  }
                  case auth                                                                                => {
                    auth.authModule(ctx.globalConfig).paCallback(ctx.request, ctx.globalConfig, descriptor).flatMap {
                      case Left(error) => {
                        BadRequest(
                          otoroshi.views.html.oto
                            .error(
                              message = s"You're not authorized here: ${error}",
                              _env = env,
                              title = "Authorization error"
                            )
                        ).asFuture
                      }
                      case Right(user) => saveUser(user, auth, descriptor, false)(ctx.request)
                    }
                  }
                }
              }
            }
            case _                                                                                         => NotFound(otoroshi.views.html.oto.error("Private apps are not configured", env)).asFuture
          }
        }
      }
    }

  def auth0error(error: Option[String], error_description: Option[String]) =
    BackOfficeAction { ctx =>
      val errorId = IdGenerator.token(16)
      logger.error(
        s"[AUTH0 ERROR] error_id: $errorId => ${error.getOrElse("--")} : ${error_description.getOrElse("--")}"
      )
      Redirect(routes.BackOfficeController.error(Some(s"Auth0 error - logged with id: $errorId")))
    }

  def backOfficeLogin() =
    BackOfficeAction.async { ctx =>
      implicit val request = ctx.request
      env.datastores.globalConfigDataStore.singleton().flatMap {
        case config if !(config.u2fLoginOnly || config.backOfficeAuthRef.isEmpty) => {
          config.backOfficeAuthRef match {
            case None        => FastFuture.successful(Redirect(otoroshi.controllers.routes.BackOfficeController.index()))
            case Some(aconf) => {
              env.datastores.authConfigsDataStore.findById(aconf).flatMap {
                case None        =>
                  FastFuture
                    .successful(NotFound(otoroshi.views.html.oto.error("BackOffice Oauth is not configured", env)))
                case Some(oauth) => oauth.authModule(config).boLoginPage(ctx.request, config)
              }
            }
          }
        }
        case config if config.u2fLoginOnly || config.backOfficeAuthRef.isEmpty    =>
          FastFuture.successful(Redirect(otoroshi.controllers.routes.BackOfficeController.index()))
      }
    }

  def backOfficeLogout() =
    BackOfficeActionAuth.async { ctx =>
      import otoroshi.utils.http.RequestImplicits._
      implicit val request = ctx.request
      val redirect         = request.getQueryString("redirect")
      ctx.user.simpleLogin match {
        case true  =>
          ctx.user.delete().map { _ =>
            Alerts.send(AdminLoggedOutAlert(env.snowflakeGenerator.nextIdStr(), env.env, ctx.user, ctx.from, ctx.ua))
            val userRedirect = redirect.getOrElse(routes.BackOfficeController.index().url)
            Redirect(userRedirect).removingFromSession("bousr", "bo-redirect-after-login")
          }
        case false => {
          env.datastores.globalConfigDataStore.singleton().flatMap { config =>
            config.backOfficeAuthRef match {
              case None        => {
                ctx.user.delete().map { _ =>
                  Alerts
                    .send(AdminLoggedOutAlert(env.snowflakeGenerator.nextIdStr(), env.env, ctx.user, ctx.from, ctx.ua))
                  val userRedirect = redirect.getOrElse(routes.BackOfficeController.index().url)
                  Redirect(userRedirect).removingFromSession("bousr", "bo-redirect-after-login")
                }
              }
              case Some(aconf) => {
                env.datastores.authConfigsDataStore.findById(aconf).flatMap {
                  case None        =>
                    FastFuture.successful(
                      NotFound(otoroshi.views.html.oto.error("BackOffice auth is not configured", env))
                        .removingFromSession("bousr", "bo-redirect-after-login")
                    )
                  case Some(oauth) =>
                    oauth.authModule(config).boLogout(ctx.request, ctx.user, config).flatMap {
                      case Left(body) =>
                        ctx.user.delete().map { _ =>
                          Alerts.send(
                            AdminLoggedOutAlert(env.snowflakeGenerator.nextIdStr(), env.env, ctx.user, ctx.from, ctx.ua)
                          )
                          body.removingFromSession("bousr", "bo-redirect-after-login")
                        }
                      case Right(value) =>
                        value match {
                          case None =>
                            ctx.user.delete().map { _ =>
                              Alerts.send(
                                AdminLoggedOutAlert(env.snowflakeGenerator.nextIdStr(), env.env, ctx.user, ctx.from, ctx.ua)
                              )
                              val userRedirect = redirect.getOrElse(routes.BackOfficeController.index().url)
                              Redirect(userRedirect).removingFromSession("bousr", "bo-redirect-after-login")
                            }
                          case Some(logoutUrl) =>
                            val userRedirect = redirect.getOrElse(s"http://${request.theHost}/")
                            val actualRedirectUrl =
                              logoutUrl.replace("${redirect}", URLEncoder.encode(userRedirect, "UTF-8"))
                            ctx.user.delete().map { _ =>
                              Alerts.send(
                                AdminLoggedOutAlert(env.snowflakeGenerator.nextIdStr(), env.env, ctx.user, ctx.from, ctx.ua)
                              )
                              Redirect(actualRedirectUrl).removingFromSession("bousr", "bo-redirect-after-login")
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

  def backOfficeCallback(error: Option[String] = None, error_description: Option[String] = None) = {
    BackOfficeAction.async { ctx =>
      implicit val request = ctx.request

      def saveUser(user: BackOfficeUser, auth: AuthModuleConfig, webauthn: Boolean)(implicit req: RequestHeader) = {
        user
          .save(Duration(env.backOfficeSessionExp, TimeUnit.MILLISECONDS))
          .map { boUser =>
            env.datastores.backOfficeUserDataStore.hasAlreadyLoggedIn(user.email).map {
              case false => {
                env.datastores.backOfficeUserDataStore.alreadyLoggedIn(user.email)
                Alerts.send(AdminFirstLogin(env.snowflakeGenerator.nextIdStr(), env.env, boUser, ctx.from, ctx.ua))
              }
              case true  => {
                Alerts
                  .send(
                    AdminLoggedInAlert(env.snowflakeGenerator.nextIdStr(), env.env, boUser, ctx.from, ctx.ua, auth.id)
                  )
              }
            }
            Redirect(
              request.session
                .get("bo-redirect-after-login")
                .getOrElse(
                  routes.BackOfficeController
                    .index()
                    .absoluteURL(env.exposedRootSchemeIsHttps)
                )
            ).removingFromSession("bo-redirect-after-login")
              .addingToSession("bousr" -> boUser.randomId)
          }
      }

      error match {
        case Some(e) => FastFuture.successful(BadRequest(otoroshi.views.html.backoffice.unauthorized(env)))
        case None    => {
          env.datastores.globalConfigDataStore.singleton().flatMap {
            case config if config.u2fLoginOnly || config.backOfficeAuthRef.isEmpty    =>
              FastFuture.successful(Redirect(otoroshi.controllers.routes.BackOfficeController.index()))
            case config if !(config.u2fLoginOnly || config.backOfficeAuthRef.isEmpty) => {

              config.backOfficeAuthRef match {
                case None                        =>
                  FastFuture
                    .successful(NotFound(otoroshi.views.html.oto.error("BackOffice OAuth is not configured", env)))
                case Some(backOfficeAuth0Config) => {
                  env.datastores.authConfigsDataStore.findById(backOfficeAuth0Config).flatMap {
                    case None        =>
                      FastFuture
                        .successful(NotFound(otoroshi.views.html.oto.error("BackOffice OAuth is not found", env)))
                    case Some(oauth) =>
                      verifyHash("backoffice", oauth, ctx.request) {

                        case auth if auth.`type` == "basic" && auth.asInstanceOf[BasicAuthModuleConfig].webauthn => {
                          val authModule = auth.authModule(config).asInstanceOf[BasicAuthModule]
                          request.headers.get("WebAuthn-Login-Step") match {
                            case Some("start")  => {
                              authModule.webAuthnAdminLoginStart(ctx.request.body.asJson.get).map {
                                case Left(error) => BadRequest(Json.obj("error" -> error))
                                case Right(reg)  => Ok(reg)
                              }
                            }
                            case Some("finish") => {
                              authModule.webAuthnAdminLoginFinish(ctx.request.body.asJson.get).flatMap {
                                case Left(error) => BadRequest(Json.obj("error" -> error)).future
                                case Right(user) => saveUser(user, auth, true)(ctx.request)
                              }
                            }
                            case _              =>
                              BadRequest(
                                otoroshi.views.html.oto
                                  .error(message = s"Missing step", _env = env, title = "Authorization error")
                              ).asFuture
                          }
                        }
                        case auth                                                                                => {
                          oauth.authModule(config).boCallback(ctx.request, config).flatMap {
                            case Left(err)   =>
                              FastFuture.successful(
                                BadRequest(
                                  otoroshi.views.html.oto
                                    .error(
                                      message = s"You're not authorized here: ${err}",
                                      _env = env,
                                      title = "Authorization error"
                                    )
                                )
                              )
                            case Right(user) =>
                              logger.debug(s"Login successful for user '${user.email}'")
                              saveUser(user, auth, false)(ctx.request)
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
}
