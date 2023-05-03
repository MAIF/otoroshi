package otoroshi.controllers

import akka.http.scaladsl.util.FastFuture
import akka.util.ByteString
import otoroshi.actions.{BackOfficeAction, BackOfficeActionAuth, PrivateAppsAction, PrivateAppsActionContext}
import otoroshi.auth._
import otoroshi.env.Env
import otoroshi.events._
import otoroshi.gateway.Errors
import otoroshi.models.{BackOfficeUser, CorsSettings, PrivateAppsUser, ServiceDescriptor}
import otoroshi.next.models.NgRoute
import otoroshi.next.plugins.{MultiAuthModule, NgMultiAuthModuleConfig}
import otoroshi.security.IdGenerator
import otoroshi.utils.{RegexPool, TypedMap}
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json.{JsArray, JsError, JsObject, JsSuccess, JsValue, Json}
import play.api.mvc._

import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
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

  def decryptState(req: RequestHeader): JsValue = {
    val secretToBytes = env.otoroshiSecret.padTo(16, "0").mkString("").take(16).getBytes

    val cipher: Cipher = Cipher.getInstance("AES")
    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(secretToBytes, "AES"))
    val decoded        =
      java.util.Base64.getUrlDecoder.decode(req.getQueryString("state").getOrElse(Json.stringify(Json.obj())))

    scala.util.Try {
      Json.parse(new String(cipher.doFinal(decoded)))
    } recover { case _ =>
      Json.obj()
    } get
  }

  def verifyHash(descId: String, auth: AuthModuleConfig, req: RequestHeader)(
      f: AuthModuleConfig => Future[Result]
  ): Future[Result] = {
    val hash: String = auth match {
      case module: GenericOauth2ModuleConfig if module.noWildcardRedirectURI =>
        val unsignedState = decryptState(req)
        if (logger.isDebugEnabled) logger.debug(s"Decoded state : ${Json.prettyPrint(unsignedState)}")
        (unsignedState \ "hash").asOpt[String].getOrElse("--")
      case _                                                                 =>
        req.getQueryString("hash").orElse(req.session.get("hash")).getOrElse("--")
    }

    val expected = env.sign(s"${auth.id}:::$descId")
    if (
      (hash != "--" && hash == expected) || auth.isInstanceOf[SamlAuthModuleConfig] || auth
        .isInstanceOf[Oauth1ModuleConfig]
    )
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

  def withMultiAuthConfig(route: NgRoute, req: RequestHeader, refFromRelayState: Option[String] = None)(
    f: AuthModuleConfig => Future[Result]
  ): Future[Result] = {
    lazy val error = Errors.craftResponseResult(
      "Multi Auth. config. ref not found on the route",
      Results.InternalServerError,
      req,
      None,
      Some("errors.multi.auth.config.ref.not.found"),
      maybeRoute = Some(route),
      attrs = TypedMap.empty
    )

    route.plugins.getPluginByClass[MultiAuthModule] match {
      case None => error
      case Some(authPlugin) => {
        (req.getQueryString("ref"), refFromRelayState) match {
          case (Some(ref), _) => env.proxyState.authModuleAsync(ref).flatMap {
              case None => error
              case Some(auth) => f(auth)
            }
          case (_, Some(ref)) => env.proxyState.authModuleAsync(ref).flatMap {
              case None => error
              case Some(auth) => f(auth)
            }
          case (_, _) => req.getQueryString("email") match {
              case None => error
              case Some(email) => NgMultiAuthModuleConfig.format.reads(authPlugin.config.raw) match {
                case JsSuccess(config, _) => config.usersGroups
                  .value
                  .find(p => p._2.asOpt[Seq[String]].getOrElse(Seq.empty)
                    .exists(em => RegexPool.theRegex(em).map(e => e.matches(email)).getOrElse(email == em))) match {
                  case Some(auth) => env.proxyState.authModuleAsync(auth._1).flatMap {
                    case None => error
                    case Some(auth) => f(auth)
                  }
                  case None =>
                    logger.error(s"Email does not match any groups")
                    error
                }
                case JsError(_) =>
                  logger.error(s"Failed to read auth module configuration")
                  error
              }
            }
        }
      }
    }
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
        //env.datastores.authConfigsDataStore.findById(ref).flatMap {
        env.proxyState.authModuleAsync(ref).flatMap {
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

  def computeSec(user: PrivateAppsUser): String = env.aesEncrypt(user.json.stringify)

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

  def confidentialAppSimpleLoginPage() = multiLoginPage()(
    (auths: JsObject, route: NgRoute, redirect: Option[String]) => {
      Results
        .Ok(otoroshi.views.html.privateapps.simplelogin(env,
          redirect,
          route.id
        ))
        .vfuture
    })

  def confidentialAppMultiLoginPage() = multiLoginPage()(
    (auths: JsObject, route: NgRoute, redirect: Option[String]) => {
      Results
        .Ok(otoroshi.views.html.privateapps.multilogin(env,
          Json.stringify(auths),
          redirect,
          route.id
        ))
        .vfuture
    })

  private def multiLoginPage()(f: (JsObject, NgRoute, Option[String]) => Future[Result]) = PrivateAppsAction.async { ctx =>
    ctx.request.getQueryString("route") match {
      case None => NotFound(otoroshi.views.html.oto.error("Route not found", env)).vfuture
      case Some(routeId) =>
        env.proxyState.route(routeId).vfuture.flatMap {
          case None => NotFound(otoroshi.views.html.oto.error("Route not found", env)).vfuture
          case Some(route) if !route.plugins.hasPlugin[MultiAuthModule] =>
            NotFound(otoroshi.views.html.oto.error("Private apps are not configured", env)).vfuture
          case Some(route) if route.plugins.hasPlugin[MultiAuthModule] && route.id != env.backOfficeDescriptor.id =>
            route.plugins.getPluginByClass[MultiAuthModule] match {
              case Some(auth) => NgMultiAuthModuleConfig.format.reads(auth.config.raw) match {
                case JsSuccess(config, _) =>
                  val auths = config.modules.flatMap(module => env.proxyState.authModule(module))
                    .foldLeft(Json.obj()) {
                      case (acc, auth) => acc ++ Json.obj(auth.id -> auth.name)
                    }
                  f(auths, route, ctx.request.getQueryString("redirect"))
                case JsError(errors) =>
                  logger.error(s"Failed to parse multi auth configuration, $errors")
                  NotFound(otoroshi.views.html.oto.error("Private apps are not configured", env)).vfuture
              }
              case None => NotFound(otoroshi.views.html.oto.error("Private apps are not configured", env)).vfuture
            }

          case _ => NotFound(otoroshi.views.html.oto.error("Private apps are not configured", env)).vfuture
        }
    }
  }

  def confidentialAppLoginPage() =
    PrivateAppsAction.async { ctx =>
      import otoroshi.utils.http.RequestImplicits._
      implicit val req = ctx.request

      (req.getQueryString("desc"), req.getQueryString("route")) match {
        case (None, None)            => NotFound(otoroshi.views.html.oto.error("Service not found", env)).vfuture
        case (_, Some(route)) =>
          env.proxyState.route(route).vfuture.flatMap {
            case None => NotFound(otoroshi.views.html.oto.error("Route not found", env)).vfuture
            case Some(route) if !route.plugins.hasPlugin[MultiAuthModule] =>
              NotFound(otoroshi.views.html.oto.error("Private apps are not configured", env)).vfuture
            case Some(route) if route.plugins.hasPlugin[MultiAuthModule] && route.id != env.backOfficeDescriptor.id => {
              withMultiAuthConfig(route, req) { auth =>
                val expectedCookieName = s"oto-papps-${auth.routeCookieSuffix(route)}"
                req.cookies.find(c => c.name == expectedCookieName) match {
                  case Some(cookie) => {
                    env.extractPrivateSessionId(cookie) match {
                      case None =>
                        auth.authModule(ctx.globalConfig).paLoginPage(req, ctx.globalConfig, route.legacy, true)
                      case Some(sessionId) =>
                        env.datastores.privateAppsUserDataStore.findById(sessionId).flatMap {
                          case None =>
                            auth.authModule(ctx.globalConfig).paLoginPage(req, ctx.globalConfig, route.legacy, true)
                          case Some(user) =>
                            val sec = computeSec(user)
                            val secStr = if (auth.clientSideSessionEnabled) s"&sec=${sec}" else ""
                            req
                              .getQueryString("redirect")
                              .getOrElse(s"${req.theProtocol}://${req.theHost}${req.relativeUri}") match {
                              case "urn:ietf:wg:oauth:2.0:oob" => {
                                val redirection =
                                  s"${req.theProtocol}://${req.theHost}/.well-known/otoroshi/login?route=true&sessionId=${user.randomId}&redirectTo=urn:ietf:wg:oauth:2.0:oob&host=${req.theHost}&cp=${
                                    auth.routeCookieSuffix(route)
                                  }&ma=${auth.sessionMaxAge}&httpOnly=${auth.sessionCookieValues.httpOnly}&secure=${auth.sessionCookieValues.secure}${secStr}"
                                val hash = env.sign(redirection)
                                FastFuture.successful(
                                  Redirect(s"$redirection&hash=$hash")
                                    .removingFromSession(
                                      s"pa-redirect-after-login-${auth.routeCookieSuffix(route)}",
                                      "desc"
                                    )
                                    .withCookies(
                                      env.createPrivateSessionCookies(
                                        req.theHost,
                                        user.randomId,
                                        route.legacy,
                                        auth,
                                        user.some
                                      ): _*
                                    )
                                )
                              }
                              case redirectTo => {
                                // TODO - check if ref is needed
                                val url = new java.net.URL(redirectTo)
                                val host = url.getHost
                                val scheme = url.getProtocol
                                val setCookiesRedirect = url.getPort match {
                                  case -1 =>
                                    val redirection =
                                      s"$scheme://$host/.well-known/otoroshi/login?route=true&sessionId=${user.randomId}&redirectTo=$redirectTo&host=$host&cp=${
                                        auth
                                          .routeCookieSuffix(route)
                                      }&ma=${auth.sessionMaxAge}&httpOnly=${auth.sessionCookieValues.httpOnly}&secure=${auth.sessionCookieValues.secure}${secStr}"
                                    val hash = env.sign(redirection)
                                    s"$redirection&hash=$hash"
                                  case port =>
                                    val redirection =
                                      s"$scheme://$host:$port/.well-known/otoroshi/login?route=true&sessionId=${user.randomId}&redirectTo=$redirectTo&host=$host&cp=${
                                        auth
                                          .routeCookieSuffix(route)
                                      }&ma=${auth.sessionMaxAge}&httpOnly=${auth.sessionCookieValues.httpOnly}&secure=${auth.sessionCookieValues.secure}${secStr}"
                                    val hash = env.sign(redirection)
                                    s"$redirection&hash=$hash"
                                }
                                FastFuture.successful(
                                  Redirect(setCookiesRedirect)
                                    .removingFromSession(
                                      s"pa-redirect-after-login-${auth.routeCookieSuffix(route)}",
                                      "desc"
                                    )
                                    .withCookies(
                                      env.createPrivateSessionCookies(
                                        host,
                                        user.randomId,
                                        route.legacy,
                                        auth,
                                        user.some
                                      ): _*
                                    )
                                )
                              }
                            }
                        }
                    }
                  }
                  case None =>
                    auth.authModule(ctx.globalConfig).paLoginPage(req, ctx.globalConfig, route.legacy, true)
                }
              }
            }
            case _ => NotFound(otoroshi.views.html.oto.error("Private apps are not configured", env)).vfuture
          }
        case (Some(serviceId), _) => {
          env.datastores.serviceDescriptorDataStore.findOrRouteById(serviceId).flatMap {
            case None                                                                                      => NotFound(otoroshi.views.html.oto.error("Service not found", env)).vfuture
            case Some(descriptor) if !descriptor.privateApp                                                =>
              NotFound(otoroshi.views.html.oto.error("Private apps are not configured", env)).vfuture
            case Some(descriptor) if descriptor.privateApp && descriptor.id != env.backOfficeDescriptor.id => {
              withAuthConfig(descriptor, req) { auth =>
                val expectedCookieName = s"oto-papps-${auth.cookieSuffix(descriptor)}"
                req.cookies.find(c => c.name == expectedCookieName) match {
                  case Some(cookie) => {
                    env.extractPrivateSessionId(cookie) match {
                      case None            =>
                        auth.authModule(ctx.globalConfig).paLoginPage(req, ctx.globalConfig, descriptor, false)
                      case Some(sessionId) => {
                        env.datastores.privateAppsUserDataStore.findById(sessionId).flatMap {
                          case None       =>
                            auth.authModule(ctx.globalConfig).paLoginPage(req, ctx.globalConfig, descriptor, false)
                          case Some(user) =>
                            val sec    = computeSec(user)
                            val secStr = if (auth.clientSideSessionEnabled) s"&sec=${sec}" else ""
                            req
                              .getQueryString("redirect")
                              .getOrElse(s"${req.theProtocol}://${req.theHost}${req.relativeUri}") match {
                              case "urn:ietf:wg:oauth:2.0:oob" => {
                                val redirection =
                                  s"${req.theProtocol}://${req.theHost}/.well-known/otoroshi/login?sessionId=${user.randomId}&redirectTo=urn:ietf:wg:oauth:2.0:oob&host=${req.theHost}&cp=${auth
                                    .cookieSuffix(descriptor)}&ma=${auth.sessionMaxAge}&httpOnly=${auth.sessionCookieValues.httpOnly}&secure=${auth.sessionCookieValues.secure}${secStr}"
                                val hash        = env.sign(redirection)
                                FastFuture.successful(
                                  Redirect(s"$redirection&hash=$hash")
                                    .removingFromSession(
                                      s"pa-redirect-after-login-${auth.cookieSuffix(descriptor)}",
                                      "desc"
                                    )
                                    .withCookies(
                                      env.createPrivateSessionCookies(
                                        req.theHost,
                                        user.randomId,
                                        descriptor,
                                        auth,
                                        user.some
                                      ): _*
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
                                        .cookieSuffix(descriptor)}&ma=${auth.sessionMaxAge}&httpOnly=${auth.sessionCookieValues.httpOnly}&secure=${auth.sessionCookieValues.secure}${secStr}"
                                    val hash        = env.sign(redirection)
                                    s"$redirection&hash=$hash"
                                  case port =>
                                    val redirection =
                                      s"$scheme://$host:$port/.well-known/otoroshi/login?sessionId=${user.randomId}&redirectTo=$redirectTo&host=$host&cp=${auth
                                        .cookieSuffix(descriptor)}&ma=${auth.sessionMaxAge}&httpOnly=${auth.sessionCookieValues.httpOnly}&secure=${auth.sessionCookieValues.secure}${secStr}"
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
                                      env.createPrivateSessionCookies(
                                        host,
                                        user.randomId,
                                        descriptor,
                                        auth,
                                        user.some
                                      ): _*
                                    )
                                )
                              }
                            }
                        }
                      }
                    }
                  }
                  case None         =>
                    auth.authModule(ctx.globalConfig).paLoginPage(req, ctx.globalConfig, descriptor, false)
                }
              }
            }
            case _                                                                                         => NotFound(otoroshi.views.html.oto.error("Private apps are not configured", env)).vfuture
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

      implicit val req = ctx.request

      def saveUser(user: PrivateAppsUser, auth: AuthModuleConfig, descriptor: ServiceDescriptor, webauthn: Boolean)(
          implicit req: RequestHeader
      ): Future[Result] = {
        user
          .save(Duration(auth.sessionMaxAge, TimeUnit.SECONDS))
          .map { paUser =>
            val sec    = computeSec(paUser)
            val secStr = if (auth.clientSideSessionEnabled) s"&sec=${sec}" else ""
            if (logger.isDebugEnabled) logger.debug(s"Auth callback, creating session on the leader ${paUser.email}")
            env.clusterAgent.createSession(paUser)
            Alerts.send(
              UserLoggedInAlert(env.snowflakeGenerator.nextIdStr(), env.env, paUser, ctx.from, ctx.ua, auth.id)
            )

            ctx.request.body.asFormUrlEncoded match {
              case Some(body) if body.get("RelayState").exists(_.nonEmpty) =>
                val queryParams =
                  body("RelayState").head.split("&").map { qParam => (qParam.split("=")(0), qParam.split("=")(1)) }
                val params      = queryParams.groupBy(_._1).mapValues(_.map(_._2).head)

                val redirectTo = params.getOrElse(
                  "redirect_uri",
                  routes.PrivateAppsController.home.absoluteURL(env.exposedRootSchemeIsHttps)
                )

                val url  = new java.net.URL(redirectTo)
                val host = url.getHost

                Redirect(redirectTo)
                  .removingFromSession(s"pa-redirect-after-login-${auth.cookieSuffix(descriptor)}", "desc")
                  .withCookies(
                    env.createPrivateSessionCookies(host, paUser.randomId, descriptor, auth, paUser.some): _*
                  )

              case _ =>
                ctx.request.session
                  .get(s"pa-redirect-after-login-${auth.cookieSuffix(descriptor)}")
                  .getOrElse(
                    routes.PrivateAppsController.home.absoluteURL(env.exposedRootSchemeIsHttps)
                  ) match {
                  case "urn:ietf:wg:oauth:2.0:oob" =>
                    val redirection =
                      s"${req.theProtocol}://${req.theHost}/.well-known/otoroshi/login?sessionId=${paUser.randomId}&redirectTo=urn:ietf:wg:oauth:2.0:oob&host=${req.theHost}&cp=${auth
                        .cookieSuffix(descriptor)}&ma=${auth.sessionMaxAge}&httpOnly=${auth.sessionCookieValues.httpOnly}&secure=${auth.sessionCookieValues.secure}${secStr}"
                    val hash        = env.sign(redirection)
                    Redirect(
                      s"$redirection&hash=$hash"
                    ).removingFromSession(s"pa-redirect-after-login-${auth.cookieSuffix(descriptor)}", "desc")
                      .withCookies(
                        env.createPrivateSessionCookies(req.theHost, user.randomId, descriptor, auth, user.some): _*
                      )
                  case redirectTo                  =>
                    val url                = new java.net.URL(redirectTo)
                    val host               = url.getHost
                    val scheme             = url.getProtocol
                    val setCookiesRedirect = url.getPort match {
                      case -1   =>
                        val redirection =
                          s"$scheme://$host/.well-known/otoroshi/login?sessionId=${paUser.randomId}&redirectTo=$redirectTo&host=$host&cp=${auth
                            .cookieSuffix(descriptor)}&ma=${auth.sessionMaxAge}&httpOnly=${auth.sessionCookieValues.httpOnly}&secure=${auth.sessionCookieValues.secure}${secStr}"
                        val hash        = env.sign(redirection)
                        s"$redirection&hash=$hash"
                      case port =>
                        val redirection =
                          s"$scheme://$host:$port/.well-known/otoroshi/login?sessionId=${paUser.randomId}&redirectTo=$redirectTo&host=$host&cp=${auth
                            .cookieSuffix(descriptor)}&ma=${auth.sessionMaxAge}&httpOnly=${auth.sessionCookieValues.httpOnly}&secure=${auth.sessionCookieValues.secure}${secStr}"
                        val hash        = env.sign(redirection)
                        s"$redirection&hash=$hash"
                    }
                    if (webauthn) {
                      Ok(Json.obj("location" -> setCookiesRedirect))
                        .removingFromSession(s"pa-redirect-after-login-${auth.cookieSuffix(descriptor)}", "desc")
                        .withCookies(
                          env.createPrivateSessionCookies(host, paUser.randomId, descriptor, auth, paUser.some): _*
                        )
                    } else {
                      Redirect(setCookiesRedirect)
                        .removingFromSession(s"pa-redirect-after-login-${auth.cookieSuffix(descriptor)}", "desc")
                        .withCookies(
                          env.createPrivateSessionCookies(host, paUser.randomId, descriptor, auth, paUser.some): _*
                        )
                    }
                }
            }
          }
      }

      var desc = ctx.request.getQueryString("desc")
        .orElse(ctx.request.session.get("desc"))

      var isRoute = ctx.request.getQueryString("route")
        .orElse(ctx.request.session.get("route"))
        .contains("true")

      var refFromRelayState: Option[String] = ctx.request.session.get("ref")

      ctx.request.body.asFormUrlEncoded match {
        case Some(body) =>
          if (body.get("RelayState").exists(_.nonEmpty)) {
            val queryParams =
              body("RelayState").head.split("&").map { qParam => (qParam.split("=")(0), qParam.split("=")(1)) }
            val params      = queryParams.groupBy(_._1).mapValues(_.map(_._2).head)

            if (!params.contains("hash") || !params.contains("redirect_uri") || !params.contains("desc"))
              NotFound(otoroshi.views.html.oto.error("Service not found", env)).vfuture
            else {
              desc = params("desc").some
              refFromRelayState = params.get("ref")
              if (!isRoute)
                isRoute = params.get("route").forall(_ == "true")
            }
          }
        case None       =>
      }

      def processService(serviceId: String) = {
        if (logger.isDebugEnabled) logger.debug(s"redirect to service descriptor : $serviceId")
        env.datastores.serviceDescriptorDataStore.findOrRouteById(serviceId).flatMap {
          case None                                                                                      => NotFound(otoroshi.views.html.oto.error("Service not found", env)).vfuture
          case Some(descriptor) if !descriptor.privateApp                                                =>
            NotFound(otoroshi.views.html.oto.error("Private apps are not configured", env)).vfuture
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
                        case Left(error) => BadRequest(Json.obj("error" -> error)).vfuture
                        case Right(user) => saveUser(user, auth, descriptor, true)(ctx.request)
                      }
                    }
                    case _              =>
                      BadRequest(
                        otoroshi.views.html.oto
                          .error(message = s"Missing step", _env = env, title = "Authorization error")
                      ).vfuture
                  }
                }
                case auth                                                                                => {
                  auth
                    .authModule(ctx.globalConfig)
                    .paCallback(ctx.request, ctx.globalConfig, descriptor, false)
                    .flatMap {
                      case Left(error) => {
                        BadRequest(
                          otoroshi.views.html.oto
                            .error(
                              message = s"You're not authorized here: ${error}",
                              _env = env,
                              title = "Authorization error"
                            )
                        ).vfuture
                      }
                      case Right(user) => saveUser(user, auth, descriptor, false)(ctx.request)
                    }
                }
              }
            }
          }
          case _                                                                                         => NotFound(otoroshi.views.html.oto.error("Private apps are not configured", env)).vfuture
        }
      }

      def processRoute(routeId: String) = {
        if (logger.isDebugEnabled) logger.debug(s"redirect to route : $routeId")
        env.proxyState.route(routeId).vfuture.flatMap {
          case None => NotFound(otoroshi.views.html.oto.error("Route not found", env)).vfuture
          case Some(route) if !route.plugins.hasPlugin[MultiAuthModule] =>
            NotFound(otoroshi.views.html.oto.error("Private apps are not configured", env)).vfuture
          case Some(route) if route.plugins.hasPlugin[MultiAuthModule] && route.id != env.backOfficeDescriptor.id => {
            withMultiAuthConfig(route, ctx.request, refFromRelayState) { _auth =>
              verifyHash(route.id, _auth, ctx.request) {
                case auth if auth.`type` == "basic" && auth.asInstanceOf[BasicAuthModuleConfig].webauthn => {
                  val authModule = auth.authModule(ctx.globalConfig).asInstanceOf[BasicAuthModule]
                  req.headers.get("WebAuthn-Login-Step") match {
                    case Some("start") => {
                      authModule.webAuthnLoginStart(ctx.request.body.asJson.get, route.legacy).map {
                        case Left(error) => BadRequest(Json.obj("error" -> error))
                        case Right(reg) => Ok(reg)
                      }
                    }
                    case Some("finish") => {
                      authModule.webAuthnLoginFinish(ctx.request.body.asJson.get, route.legacy).flatMap {
                        case Left(error) => BadRequest(Json.obj("error" -> error)).vfuture
                        case Right(user) => saveUser(user, auth, route.legacy, true)(ctx.request)
                      }
                    }
                    case _ =>
                      BadRequest(
                        otoroshi.views.html.oto
                          .error(message = s"Missing step", _env = env, title = "Authorization error")
                      ).vfuture
                  }
                }
                case auth => {
                  auth
                    .authModule(ctx.globalConfig)
                    .paCallback(ctx.request, ctx.globalConfig, route.legacy, true)
                    .flatMap {
                      case Left(error) => {
                        BadRequest(
                          otoroshi.views.html.oto
                            .error(
                              message = s"You're not authorized here: ${error}",
                              _env = env,
                              title = "Authorization error"
                            )
                        ).vfuture
                      }
                      case Right(user) => saveUser(user, auth, route.legacy, false)(ctx.request)
                    }
                }
              }
            }
          }
          case _ => NotFound(otoroshi.views.html.oto.error("Private apps are not configured", env)).vfuture
        }
      }

      ((desc, ctx.request.getQueryString("state")) match {
        case (Some(serviceId), _) if !isRoute => processService(serviceId)
        case (Some(routeId), _) if isRoute => processRoute(routeId)
        case (_, Some(state))     =>
          if (logger.isDebugEnabled) logger.debug(s"Received state : $state")
          val unsignedState = decryptState(ctx.request.requestHeader)
          (unsignedState \ "descriptor").asOpt[String] match {
            case Some(route) if isRoute => processRoute(route)
            case Some(service) if !isRoute => processService(service)
            case _                => NotFound(otoroshi.views.html.oto.error(s"${if(isRoute) "Route" else "service"} not found", env)).vfuture
          }
        case (_, _)               => NotFound(otoroshi.views.html.oto.error(s"${if(isRoute) "Route" else "service"} not found", env)).vfuture
      })
        .recover {
          case t: Throwable => {
            val errorId = IdGenerator.uuid
            logger.error(s"An error occurred during the authentication callback with error id: '${errorId}'", t)
            InternalServerError(
              otoroshi.views.html.oto
                .error(
                  message =
                    s"An error occurred during the authentication callback. Please contact your administrator with error id: ${errorId}",
                  _env = env,
                  title = "Authorization error"
                )
            )
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
            case None        => FastFuture.successful(Redirect(otoroshi.controllers.routes.BackOfficeController.index))
            case Some(aconf) => {
              //env.datastores.authConfigsDataStore.findById(aconf).flatMap {
              env.proxyState.authModuleAsync(aconf).flatMap {
                case None        =>
                  FastFuture
                    .successful(NotFound(otoroshi.views.html.oto.error("BackOffice Oauth is not configured", env)))
                case Some(oauth) => oauth.authModule(config).boLoginPage(ctx.request, config)
              }
            }
          }
        }
        case config if config.u2fLoginOnly || config.backOfficeAuthRef.isEmpty    =>
          FastFuture.successful(Redirect(otoroshi.controllers.routes.BackOfficeController.index))
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
            val userRedirect = redirect.getOrElse(routes.BackOfficeController.index.url)
            Redirect(userRedirect).removingFromSession("bousr", "bo-redirect-after-login")
          }
        case false => {
          env.datastores.globalConfigDataStore.singleton().flatMap { config =>
            config.backOfficeAuthRef match {
              case None        => {
                ctx.user.delete().map { _ =>
                  Alerts
                    .send(AdminLoggedOutAlert(env.snowflakeGenerator.nextIdStr(), env.env, ctx.user, ctx.from, ctx.ua))
                  val userRedirect = redirect.getOrElse(routes.BackOfficeController.index.url)
                  Redirect(userRedirect).removingFromSession("bousr", "bo-redirect-after-login")
                }
              }
              case Some(aconf) => {
                //env.datastores.authConfigsDataStore.findById(aconf).flatMap {
                env.proxyState.authModuleAsync(aconf).flatMap {
                  case None        =>
                    FastFuture.successful(
                      NotFound(otoroshi.views.html.oto.error("BackOffice auth is not configured", env))
                        .removingFromSession("bousr", "bo-redirect-after-login")
                    )
                  case Some(oauth) =>
                    oauth.authModule(config).boLogout(ctx.request, ctx.user, config).flatMap {
                      case Left(body)   =>
                        ctx.user.delete().map { _ =>
                          Alerts.send(
                            AdminLoggedOutAlert(env.snowflakeGenerator.nextIdStr(), env.env, ctx.user, ctx.from, ctx.ua)
                          )
                          body.removingFromSession("bousr", "bo-redirect-after-login")
                        }
                      case Right(value) =>
                        value match {
                          case None            =>
                            ctx.user.delete().map { _ =>
                              Alerts.send(
                                AdminLoggedOutAlert(
                                  env.snowflakeGenerator.nextIdStr(),
                                  env.env,
                                  ctx.user,
                                  ctx.from,
                                  ctx.ua
                                )
                              )
                              val userRedirect = redirect.getOrElse(routes.BackOfficeController.index.url)
                              Redirect(userRedirect).removingFromSession("bousr", "bo-redirect-after-login")
                            }
                          case Some(logoutUrl) =>
                            val userRedirect      = redirect.getOrElse(s"${request.theProtocol}://${request.theHost}/")
                            val actualRedirectUrl =
                              logoutUrl.replace("${redirect}", URLEncoder.encode(userRedirect, "UTF-8"))
                            ctx.user.delete().map { _ =>
                              Alerts.send(
                                AdminLoggedOutAlert(
                                  env.snowflakeGenerator.nextIdStr(),
                                  env.env,
                                  ctx.user,
                                  ctx.from,
                                  ctx.ua
                                )
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
                  routes.BackOfficeController.index
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
              FastFuture.successful(Redirect(otoroshi.controllers.routes.BackOfficeController.index))
            case config if !(config.u2fLoginOnly || config.backOfficeAuthRef.isEmpty) => {

              config.backOfficeAuthRef match {
                case None                        =>
                  FastFuture
                    .successful(NotFound(otoroshi.views.html.oto.error("BackOffice OAuth is not configured", env)))
                case Some(backOfficeAuth0Config) => {
                  // env.datastores.authConfigsDataStore.findById(backOfficeAuth0Config).flatMap {
                  env.proxyState.authModuleAsync(backOfficeAuth0Config).flatMap {
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
                              if (logger.isDebugEnabled) logger.debug(s"Login successful for user '${user.email}'")
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
