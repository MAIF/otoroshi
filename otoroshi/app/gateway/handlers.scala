package gateway

import java.net.URLEncoder
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicLong}

import akka.actor.{Actor, Props}
import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.util.FastFuture._
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import auth.AuthModuleConfig
import com.auth0.jwt.JWT
import com.google.common.base.Charsets
import env.{Env, SidecarConfig}
import events._
import models._
import org.joda.time.DateTime
import otoroshi.el.{HeadersExpressionLanguage, TargetExpressionLanguage}
import otoroshi.script.Implicits._
import otoroshi.script._
import otoroshi.utils.LetsEncryptHelper
import play.api.Logger
import play.api.http.{Status => _, _}
import play.api.libs.json._
import play.api.libs.streams.Accumulator
import play.api.libs.ws.{DefaultWSCookie, EmptyBody, SourceBody}
import play.api.mvc.Results._
import play.api.mvc._
import play.api.routing.Router
import security.{IdGenerator, OtoroshiClaim}
import ssl.{SSLSessionJavaHelper, X509KeyManagerSnitch}
import utils.RequestImplicits._
import utils._
import utils.http.Implicits._

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NoStackTrace
import scala.util.{Failure, Success, Try}

case class ProxyDone(status: Int, isChunked: Boolean, upstreamLatency: Long, headersOut: Seq[Header])

class ErrorHandler()(implicit env: Env) extends HttpErrorHandler {

  implicit val ec = env.otoroshiExecutionContext

  lazy val logger = Logger("otoroshi-error-handler")

  def onClientError(request: RequestHeader, statusCode: Int, mess: String): Future[Result] = {
    val message       = Option(mess).filterNot(_.trim.isEmpty).getOrElse("An error occurred")
    val remoteAddress = request.theIpAddress
    logger.error(
      s"Client Error: $message from ${remoteAddress} on ${request.method} ${request.theProtocol}://${request.theHost}${request.relativeUri} ($statusCode) - ${request.headers.toSimpleMap
        .mkString(";")}"
    )
    env.metrics.counter("errors.client").inc()
    env.datastores.globalConfigDataStore.singleton().map { config =>
      env.datastores.serviceDescriptorDataStore.updateMetricsOnError(config)
    }
    val snowflake = env.snowflakeGenerator.nextIdStr()
    val attrs     = TypedMap.empty
    RequestSink.maybeSinkRequest(
      snowflake,
      request,
      attrs,
      RequestOrigin.ErrorHandler,
      statusCode,
      message,
      Errors.craftResponseResult(
        s"Client Error: an error occurred on ${request.relativeUri} ($statusCode)",
        Status(statusCode),
        request,
        None,
        Some("errors.client.error"),
        attrs = TypedMap.empty
      )
    )
  }

  def onServerError(request: RequestHeader, exception: Throwable): Future[Result] = {
    // exception.printStackTrace()
    val remoteAddress = request.theIpAddress
    logger.error(
      s"Server Error ${exception.getMessage} from ${remoteAddress} on ${request.method} ${request.theProtocol}://${request.theHost}${request.relativeUri} - ${request.headers.toSimpleMap
        .mkString(";")}",
      exception
    )
    env.metrics.counter("errors.server").inc()
    env.datastores.globalConfigDataStore.singleton().map { config =>
      env.datastores.serviceDescriptorDataStore.updateMetricsOnError(config)
    }
    val snowflake = env.snowflakeGenerator.nextIdStr()
    val attrs     = TypedMap.empty
    RequestSink.maybeSinkRequest(
      snowflake,
      request,
      attrs,
      RequestOrigin.ErrorHandler,
      500,
      Option(exception).flatMap(e => Option(e.getMessage)).getOrElse("An error occurred ..."),
      Errors.craftResponseResult("An error occurred ...",
                                 InternalServerError,
                                 request,
                                 None,
                                 Some("errors.server.error"),
                                 attrs = TypedMap.empty)
    )
  }
}

object SameThreadExecutionContext extends ExecutionContext {
  override def reportFailure(t: Throwable): Unit =
    throw new IllegalStateException("exception in SameThreadExecutionContext", t) with NoStackTrace
  override def execute(runnable: Runnable): Unit = runnable.run()
}

case class AnalyticsQueueEvent(descriptor: ServiceDescriptor,
                               callDuration: Long,
                               callOverhead: Long,
                               dataIn: Long,
                               dataOut: Long,
                               upstreamLatency: Long,
                               config: models.GlobalConfig)

object AnalyticsQueue {
  def props(env: Env) = Props(new AnalyticsQueue(env))
}

class AnalyticsQueue(env: Env) extends Actor {
  override def receive: Receive = {
    case AnalyticsQueueEvent(descriptor, duration, overhead, dataIn, dataOut, upstreamLatency, config) => {
      descriptor
        .updateMetrics(duration, overhead, dataIn, dataOut, upstreamLatency, config)(context.dispatcher, env)
      env.datastores.globalConfigDataStore.updateQuotas(config)(context.dispatcher, env)
    }
  }
}

class GatewayRequestHandler(snowMonkey: SnowMonkey,
                            webSocketHandler: WebSocketHandler,
                            router: Router,
                            errorHandler: HttpErrorHandler,
                            configuration: HttpConfiguration,
                            filters: HttpFilters,
                            actionBuilder: ActionBuilder[Request, AnyContent])(implicit env: Env, mat: Materializer)
    extends DefaultHttpRequestHandler(router, errorHandler, configuration, filters) {

  implicit lazy val ec        = env.otoroshiExecutionContext
  implicit lazy val scheduler = env.otoroshiScheduler

  lazy val logger      = Logger("otoroshi-http-handler")
  lazy val debugLogger = Logger("otoroshi-http-handler-debug")

  lazy val analyticsQueue = env.otoroshiActorSystem.actorOf(AnalyticsQueue.props(env))

  lazy val ipRegex = RegexPool.regex(
    "^(([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\.){3}([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])(:\\d{2,5})?$"
  )
  lazy val monitoringPaths = Seq("/health", "/metrics")

  val sourceBodyParser = BodyParser("Gateway BodyParser") { _ =>
    Accumulator.source[ByteString].map(Right.apply)
  }

  val reqCounter = new AtomicInteger(0)

  val headersInFiltered = Seq(
    env.Headers.OtoroshiState,
    env.Headers.OtoroshiClaim,
    env.Headers.OtoroshiRequestId,
    env.Headers.OtoroshiClientId,
    env.Headers.OtoroshiClientSecret,
    env.Headers.OtoroshiAuthorization,
    "Host",
    "X-Forwarded-For",
    "X-Forwarded-Proto",
    "X-Forwarded-Protocol",
    "Raw-Request-Uri",
    "Remote-Address",
    "Timeout-Access",
    "Tls-Session-Info",
  ).map(_.toLowerCase)

  val headersOutFiltered = Seq(
    env.Headers.OtoroshiStateResp,
    "Transfer-Encoding",
    "Content-Length",
    "Raw-Request-Uri",
    "Remote-Address",
    "Timeout-Access",
    "Tls-Session-Info",
  ).map(_.toLowerCase)

  // TODO : very dirty ... fix it using Play 2.6 request.hasBody
  // def hasBody(request: Request[_]): Boolean = request.hasBody
  def hasBody(request: Request[_]): Boolean =
    (request.method, request.headers.get("Content-Length")) match {
      case ("GET", Some(_))    => true
      case ("GET", None)       => false
      case ("HEAD", Some(_))   => true
      case ("HEAD", None)      => false
      case ("PATCH", _)        => true
      case ("POST", _)         => true
      case ("PUT", _)          => true
      case ("DELETE", Some(_)) => true
      case ("DELETE", None)    => false
      case _                   => true
    }

  def matchRedirection(host: String): Boolean =
    env.redirections.nonEmpty && env.redirections.exists(it => host.contains(it))

  def badCertReply(request: RequestHeader) = actionBuilder.async { req =>
    Errors.craftResponseResult(
      "No SSL/TLS certificate found for the current domain name. Connection refused !",
      NotFound,
      req,
      None,
      Some("errors.ssl.nocert"),
      attrs = TypedMap.empty
    )
  }

  override def routeRequest(request: RequestHeader): Option[Handler] = {
    val config = env.datastores.globalConfigDataStore.latestSafe
    if (request.theSecured && config.isDefined && config.get.autoCert.enabled) { // && config.get.autoCert.replyNicely) { // to avoid cache effet
      request.headers.get("Tls-Session-Info").flatMap(SSLSessionJavaHelper.computeKey) match {
        case Some(key) => {
          Option(X509KeyManagerSnitch.sslSessions.getIfPresent(key)) match {
            case Some((_, _, chain))
                if chain.headOption.exists(_.getSubjectDN.getName.contains(SSLSessionJavaHelper.NotAllowed)) =>
              Some(badCertReply(request))
            case a => internalRouteRequest(request)
          }
        }
        case _ => Some(badCertReply(request)) // TODO: is it accurate ?
      }
    } else {
      internalRouteRequest(request)
    }
  }

  def internalRouteRequest(request: RequestHeader): Option[Handler] = {
    if (env.globalMaintenanceMode) {
      if (request.relativeUri.contains("__otoroshi_assets")) {
        super.routeRequest(request)
      } else {
        Some(globalMaintenanceMode(TypedMap.empty))
      }
    } else {
      val isSecured    = request.theSecured
      val protocol     = request.theProtocol
      lazy val url     = ByteString(s"$protocol://${request.theHost}${request.relativeUri}")
      lazy val cookies = request.cookies.map(_.value).map(ByteString.apply)
      lazy val headers = request.headers.toSimpleMap.map(t => (ByteString.apply(t._1), ByteString.apply(t._2)))
      // logger.trace(s"[SIZE] url: ${url.size} bytes, cookies: ${cookies.map(_.size).mkString(", ")}, headers: ${headers.map(_.size).mkString(", ")}")
      if (env.clusterConfig.mode == cluster.ClusterMode.Worker && env.clusterAgent.cannotServeRequests()) {
        Some(clusterError("Waiting for first Otoroshi leader sync."))
      } else if (env.validateRequests && url.size > env.maxUrlLength) {
        Some(tooBig("URL should be smaller", UriTooLong))
      } else if (env.validateRequests && cookies.exists(_.size > env.maxCookieLength)) {
        Some(tooBig("Cookies should be smaller"))
      } else if (env.validateRequests && headers.exists(
                   t => t._1.size > env.maxHeaderNameLength || t._2.size > env.maxHeaderValueLength
                 )) {
        Some(tooBig(s"Headers should be smaller"))
      } else {
        val toHttps    = env.exposedRootSchemeIsHttps
        val host       = request.theDomain // if (request.host.contains(":")) request.host.split(":")(0) else request.host
        val monitoring = monitoringPaths.exists(p => request.relativeUri.startsWith(p))
        host match {
          case str if matchRedirection(str)                                          => Some(redirectToMainDomain())
          case _ if ipRegex.matches(request.theHost) && monitoring                   => super.routeRequest(request)
          case _ if request.relativeUri.contains("__otoroshi_assets")                => super.routeRequest(request)
          case _ if request.relativeUri.startsWith("/__otoroshi_private_apps_login") => Some(setPrivateAppsCookies())
          case _ if request.relativeUri.startsWith("/__otoroshi_private_apps_logout") =>
            Some(removePrivateAppsCookies())
          case _ if request.relativeUri.startsWith("/.well-known/otoroshi/login")  => Some(setPrivateAppsCookies())
          case _ if request.relativeUri.startsWith("/.well-known/otoroshi/logout") => Some(removePrivateAppsCookies())
          case _ if request.relativeUri.startsWith("/.well-known/otoroshi/me")     => Some(myProfile())
          case _ if request.relativeUri.startsWith("/.well-known/acme-challenge/") => Some(letsEncrypt())
          case env.backOfficeHost if !isSecured && toHttps                         => Some(redirectToHttps())
          case env.privateAppsHost if !isSecured && toHttps                        => Some(redirectToHttps())
          case env.backOfficeHost if monitoring                                    => Some(forbidden())
          case env.privateAppsHost if monitoring                                   => Some(forbidden())
          case env.adminApiHost if monitoring                                      => super.routeRequest(request)
          case env.adminApiHost if env.exposeAdminApi                              => super.routeRequest(request)
          case env.backOfficeHost if env.exposeAdminDashboard                      => super.routeRequest(request)
          case env.privateAppsHost                                                 => super.routeRequest(request)
          case _ =>
            request.headers.get("Sec-WebSocket-Version") match {
              case None    => Some(forwardCall())
              case Some(_) => Some(webSocketHandler.proxyWebSocket())
            }
        }
      }
    }
  }

  def letsEncrypt() = actionBuilder.async { req =>
    if (!req.theSecured) {
      env.datastores.globalConfigDataStore.latestSafe match {
        case None => FastFuture.successful(InternalServerError(Json.obj("error" -> "no config found !")))
        case Some(config) if !config.letsEncryptSettings.enabled =>
          FastFuture.successful(InternalServerError(Json.obj("error" -> "config disabled !")))
        case Some(config) => {
          val domain = req.theDomain
          val token  = req.relativeUri.split("\\?").head.replace("/.well-known/acme-challenge/", "")
          LetsEncryptHelper.getChallengeForToken(domain, token).map {
            case None       => NotFound(Json.obj("error" -> "token not found !"))
            case Some(body) => Ok(body.utf8String).as("text/plain")
          }
        }
      }
    } else {
      FastFuture.successful(InternalServerError(Json.obj("error" -> "no config found !")))
    }
  }

  def setPrivateAppsCookies() = actionBuilder.async { req =>
    val redirectToOpt: Option[String] = req.queryString.get("redirectTo").map(_.last)
    val sessionIdOpt: Option[String]  = req.queryString.get("sessionId").map(_.last)
    val hostOpt: Option[String]       = req.queryString.get("host").map(_.last)
    val cookiePrefOpt: Option[String] = req.queryString.get("cp").map(_.last)
    val maOpt: Option[Int]            = req.queryString.get("ma").map(_.last).map(_.toInt)
    (redirectToOpt, sessionIdOpt, hostOpt, cookiePrefOpt, maOpt) match {
      case (Some("urn:ietf:wg:oauth:2.0:oob"), Some(sessionId), Some(host), Some(cp), ma) =>
        FastFuture.successful(
          Ok(views.html.otoroshi.token(env.signPrivateSessionId(sessionId), env)).withCookies(
            env.createPrivateSessionCookiesWithSuffix(host, sessionId, cp, ma.getOrElse(86400)): _*
          )
        )
      case (Some(redirectTo), Some(sessionId), Some(host), Some(cp), ma) =>
        FastFuture.successful(
          Redirect(redirectTo).withCookies(
            env.createPrivateSessionCookiesWithSuffix(host, sessionId, cp, ma.getOrElse(86400)): _*
          )
        )
      case _ =>
        Errors.craftResponseResult("Missing parameters",
                                   BadRequest,
                                   req,
                                   None,
                                   Some("errors.missing.parameters"),
                                   attrs = TypedMap.empty)
    }
  }

  def withAuthConfig(descriptor: ServiceDescriptor, req: RequestHeader, attrs: TypedMap)(
      f: AuthModuleConfig => Future[Result]
  ): Future[Result] = {
    descriptor.authConfigRef match {
      case None =>
        Errors.craftResponseResult(
          "Auth. config. ref not found on the descriptor",
          Results.InternalServerError,
          req,
          Some(descriptor),
          Some("errors.auth.config.ref.not.found"),
          attrs = attrs
        )
      case Some(ref) => {
        env.datastores.authConfigsDataStore.findById(ref).flatMap {
          case None =>
            Errors.craftResponseResult(
              "Auth. config. not found on the descriptor",
              Results.InternalServerError,
              req,
              Some(descriptor),
              Some("errors.auth.config.not.found"),
              attrs = attrs
            )
          case Some(auth) => f(auth)
        }
      }
    }
  }

  def myProfile() = actionBuilder.async { req =>
    implicit val request = req

    val attrs = utils.TypedMap.empty

    env.datastores.globalConfigDataStore.singleton().flatMap { globalConfig =>
      ServiceLocation(req.theHost, globalConfig) match {
        case None => {
          Errors.craftResponseResult(s"Service not found",
                                     NotFound,
                                     req,
                                     None,
                                     Some("errors.service.not.found"),
                                     attrs = attrs)
        }
        case Some(ServiceLocation(domain, serviceEnv, subdomain)) => {
          env.datastores.serviceDescriptorDataStore
            .find(ServiceDescriptorQuery(subdomain, serviceEnv, domain, req.relativeUri, req.headers.toSimpleMap),
                  req,
                  attrs)
            .flatMap {
              case None => {
                Errors.craftResponseResult(s"Service not found",
                                           NotFound,
                                           req,
                                           None,
                                           Some("errors.service.not.found"),
                                           attrs = attrs)
              }
              case Some(desc) if !desc.enabled => {
                Errors.craftResponseResult(s"Service not found",
                                           NotFound,
                                           req,
                                           None,
                                           Some("errors.service.not.found"),
                                           attrs = attrs)
              }
              // case Some(descriptor) if !descriptor.privateApp => {
              //   Errors.craftResponseResult(s"Service not found", NotFound, req, None, Some("errors.service.not.found"))
              // }
              case Some(descriptor)
                  if !descriptor.privateApp && descriptor.id != env.backOfficeDescriptor.id && descriptor
                    .isUriPublic(req.path) => {
                // Public service, no profile but no error either ???
                FastFuture.successful(Ok(Json.obj("access_type" -> "public")))
              }
              case Some(descriptor)
                  if !descriptor.privateApp && descriptor.id != env.backOfficeDescriptor.id && !descriptor
                    .isUriPublic(req.path) => {
                // ApiKey
                ApiKeyHelper.extractApiKey(req, descriptor, attrs).flatMap {
                  case None =>
                    Errors
                      .craftResponseResult(s"Invalid API key",
                                           Unauthorized,
                                           req,
                                           None,
                                           Some("errors.invalid.api.key"),
                                           attrs = attrs)
                  case Some(apiKey) =>
                    FastFuture.successful(Ok(apiKey.lightJson ++ Json.obj("access_type" -> "apikey")))
                }
              }
              case Some(descriptor) if descriptor.privateApp && descriptor.id != env.backOfficeDescriptor.id => {
                withAuthConfig(descriptor, req, attrs) { auth =>
                  PrivateAppsUserHelper.isPrivateAppsSessionValid(req, descriptor, attrs).flatMap {
                    case None =>
                      Errors.craftResponseResult(s"Invalid session",
                                                 Unauthorized,
                                                 req,
                                                 None,
                                                 Some("errors.invalid.session"),
                                                 attrs = attrs)
                    case Some(session) =>
                      FastFuture.successful(Ok(session.profile.as[JsObject] ++ Json.obj("access_type" -> "session")))
                  }
                }
              }
              case _ => {
                Errors.craftResponseResult(s"Unauthorized",
                                           Unauthorized,
                                           req,
                                           None,
                                           Some("errors.unauthorized"),
                                           attrs = attrs)
              }
            }
        }
      }
    }
  }

  def removePrivateAppsCookies() = actionBuilder.async { req =>
    implicit val request = req

    val attrs = TypedMap.empty

    env.datastores.globalConfigDataStore.singleton().flatMap { globalConfig =>
      ServiceLocation(req.theHost, globalConfig) match {
        case None => {
          Errors.craftResponseResult(s"Service not found for URL ${req.theHost}::${req.relativeUri}",
                                     NotFound,
                                     req,
                                     None,
                                     Some("errors.service.not.found"),
                                     attrs = attrs)
        }
        case Some(ServiceLocation(domain, serviceEnv, subdomain)) => {
          env.datastores.serviceDescriptorDataStore
            .find(ServiceDescriptorQuery(subdomain, serviceEnv, domain, req.relativeUri, req.headers.toSimpleMap),
                  req,
                  attrs)
            .flatMap {
              case None => {
                Errors.craftResponseResult(s"Service not found",
                                           NotFound,
                                           req,
                                           None,
                                           Some("errors.service.not.found"),
                                           attrs = attrs)
              }
              case Some(desc) if !desc.enabled => {
                Errors.craftResponseResult(s"Service not found",
                                           NotFound,
                                           req,
                                           None,
                                           Some("errors.service.not.found"),
                                           attrs = attrs)
              }
              case Some(descriptor) if !descriptor.privateApp => {
                Errors.craftResponseResult(s"Private apps are not configured",
                                           InternalServerError,
                                           req,
                                           None,
                                           Some("errors.service.auth.not.configured"),
                                           attrs = attrs)
              }
              case Some(descriptor) if descriptor.privateApp && descriptor.id != env.backOfficeDescriptor.id => {
                withAuthConfig(descriptor, req, attrs) { auth =>
                  auth.authModule(globalConfig).paLogout(req, globalConfig, descriptor).map {
                    case None => {
                      val cookieOpt = request.cookies.find(c => c.name.startsWith("oto-papps-"))
                      cookieOpt.flatMap(env.extractPrivateSessionId).map { id =>
                        env.datastores.privateAppsUserDataStore.findById(id).map(_.foreach(_.delete()))
                      }
                      val finalRedirect = req.getQueryString("redirect").getOrElse(s"http://${req.theHost}")
                      val redirectTo = env.rootScheme + env.privateAppsHost + env.privateAppsPort
                        .map(a => s":$a")
                        .getOrElse("") + controllers.routes.AuthController
                        .confidentialAppLogout()
                        .url + s"?redirectTo=${finalRedirect}&host=${req.theHost}&cp=${auth.cookieSuffix(descriptor)}"
                      logger.trace("should redirect to " + redirectTo)
                      Redirect(redirectTo)
                        .discardingCookies(env.removePrivateSessionCookies(req.theHost, descriptor, auth): _*)
                    }
                    case Some(logoutUrl) => {
                      val cookieOpt = request.cookies.find(c => c.name.startsWith("oto-papps-"))
                      cookieOpt.flatMap(env.extractPrivateSessionId).map { id =>
                        env.datastores.privateAppsUserDataStore.findById(id).map(_.foreach(_.delete()))
                      }
                      val finalRedirect = req.getQueryString("redirect").getOrElse(s"http://${req.theHost}")
                      val redirectTo = env.rootScheme + env.privateAppsHost + env.privateAppsPort
                        .map(a => s":$a")
                        .getOrElse("") + controllers.routes.AuthController
                        .confidentialAppLogout()
                        .url + s"?redirectTo=${finalRedirect}&host=${req.theHost}&cp=${auth.cookieSuffix(descriptor)}"
                      val actualRedirectUrl = logoutUrl.replace("${redirect}", URLEncoder.encode(redirectTo, "UTF-8"))
                      logger.trace("should redirect to " + actualRedirectUrl)
                      Redirect(actualRedirectUrl)
                        .discardingCookies(env.removePrivateSessionCookies(req.theHost, descriptor, auth): _*)
                    }
                  }
                }
              }
              case _ => {
                Errors.craftResponseResult(s"Private apps are not configured",
                                           InternalServerError,
                                           req,
                                           None,
                                           Some("errors.service.auth.not.configured"),
                                           attrs = attrs)
              }
            }
        }
      }
    }
  }

  def clusterError(message: String) = actionBuilder.async { req =>
    Errors.craftResponseResult(message,
                               InternalServerError,
                               req,
                               None,
                               Some("errors.no.cluster.state.yet"),
                               attrs = TypedMap.empty)
  }

  def tooBig(message: String, status: Status = BadRequest) = actionBuilder.async { req =>
    Errors.craftResponseResult(message, BadRequest, req, None, Some("errors.entity.too.big"), attrs = TypedMap.empty)
  }

  def globalMaintenanceMode(attrs: TypedMap) = actionBuilder.async { req =>
    Errors.craftResponseResult(
      "Service in maintenance mode",
      ServiceUnavailable,
      req,
      None,
      Some("errors.service.in.maintenance"),
      attrs = attrs
    )
  }

  def passWithTcpUdpTunneling(req: RequestHeader, desc: ServiceDescriptor, attrs: TypedMap)(
      f: => Future[Result]
  ): Future[Result] = {
    if (desc.isPrivate) {
      PrivateAppsUserHelper.isPrivateAppsSessionValid(req, desc, attrs).flatMap {
        case None => f
        case Some(user) => {
          if (desc.tcpUdpTunneling) {
            req.getQueryString("redirect") match {
              case Some("urn:ietf:wg:oauth:2.0:oob") =>
                FastFuture.successful(Ok(views.html.otoroshi.token(env.signPrivateSessionId(user.randomId), env)))
              case _ =>
                Errors
                  .craftResponseResult(s"Resource not found",
                                       NotFound,
                                       req,
                                       None,
                                       Some("errors.resource.not.found"),
                                       attrs = attrs)
            }
          } else {
            f
          }
        }
      }
    } else {
      if (desc.tcpUdpTunneling) {
        Errors
          .craftResponseResult(s"Resource not found",
                               NotFound,
                               req,
                               None,
                               Some("errors.resource.not.found"),
                               attrs = attrs)
      } else {
        f
      }
    }
  }

  def forbidden() = actionBuilder { req =>
    Forbidden(Json.obj("error" -> "forbidden"))
  }

  def redirectToHttps() = actionBuilder { req =>
    val domain   = req.theDomain
    val protocol = req.theProtocol
    logger.trace(
      s"redirectToHttps from ${protocol}://$domain${req.relativeUri} to ${env.rootScheme}$domain${req.relativeUri}"
    )
    Redirect(s"${env.rootScheme}$domain${req.relativeUri}").withHeaders("otoroshi-redirect-to" -> "https")
  }

  def redirectToMainDomain() = actionBuilder { req =>
    val domain: String = env.redirections.foldLeft(req.theDomain)((domain, item) => domain.replace(item, env.domain))
    val protocol       = req.theProtocol
    logger.debug(
      s"redirectToMainDomain from $protocol://${req.theDomain}${req.relativeUri} to $protocol://$domain${req.relativeUri}"
    )
    Redirect(s"$protocol://$domain${req.relativeUri}")
  }

  def splitToCanary(desc: ServiceDescriptor, trackingId: String, reqNumber: Int, config: GlobalConfig)(
      implicit env: Env
  ): Future[ServiceDescriptor] = {
    if (desc.canary.enabled) {
      env.datastores.canaryDataStore.isCanary(desc.id, trackingId, desc.canary.traffic, reqNumber, config).fast.map {
        case false => desc
        case true  => desc.copy(targets = desc.canary.targets, root = desc.canary.root)
      }
    } else {
      FastFuture.successful(desc)
    }
  }

  def applyJwtVerifier(
      service: ServiceDescriptor,
      req: RequestHeader,
      apiKey: Option[ApiKey],
      paUsr: Option[PrivateAppsUser],
      elContext: Map[String, String],
      attrs: TypedMap,
  )(f: JwtInjection => Future[Result])(implicit env: Env): Future[Result] = {
    if (service.jwtVerifier.enabled) {
      service.jwtVerifier.shouldBeVerified(req.path).flatMap {
        case false => f(JwtInjection())
        case true => {
          logger.debug(s"Applying JWT verification for service ${service.id}:${service.name}")
          service.jwtVerifier.verify(req, service, apiKey, paUsr, elContext, attrs)(f)
        }
      }
    } else {
      f(JwtInjection())
    }
  }

  def applySidecar(service: ServiceDescriptor, remoteAddress: String, req: RequestHeader, attrs: TypedMap)(
      f: ServiceDescriptor => Future[Result]
  )(implicit env: Env): Future[Result] = {
    def chooseRemoteAddress(config: SidecarConfig) =
      if (config.strict) req.headers.get("Remote-Address").map(add => add.split(":")(0)).getOrElse(remoteAddress)
      else remoteAddress
    env.sidecarConfig match {
      case _ if service.id == env.backOfficeDescriptor.id => f(service)
      // when outside container wants to access oustide services through otoroshi
      case Some(config) if chooseRemoteAddress(config) != config.from && config.serviceId != service.id =>
        logger.debug(
          s"Outside container (${chooseRemoteAddress(config)}) wants to access oustide service (${service.id}) through otoroshi"
        )
        Errors.craftResponseResult(
          "sidecar.bad.request.origin",
          Results.BadGateway,
          req,
          Some(service),
          None,
          attrs = attrs
        )
      // when local service wants to access protected services from other containers
      case Some(config @ SidecarConfig(_, _, _, Some(akid), strict))
          if chooseRemoteAddress(config) == config.from && config.serviceId != service.id => {
        logger.debug(
          s"Local service (${config.from}) wants to access protected service (${config.serviceId}) from other container (${chooseRemoteAddress(config)}) with apikey ${akid}"
        )
        env.datastores.apiKeyDataStore.findById(akid) flatMap {
          case Some(ak) =>
            f(
              service.copy(
                publicPatterns = Seq("/.*"),
                privatePatterns = Seq.empty,
                additionalHeaders = service.additionalHeaders ++ Map(
                  "Host"                           -> req.headers.get("Host").get,
                  env.Headers.OtoroshiClientId     -> ak.clientId,
                  env.Headers.OtoroshiClientSecret -> ak.clientSecret
                )
              )
            )
          case None =>
            Errors.craftResponseResult(
              "sidecar.bad.apikey.clientid",
              Results.InternalServerError,
              req,
              Some(service),
              None,
              attrs = attrs
            )
        }
      }
      // when local service wants to access unprotected services from other containers
      case Some(config @ SidecarConfig(_, _, _, None, strict))
          if chooseRemoteAddress(config) == config.from && config.serviceId != service.id =>
        logger.debug(
          s"Local service (${config.from}) wants to access unprotected service (${config.serviceId}) from other container (${chooseRemoteAddress(config)}) without apikey"
        )
        f(service.copy(publicPatterns = Seq("/.*"), privatePatterns = Seq.empty))
      // when local service wants to access himself through otoroshi
      case Some(config) if config.serviceId == service.id && chooseRemoteAddress(config) == config.from =>
        logger.debug(s"Local service (${config.from}) wants to access himself through Otoroshi")
        f(service.copy(targets = Seq(config.target)))
      // when service from other containers wants to access local service through otoroshi
      case Some(config) if config.serviceId == service.id && chooseRemoteAddress(config) != config.from =>
        logger.debug(
          s"External service (${chooseRemoteAddress(config)}) wants to access local service (${service.id}) through Otoroshi"
        )
        f(service.copy(targets = Seq(config.target)))
      case _ =>
        f(service)
    }
  }

  def passWithHeadersVerification(desc: ServiceDescriptor,
                                  req: RequestHeader,
                                  apiKey: Option[ApiKey],
                                  paUsr: Option[PrivateAppsUser],
                                  ctx: Map[String, String],
                                  attrs: TypedMap)(f: => Future[Result]): Future[Result] = {
    if (desc.headersVerification.isEmpty) {
      f
    } else {
      val inputHeaders = req.headers.toSimpleMap
        .mapValues(v => HeadersExpressionLanguage.apply(v, Some(req), Some(desc), apiKey, paUsr, ctx, attrs, env))
        .filterNot(h => h._2 == "null")
      desc.headersVerification.map(tuple => inputHeaders.get(tuple._1).exists(_ == tuple._2)).find(_ == false) match {
        case Some(_) =>
          Errors.craftResponseResult(
            "Missing header(s)",
            Results.BadRequest,
            req,
            Some(desc),
            Some("errors.missing.headers"),
            attrs = attrs
          )
        case None => f
      }
    }
  }

  def passWithReadOnly(readOnly: Boolean, req: RequestHeader, attrs: TypedMap)(f: => Future[Result]): Future[Result] = {
    readOnly match {
      case false => f
      case true =>
        req.method.toLowerCase match {
          case "get"     => f
          case "head"    => f
          case "options" => f
          case _ =>
            Errors.craftResponseResult(
              s"Method not allowed. Can only handle GET, HEAD, OPTIONS",
              MethodNotAllowed,
              req,
              None,
              Some("errors.method.not.allowed"),
              attrs = attrs
            )
        }
    }
  }

  def stateRespValid(stateValue: String,
                     stateResp: Option[String],
                     jti: String,
                     descriptor: ServiceDescriptor): Boolean = {
    stateResp match {
      case None => false
      case Some(resp) =>
        descriptor.secComVersion match {
          case SecComVersion.V1 => stateValue == resp
          case SecComVersion.V2 =>
            descriptor.algoChallengeFromBackToOto.asAlgorithm(models.OutputMode)(env) match {
              case None => false
              case Some(algo) => {
                Try {
                  val jwt = JWT
                    .require(algo)
                    .withAudience(env.Headers.OtoroshiIssuer)
                    .withClaim("state-resp", stateValue)
                    .acceptLeeway(10) // TODO: customize ???
                    .build()
                    .verify(resp)
                  val exp =
                    Option(jwt.getClaim("exp")).filterNot(_.isNull).map(_.asLong())
                  val iat =
                    Option(jwt.getClaim("iat")).filterNot(_.isNull).map(_.asLong())
                  if (exp.isEmpty || iat.isEmpty) {
                    false
                  } else {
                    if ((exp.get - iat.get) <= descriptor.secComTtl.toSeconds) { // seconds
                      true
                    } else {
                      false
                    }
                  }
                } match {
                  case Success(v) => v
                  case Failure(e) => false
                }
              }
            }
        }
    }
  }

  def forwardCall() = actionBuilder.async(sourceBodyParser) { req =>
    // TODO : add metrics + JMX
    // val meterIn             = Metrics.metrics.meter("GatewayDataIn")
    // val meterOut            = Metrics.metrics.meter("GatewayDataOut")
    // req.clientCertificateChain.foreach { chain =>
    //   chain.foreach(c => logger.info(s"incoming cert chain: $c"))
    // }
    // req.clientCertificateChain.getOrElse(logger.info("no cert chain"))
    val snowflake           = env.snowflakeGenerator.nextIdStr()
    val callDate            = DateTime.now()
    val requestTimestamp    = callDate.toString("yyyy-MM-dd'T'HH:mm:ss.SSSZZ")
    val reqNumber           = reqCounter.incrementAndGet()
    val remoteAddress       = req.theIpAddress
    val isSecured           = req.theSecured
    val from                = req.theIpAddress
    val counterIn           = new AtomicLong(0L)
    val counterOut          = new AtomicLong(0L)
    val start               = System.currentTimeMillis()
    val bodyAlreadyConsumed = new AtomicBoolean(false)
    val protocol            = req.theProtocol
    val attrs = utils.TypedMap.empty.put(
      otoroshi.plugins.Keys.SnowFlakeKey        -> snowflake,
      otoroshi.plugins.Keys.RequestTimestampKey -> callDate,
      otoroshi.plugins.Keys.RequestStartKey     -> start,
      otoroshi.plugins.Keys.RequestWebsocketKey -> false
    )

    val elCtx: Map[String, String] = Map(
      "requestId"        -> snowflake,
      "requestSnowflake" -> snowflake,
      "requestTimestamp" -> requestTimestamp
    )

    attrs.put(otoroshi.plugins.Keys.ElCtxKey -> elCtx)

    val currentHandledRequests = env.datastores.requestsDataStore.incrementHandledRequests()
    // val currentProcessedRequests = env.datastores.requestsDataStore.incrementProcessedRequests()
    val globalConfig = env.datastores.globalConfigDataStore.latest()

    val finalResult = {
      // env.datastores.globalConfigDataStore.singleton().flatMap { globalConfig => // Very consuming but eh !!!
      env.metrics.markLong(s"${env.snowflakeSeed}.concurrent-requests", currentHandledRequests)
      if (currentHandledRequests > globalConfig.maxConcurrentRequests) {
        Audit.send(
          MaxConcurrentRequestReachedEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            globalConfig.maxConcurrentRequests,
            currentHandledRequests
          )
        )
        Alerts.send(
          MaxConcurrentRequestReachedAlert(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            globalConfig.maxConcurrentRequests,
            currentHandledRequests
          )
        )
      }
      if (globalConfig.limitConcurrentRequests && currentHandledRequests > globalConfig.maxConcurrentRequests) {
        Errors.craftResponseResult(s"Cannot process more request",
                                   TooManyRequests,
                                   req,
                                   None,
                                   Some("errors.cant.process.more.request"),
                                   attrs = attrs)
      } else {

        ServiceLocation(req.theHost, globalConfig) match {
          case None =>
            val err = Errors.craftResponseResult(s"Service not found: invalid host",
                                                 NotFound,
                                                 req,
                                                 None,
                                                 Some("errors.service.not.found.invalid.host"),
                                                 attrs = attrs)
            RequestSink.maybeSinkRequest(snowflake,
                                         req,
                                         attrs,
                                         RequestOrigin.ReverseProxy,
                                         404,
                                         s"Service not found: invalid host",
                                         err)

          case Some(ServiceLocation(domain, serviceEnv, subdomain)) => {
            val uriParts = req.relativeUri.split("/").toSeq

            env.datastores.serviceDescriptorDataStore
              .find(ServiceDescriptorQuery(subdomain, serviceEnv, domain, req.relativeUri, req.headers.toSimpleMap),
                    req,
                    attrs)
              .fast
              .flatMap {
                case None =>
                  // val query = ServiceDescriptorQuery(subdomain, serviceEnv, domain, req.relativeUri, req.headers.toSimpleMap)
                  // logger.info(s"Downstream service not found for $query")
                  val err = Errors
                    .craftResponseResult(s"Service not found",
                                         NotFound,
                                         req,
                                         None,
                                         Some("errors.service.not.found"),
                                         attrs = attrs)
                  RequestSink
                    .maybeSinkRequest(snowflake, req, attrs, RequestOrigin.ReverseProxy, 404, s"Service not found", err)
                case Some(desc) if !desc.enabled =>
                  val err = Errors
                    .craftResponseResult(s"Service unavailable",
                                         ServiceUnavailable,
                                         req,
                                         None,
                                         Some("errors.service.unavailable"),
                                         attrs = attrs)
                  RequestSink.maybeSinkRequest(snowflake,
                                               req,
                                               attrs,
                                               RequestOrigin.ReverseProxy,
                                               503,
                                               "Service unavailable",
                                               err)
                case Some(rawDesc) if rawDesc.redirection.enabled && rawDesc.redirection.hasValidCode => {
                  // TODO: event here
                  FastFuture.successful(
                    Results
                      .Status(rawDesc.redirection.code)
                      .withHeaders("Location" -> rawDesc.redirection.formattedTo(req, rawDesc, elCtx, attrs, env))
                  )
                }
                case Some(rawDesc) => {
                  if (rawDesc.id != env.backOfficeServiceId && globalConfig.maintenanceMode) {
                    Errors.craftResponseResult(
                      "Service in maintenance mode",
                      ServiceUnavailable,
                      req,
                      Some(rawDesc),
                      Some("errors.service.in.maintenance"),
                      attrs = attrs
                    )
                  } else {
                    rawDesc
                      .beforeRequest(
                        BeforeRequestContext(
                          index = -1,
                          snowflake = snowflake,
                          descriptor = rawDesc,
                          request = req,
                          config = rawDesc.transformerConfig,
                          attrs = attrs
                        )
                      )
                      .flatMap { _ =>
                        rawDesc.preRoute(snowflake, req, attrs) {
                          passWithTcpUdpTunneling(req, rawDesc, attrs) {
                            passWithHeadersVerification(rawDesc, req, None, None, elCtx, attrs) {
                              passWithReadOnly(rawDesc.readOnly, req, attrs) {
                                applySidecar(rawDesc, remoteAddress, req, attrs) { desc =>
                                  val firstOverhead = System.currentTimeMillis() - start
                                  snowMonkey.introduceChaos(reqNumber, globalConfig, desc, hasBody(req)) {
                                    snowMonkeyContext =>
                                      val secondStart = System.currentTimeMillis()
                                      val maybeCanaryId: Option[String] = req.cookies
                                        .get("otoroshi-canary")
                                        .map(_.value)
                                        .orElse(req.headers.get(env.Headers.OtoroshiTrackerId))
                                        .filter { value =>
                                          if (value.contains("::")) {
                                            value.split("::").toList match {
                                              case signed :: id :: Nil if env.sign(id) == signed => true
                                              case _                                             => false
                                            }
                                          } else {
                                            false
                                          }
                                        } map (value => value.split("::")(1))
                                      val canaryId: String = maybeCanaryId.getOrElse(IdGenerator.uuid + "-" + reqNumber)

                                      val trackingId: String = req.cookies
                                        .get("otoroshi-tracking")
                                        .map(_.value)
                                        .getOrElse(IdGenerator.uuid + "-" + reqNumber)

                                      if (maybeCanaryId.isDefined) {
                                        logger.debug(s"request already has canary id : $canaryId")
                                      } else {
                                        logger.debug(s"request has a new canary id : $canaryId")
                                      }

                                      val withTrackingCookies: Seq[Cookie] = {
                                        if (!desc.canary.enabled)
                                          Seq.empty[play.api.mvc.Cookie]
                                        else if (maybeCanaryId.isDefined)
                                          Seq.empty[play.api.mvc.Cookie]
                                        else
                                          Seq(
                                            play.api.mvc.Cookie(
                                              name = "otoroshi-canary",
                                              value = s"${env.sign(canaryId)}::$canaryId",
                                              maxAge = Some(2592000),
                                              path = "/",
                                              domain = Some(req.theDomain),
                                              httpOnly = false
                                            )
                                          )
                                      } ++ (if (desc.targetsLoadBalancing.needTrackingCookie) {
                                              Seq(
                                                play.api.mvc.Cookie(
                                                  name = "otoroshi-tracking",
                                                  value = trackingId,
                                                  maxAge = Some(2592000),
                                                  path = "/",
                                                  domain = Some(req.theDomain),
                                                  httpOnly = false
                                                )
                                              )
                                            } else {
                                              Seq.empty[Cookie]
                                            })

                                      //desc.isUp.flatMap(iu => splitToCanary(desc, trackingId).fast.map(d => (iu, d))).fast.flatMap {
                                      splitToCanary(desc, canaryId, reqNumber, globalConfig).fast.flatMap { _desc =>
                                        val isUp = true

                                        val descriptor = _desc

                                        def callDownstream(config: GlobalConfig,
                                                           _apiKey: Option[ApiKey] = None,
                                                           _paUsr: Option[PrivateAppsUser] = None): Future[Result] = {

                                          val apiKey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey).orElse(_apiKey)
                                          val paUsr  = attrs.get(otoroshi.plugins.Keys.UserKey).orElse(_paUsr)

                                          apiKey
                                            .foreach(apk => attrs.putIfAbsent(otoroshi.plugins.Keys.ApiKeyKey  -> apk))
                                          paUsr.foreach(usr => attrs.putIfAbsent(otoroshi.plugins.Keys.UserKey -> usr))

                                          desc
                                            .validateClientCertificates(snowflake, req, apiKey, paUsr, config, attrs) {
                                              passWithReadOnly(apiKey.map(_.readOnly).getOrElse(false), req, attrs) {
                                                if (config.useCircuitBreakers && descriptor.clientConfig.useCircuitBreaker) {
                                                  val cbStart = System.currentTimeMillis()
                                                  val counter = new AtomicInteger(0)
                                                  val relUri  = req.relativeUri
                                                  val cachedPath: String =
                                                    descriptor.clientConfig
                                                      .timeouts(relUri)
                                                      .map(_ => relUri)
                                                      .getOrElse("")
                                                  env.circuitBeakersHolder
                                                    .get(desc.id + cachedPath,
                                                         () => new ServiceDescriptorCircuitBreaker())
                                                    .call(
                                                      descriptor,
                                                      reqNumber.toString,
                                                      trackingId,
                                                      req.relativeUri,
                                                      req,
                                                      bodyAlreadyConsumed,
                                                      s"${req.method} ${req.relativeUri}",
                                                      counter,
                                                      attrs,
                                                      (t, attempts) =>
                                                        actuallyCallDownstream(t,
                                                                               apiKey,
                                                                               paUsr,
                                                                               System.currentTimeMillis - cbStart,
                                                                               counter.get())
                                                    ) recoverWith {
                                                    case BodyAlreadyConsumedException =>
                                                      Errors.craftResponseResult(
                                                        s"Something went wrong, the downstream service does not respond quickly enough but consumed all the request body, you should try later. Thanks for your understanding",
                                                        GatewayTimeout,
                                                        req,
                                                        Some(descriptor),
                                                        Some("errors.request.timeout"),
                                                        duration = System.currentTimeMillis - start,
                                                        overhead = (System
                                                          .currentTimeMillis() - secondStart) + firstOverhead,
                                                        cbDuration = System.currentTimeMillis - cbStart,
                                                        callAttempts = counter.get(),
                                                        attrs = attrs
                                                      )
                                                    case RequestTimeoutException =>
                                                      Errors.craftResponseResult(
                                                        s"Something went wrong, the downstream service does not respond quickly enough, you should try later. Thanks for your understanding",
                                                        GatewayTimeout,
                                                        req,
                                                        Some(descriptor),
                                                        Some("errors.request.timeout"),
                                                        duration = System.currentTimeMillis - start,
                                                        overhead = (System
                                                          .currentTimeMillis() - secondStart) + firstOverhead,
                                                        cbDuration = System.currentTimeMillis - cbStart,
                                                        callAttempts = counter.get(),
                                                        attrs = attrs
                                                      )
                                                    case _: scala.concurrent.TimeoutException =>
                                                      Errors.craftResponseResult(
                                                        s"Something went wrong, the downstream service does not respond quickly enough, you should try later. Thanks for your understanding",
                                                        GatewayTimeout,
                                                        req,
                                                        Some(descriptor),
                                                        Some("errors.request.timeout"),
                                                        duration = System.currentTimeMillis - start,
                                                        overhead = (System
                                                          .currentTimeMillis() - secondStart) + firstOverhead,
                                                        cbDuration = System.currentTimeMillis - cbStart,
                                                        callAttempts = counter.get(),
                                                        attrs = attrs
                                                      )
                                                    case AllCircuitBreakersOpenException =>
                                                      Errors.craftResponseResult(
                                                        s"Something went wrong, the downstream service seems a little bit overwhelmed, you should try later. Thanks for your understanding",
                                                        ServiceUnavailable,
                                                        req,
                                                        Some(descriptor),
                                                        Some("errors.circuit.breaker.open"),
                                                        duration = System.currentTimeMillis - start,
                                                        overhead = (System
                                                          .currentTimeMillis() - secondStart) + firstOverhead,
                                                        cbDuration = System.currentTimeMillis - cbStart,
                                                        callAttempts = counter.get(),
                                                        attrs = attrs
                                                      )
                                                    case error
                                                        if error != null && error.getMessage != null && error.getMessage
                                                          .toLowerCase()
                                                          .contains("connection refused") =>
                                                      Errors.craftResponseResult(
                                                        s"Something went wrong, the connection to downstream service was refused, you should try later. Thanks for your understanding",
                                                        BadGateway,
                                                        req,
                                                        Some(descriptor),
                                                        Some("errors.connection.refused"),
                                                        duration = System.currentTimeMillis - start,
                                                        overhead = (System
                                                          .currentTimeMillis() - secondStart) + firstOverhead,
                                                        cbDuration = System.currentTimeMillis - cbStart,
                                                        callAttempts = counter.get(),
                                                        attrs = attrs
                                                      )
                                                    case error if error != null && error.getMessage != null =>
                                                      logger.error(s"Something went wrong, you should try later", error)
                                                      Errors.craftResponseResult(
                                                        s"Something went wrong, you should try later. Thanks for your understanding.",
                                                        BadGateway,
                                                        req,
                                                        Some(descriptor),
                                                        Some("errors.proxy.error"),
                                                        duration = System.currentTimeMillis - start,
                                                        overhead = (System
                                                          .currentTimeMillis() - secondStart) + firstOverhead,
                                                        cbDuration = System.currentTimeMillis - cbStart,
                                                        callAttempts = counter.get(),
                                                        attrs = attrs
                                                      )
                                                    case error =>
                                                      logger.error(s"Something went wrong, you should try later", error)
                                                      Errors.craftResponseResult(
                                                        s"Something went wrong, you should try later. Thanks for your understanding",
                                                        BadGateway,
                                                        req,
                                                        Some(descriptor),
                                                        Some("errors.proxy.error"),
                                                        duration = System.currentTimeMillis - start,
                                                        overhead = (System
                                                          .currentTimeMillis() - secondStart) + firstOverhead,
                                                        cbDuration = System.currentTimeMillis - cbStart,
                                                        callAttempts = counter.get(),
                                                        attrs = attrs
                                                      )
                                                  }
                                                } else {
                                                  val targets: Seq[Target] = descriptor.targets
                                                    .filter(_.predicate.matches(reqNumber.toString, req, attrs))
                                                    .flatMap(t => Seq.fill(t.weight)(t))
                                                  val target = descriptor.targetsLoadBalancing
                                                    .select(reqNumber.toString, trackingId, req, targets, descriptor)
                                                  //val index = reqCounter.get() % (if (targets.nonEmpty) targets.size else 1)
                                                  // Round robin loadbalancing is happening here !!!!!
                                                  //val target = targets.apply(index.toInt)
                                                  actuallyCallDownstream(target, apiKey, paUsr, 0L, 1)
                                                }
                                              }
                                            }
                                        }

                                        def actuallyCallDownstream(_target: Target,
                                                                   apiKey: Option[ApiKey] = None,
                                                                   paUsr: Option[PrivateAppsUser] = None,
                                                                   cbDuration: Long,
                                                                   callAttempts: Int): Future[Result] = {
                                          applyJwtVerifier(rawDesc, req, apiKey, paUsr, elCtx, attrs) { jwtInjection =>
                                            //val snowflake        = env.snowflakeGenerator.nextIdStr()
                                            val requestTimestamp =
                                              DateTime.now().toString("yyyy-MM-dd'T'HH:mm:ss.SSSZZ")
                                            val jti        = IdGenerator.uuid
                                            val stateValue = IdGenerator.extendedToken(128)
                                            val stateToken: String = descriptor.secComVersion match {
                                              case SecComVersion.V1 => stateValue
                                              case SecComVersion.V2 =>
                                                OtoroshiClaim(
                                                  iss = env.Headers.OtoroshiIssuer,
                                                  sub = env.Headers.OtoroshiIssuer,
                                                  aud = descriptor.name,
                                                  exp = DateTime
                                                    .now()
                                                    .plusSeconds(descriptor.secComTtl.toSeconds.toInt)
                                                    .toDate
                                                    .getTime,
                                                  iat = DateTime.now().toDate.getTime,
                                                  jti = jti
                                                ).withClaim("state", stateValue)
                                                  .serialize(descriptor.algoChallengeFromOtoToBack)
                                            }
                                            val rawUri      = req.relativeUri.substring(1)
                                            val uriParts    = rawUri.split("/").toSeq
                                            val uri: String = descriptor.maybeStrippedUri(req, rawUri)
                                            val scheme =
                                              if (descriptor.redirectToLocal) descriptor.localScheme else _target.scheme
                                            val host = TargetExpressionLanguage(if (descriptor.redirectToLocal)
                                                                                  descriptor.localHost
                                                                                else _target.host,
                                                                                Some(req),
                                                                                Some(descriptor),
                                                                                apiKey,
                                                                                paUsr,
                                                                                elCtx,
                                                                                attrs,
                                                                                env)
                                            val root = descriptor.root
                                            val url = TargetExpressionLanguage(s"$scheme://$host$root$uri",
                                                                               Some(req),
                                                                               Some(descriptor),
                                                                               apiKey,
                                                                               paUsr,
                                                                               elCtx,
                                                                               attrs,
                                                                               env)
                                            lazy val currentReqHasBody = hasBody(req)
                                            // val queryString = req.queryString.toSeq.flatMap { case (key, values) => values.map(v => (key, v)) }
                                            val fromOtoroshi = req.headers
                                              .get(env.Headers.OtoroshiRequestId)
                                              .orElse(req.headers.get(env.Headers.OtoroshiGatewayParentRequest))
                                            val promise = Promise[ProxyDone]

                                            val claim = descriptor.generateInfoToken(apiKey, paUsr, Some(req))
                                            logger.trace(s"Claim is : $claim")

                                            val stateResponseHeaderName = descriptor.secComHeaders.stateResponseName
                                              .getOrElse(env.Headers.OtoroshiStateResp)

                                            val headersIn: Seq[(String, String)] = HeadersHelper.composeHeadersIn(
                                              descriptor = descriptor,
                                              req = req,
                                              apiKey = apiKey,
                                              paUsr = paUsr,
                                              elCtx = elCtx,
                                              currentReqHasBody = currentReqHasBody,
                                              headersInFiltered = headersInFiltered,
                                              snowflake = snowflake,
                                              requestTimestamp = requestTimestamp,
                                              host = host,
                                              claim = claim,
                                              stateToken = stateToken,
                                              fromOtoroshi = fromOtoroshi,
                                              snowMonkeyContext = snowMonkeyContext,
                                              jwtInjection = jwtInjection,
                                              attrs = attrs
                                            )

                                            val lazySource = Source.single(ByteString.empty).flatMapConcat { _ =>
                                              bodyAlreadyConsumed.compareAndSet(false, true)
                                              req.body
                                                .concat(snowMonkeyContext.trailingRequestBodyStream)
                                                .map(bs => {
                                                  // meterIn.mark(bs.length)
                                                  counterIn.addAndGet(bs.length)
                                                  bs
                                                })
                                            }
                                            // val requestHeader = ByteString(
                                            //   req.method + " " + req.relativeUri + " HTTP/1.1\n" + headersIn
                                            //     .map(h => s"${h._1}: ${h._2}")
                                            //     .mkString("\n") + "\n"
                                            // )
                                            // meterIn.mark(requestHeader.length)
                                            // counterIn.addAndGet(requestHeader.length)
                                            // logger.trace(s"curl -X ${req.method.toUpperCase()} ${headersIn.map(h => s"-H '${h._1}: ${h._2}'").mkString(" ")} '$url?${queryString.map(h => s"${h._1}=${h._2}").mkString("&")}' --include")
                                            debugLogger.trace(
                                              s"curl -X ${req.method
                                                .toUpperCase()} ${headersIn.map(h => s"-H '${h._1}: ${h._2}'").mkString(" ")} '$url' --include"
                                            )
                                            val overhead = (System.currentTimeMillis() - secondStart) + firstOverhead
                                            if (overhead > env.overheadThreshold) {
                                              HighOverheadAlert(
                                                `@id` = env.snowflakeGenerator.nextIdStr(),
                                                limitOverhead = env.overheadThreshold,
                                                currentOverhead = overhead,
                                                serviceDescriptor = descriptor,
                                                target = Location(
                                                  scheme = req.theProtocol,
                                                  host = req.theHost,
                                                  uri = req.relativeUri
                                                )
                                              ).toAnalytics()
                                            }
                                            val quotas: Future[RemainingQuotas] =
                                              apiKey
                                                .map(_.updateQuotas())
                                                .getOrElse(FastFuture.successful(RemainingQuotas()))
                                            promise.future.andThen {
                                              case Success(resp) => {

                                                val actualDuration: Long = System.currentTimeMillis() - start
                                                val duration: Long =
                                                  if (descriptor.id == env.backOfficeServiceId && actualDuration > 300L)
                                                    300L
                                                  else actualDuration

                                                analyticsQueue ! AnalyticsQueueEvent(descriptor,
                                                                                     duration,
                                                                                     overhead,
                                                                                     counterIn.get(),
                                                                                     counterOut.get(),
                                                                                     resp.upstreamLatency,
                                                                                     globalConfig)

                                                descriptor.targetsLoadBalancing match {
                                                  case BestResponseTime =>
                                                    BestResponseTime.incrementAverage(descriptor, _target, duration)
                                                  case WeightedBestResponseTime(_) =>
                                                    BestResponseTime.incrementAverage(descriptor, _target, duration)
                                                  case _ =>
                                                }

                                                quotas.andThen {
                                                  case Success(q) => {
                                                    val fromLbl =
                                                      req.headers
                                                        .get(env.Headers.OtoroshiVizFromLabel)
                                                        .getOrElse("internet")
                                                    val viz: OtoroshiViz = OtoroshiViz(
                                                      to = descriptor.id,
                                                      toLbl = descriptor.name,
                                                      from = req.headers
                                                        .get(env.Headers.OtoroshiVizFrom)
                                                        .getOrElse("internet"),
                                                      fromLbl = fromLbl,
                                                      fromTo = s"$fromLbl###${descriptor.name}"
                                                    )
                                                    val evt = GatewayEvent(
                                                      `@id` = env.snowflakeGenerator.nextIdStr(),
                                                      reqId = snowflake,
                                                      parentReqId = fromOtoroshi,
                                                      `@timestamp` = DateTime.now(),
                                                      `@calledAt` = callDate,
                                                      protocol = req.version,
                                                      to = Location(
                                                        scheme = req.theProtocol,
                                                        host = req.theHost,
                                                        uri = req.relativeUri
                                                      ),
                                                      target = Location(
                                                        scheme = scheme,
                                                        host = host,
                                                        uri = req.relativeUri
                                                      ),
                                                      duration = duration,
                                                      overhead = overhead,
                                                      cbDuration = cbDuration,
                                                      overheadWoCb = overhead - cbDuration,
                                                      callAttempts = callAttempts,
                                                      url = url,
                                                      method = req.method,
                                                      from = from,
                                                      env = descriptor.env,
                                                      data = DataInOut(
                                                        dataIn = counterIn.get(),
                                                        dataOut = counterOut.get()
                                                      ),
                                                      status = resp.status,
                                                      headers = req.headers.toSimpleMap.toSeq.map(Header.apply),
                                                      headersOut = resp.headersOut,
                                                      identity = apiKey
                                                        .map(
                                                          k =>
                                                            Identity(
                                                              identityType = "APIKEY",
                                                              identity = k.clientId,
                                                              label = k.clientName
                                                          )
                                                        )
                                                        .orElse(
                                                          paUsr.map(
                                                            k =>
                                                              Identity(
                                                                identityType = "PRIVATEAPP",
                                                                identity = k.email,
                                                                label = k.name
                                                            )
                                                          )
                                                        ),
                                                      responseChunked = resp.isChunked,
                                                      `@serviceId` = descriptor.id,
                                                      `@service` = descriptor.name,
                                                      descriptor = Some(descriptor),
                                                      `@product` = descriptor.metadata.getOrElse("product", "--"),
                                                      remainingQuotas = q,
                                                      viz = Some(viz),
                                                      clientCertChain = req.clientCertChainPem,
                                                      err = false,
                                                      userAgentInfo =
                                                        attrs.get[JsValue](otoroshi.plugins.Keys.UserAgentInfoKey),
                                                      geolocationInfo =
                                                        attrs.get[JsValue](otoroshi.plugins.Keys.GeolocationInfoKey),
                                                      extraAnalyticsData =
                                                        attrs.get[JsValue](otoroshi.plugins.Keys.ExtraAnalyticsDataKey)
                                                    )
                                                    evt.toAnalytics()
                                                    if (descriptor.logAnalyticsOnServer) {
                                                      evt.log()(env, env.analyticsExecutionContext) // pressure EC
                                                    }
                                                  }
                                                }(env.analyticsExecutionContext) // pressure EC
                                              }
                                            }(env.analyticsExecutionContext) // pressure EC
                                            //.andThen {
                                            //  case _ => env.datastores.requestsDataStore.decrementProcessedRequests()
                                            //}
                                            val wsCookiesIn = req.cookies.toSeq.map(
                                              c =>
                                                DefaultWSCookie(
                                                  name = c.name,
                                                  value = c.value,
                                                  domain = c.domain,
                                                  path = Option(c.path),
                                                  maxAge = c.maxAge.map(_.toLong),
                                                  secure = c.secure,
                                                  httpOnly = c.httpOnly
                                              )
                                            )
                                            val rawRequest = otoroshi.script.HttpRequest(
                                              url = s"${req.theProtocol}://${req.theHost}${req.relativeUri}",
                                              method = req.method,
                                              headers = req.headers.toSimpleMap,
                                              cookies = wsCookiesIn,
                                              version = req.version,
                                              clientCertificateChain = req.clientCertificateChain,
                                              target = None,
                                              claims = claim
                                            )
                                            val otoroshiRequest = otoroshi.script.HttpRequest(
                                              url = url,
                                              method = req.method,
                                              headers = headersIn.toMap,
                                              cookies = wsCookiesIn,
                                              version = req.version,
                                              clientCertificateChain = req.clientCertificateChain,
                                              target = Some(_target),
                                              claims = claim
                                            )
                                            val transReqCtx = TransformerRequestContext(
                                              index = -1,
                                              snowflake = snowflake,
                                              rawRequest = rawRequest,
                                              otoroshiRequest = otoroshiRequest,
                                              descriptor = descriptor,
                                              apikey = apiKey,
                                              user = paUsr,
                                              request = req,
                                              config = descriptor.transformerConfig,
                                              attrs = attrs
                                            )
                                            val finalRequest = descriptor
                                              .transformRequest(transReqCtx)
                                            val finalBody = descriptor.transformRequestBody(
                                              TransformerRequestBodyContext(
                                                index = -1,
                                                snowflake = snowflake,
                                                rawRequest = rawRequest,
                                                otoroshiRequest = otoroshiRequest,
                                                descriptor = descriptor,
                                                apikey = apiKey,
                                                user = paUsr,
                                                request = req,
                                                config = descriptor.transformerConfig,
                                                body = lazySource,
                                                attrs = attrs
                                              )
                                            )
                                            finalRequest.flatMap {
                                              case Left(badResult) => {
                                                quotas.fast.map { remainingQuotas =>
                                                  val _headersOut: Seq[(String, String)] =
                                                    HeadersHelper.composeHeadersOutBadResult(
                                                      descriptor = descriptor,
                                                      req = req,
                                                      badResult = badResult,
                                                      apiKey = apiKey,
                                                      paUsr = paUsr,
                                                      elCtx = elCtx,
                                                      snowflake = snowflake,
                                                      requestTimestamp = requestTimestamp,
                                                      headersOutFiltered = headersOutFiltered,
                                                      overhead = overhead,
                                                      upstreamLatency = 0L,
                                                      canaryId = canaryId,
                                                      remainingQuotas = remainingQuotas,
                                                      attrs = attrs
                                                    )
                                                  promise.trySuccess(
                                                    ProxyDone(
                                                      badResult.header.status,
                                                      false,
                                                      0,
                                                      _headersOut.map(Header.apply)
                                                    )
                                                  )
                                                  badResult.withHeaders(_headersOut: _*)
                                                }
                                              }
                                              case Right(httpRequest) => {
                                                val upstreamStart = System.currentTimeMillis()
                                                val body =
                                                  if (currentReqHasBody) SourceBody(finalBody)
                                                  else EmptyBody

                                                // Stream IN

                                                // env.gatewayClient
                                                //   .urlWithProtocol(target.scheme, url)
                                                //   //.withRequestTimeout(descriptor.clientConfig.callTimeout.millis)
                                                //   .withRequestTimeout(6.hour) // we should monitor leaks
                                                //   .withMethod(req.method)
                                                //   // .withQueryString(queryString: _*)
                                                //   .withHttpHeaders(headersIn: _*)
                                                //   .withBody(body)
                                                //   .withFollowRedirects(false)
                                                //   .stream()

                                                val finalTarget = httpRequest.target.getOrElse(_target)
                                                attrs.put(otoroshi.plugins.Keys.RequestTargetKey -> finalTarget)

                                                val clientReq = descriptor.useAkkaHttpClient match {
                                                  case _ if finalTarget.mtlsConfig.mtls =>
                                                    env.gatewayClient.akkaUrlWithTarget(
                                                      UrlSanitizer.sanitize(httpRequest.url),
                                                      finalTarget,
                                                      descriptor.clientConfig
                                                    )
                                                  case true =>
                                                    env.gatewayClient.akkaUrlWithTarget(
                                                      UrlSanitizer.sanitize(httpRequest.url),
                                                      finalTarget,
                                                      descriptor.clientConfig
                                                    )
                                                  case false =>
                                                    env.gatewayClient.urlWithTarget(
                                                      UrlSanitizer.sanitize(httpRequest.url),
                                                      finalTarget,
                                                      descriptor.clientConfig
                                                    )
                                                }

                                                val builder = clientReq
                                                  .withRequestTimeout(
                                                    descriptor.clientConfig.extractTimeout(req.relativeUri,
                                                                                           _.callAndStreamTimeout,
                                                                                           _.callAndStreamTimeout)
                                                  )
                                                  //.withRequestTimeout(env.requestTimeout) // we should monitor leaks
                                                  .withMethod(httpRequest.method)
                                                  // .withHttpHeaders(httpRequest.headers.toSeq.filterNot(_._1 == "Cookie"): _*)
                                                  .withHttpHeaders(
                                                    HeadersHelper
                                                      .addClaims(httpRequest.headers, httpRequest.claims, descriptor)
                                                      .filterNot(_._1 == "Cookie"): _*
                                                  )
                                                  .withCookies(wsCookiesIn: _*)
                                                  .withFollowRedirects(false)
                                                  .withMaybeProxyServer(
                                                    descriptor.clientConfig.proxy.orElse(globalConfig.proxies.services)
                                                  )

                                                // because writeableOf_WsBody always add a 'Content-Type: application/octet-stream' header
                                                val builderWithBody = if (currentReqHasBody) {
                                                  builder.withBody(body)
                                                } else {
                                                  builder
                                                }

                                                builderWithBody
                                                  .stream()
                                                  .flatMap(resp => quotas.fast.map(q => (resp, q)))
                                                  .flatMap { tuple =>
                                                    val (resp, remainingQuotas) = tuple
                                                    // val responseHeader          = ByteString(s"HTTP/1.1 ${resp.headers.status}")
                                                    val headers = resp.headers.mapValues(_.head)
                                                    val _headersForOut: Seq[(String, String)] =
                                                      resp.headers.toSeq.flatMap(
                                                        c => c._2.map(v => (c._1, v))
                                                      ) //.map(tuple => (tuple._1, tuple._2.mkString(","))) //.toSimpleMap // .mapValues(_.head)
                                                    val rawResponse = otoroshi.script.HttpResponse(
                                                      status = resp.status,
                                                      headers = headers.toMap,
                                                      cookies = resp.cookies
                                                    )
                                                    // logger.trace(s"Connection: ${resp.headers.headers.get("Connection").map(_.last)}")
                                                    // if (env.notDev && !headers.get(stateRespHeaderName).contains(state)) {
                                                    // val validState = headers.get(stateRespHeaderName).filter(c => env.crypto.verifyString(state, c)).orElse(headers.get(stateRespHeaderName).contains(state)).getOrElse(false)
                                                    val stateRespHeaderName = descriptor.secComHeaders.stateResponseName
                                                      .getOrElse(env.Headers.OtoroshiStateResp)
                                                    val stateResp = headers
                                                      .get(stateRespHeaderName)
                                                      .orElse(headers.get(stateRespHeaderName.toLowerCase))
                                                    if ((descriptor.enforceSecureCommunication && descriptor.sendStateChallenge)
                                                        && !descriptor.isUriExcludedFromSecuredCommunication("/" + uri)
                                                        && !stateRespValid(stateValue, stateResp, jti, descriptor)) {
                                                      // && !headers.get(stateRespHeaderName).contains(state)) {
                                                      resp.ignore()
                                                      if (resp.status == 404 && headers
                                                            .get("X-CleverCloudUpgrade")
                                                            .contains("true")) {
                                                        Errors.craftResponseResult(
                                                          "No service found for the specified target host, the service descriptor should be verified !",
                                                          NotFound,
                                                          req,
                                                          Some(descriptor),
                                                          Some("errors.no.service.found"),
                                                          duration = System.currentTimeMillis - start,
                                                          overhead = (System
                                                            .currentTimeMillis() - secondStart) + firstOverhead,
                                                          cbDuration = cbDuration,
                                                          callAttempts = callAttempts,
                                                          attrs = attrs
                                                        )
                                                      } else if (isUp) {
                                                        // val body = Await.result(resp.body.runFold(ByteString.empty)((a, b) => a.concat(b)).map(_.utf8String), Duration("10s"))
                                                        val exchange = Json.stringify(
                                                          Json.obj(
                                                            "uri"           -> req.relativeUri,
                                                            "url"           -> url,
                                                            "state"         -> stateValue,
                                                            "reveivedState" -> JsString(stateResp.getOrElse("--")),
                                                            "claim" -> claim
                                                              .serialize(descriptor.algoInfoFromOtoToBack)(env),
                                                            "method" -> req.method,
                                                            "query"  -> req.rawQueryString,
                                                            "status" -> resp.status,
                                                            "headersIn" -> JsArray(
                                                              req.headers.toSimpleMap
                                                                .map(t => Json.obj("name" -> t._1, "value" -> t._2))
                                                                .toSeq
                                                            ),
                                                            "headersOut" -> JsArray(
                                                              headers
                                                                .map(t => Json.obj("name" -> t._1, "values" -> t._2))
                                                                .toSeq
                                                            )
                                                          )
                                                        )
                                                        logger
                                                          .error(
                                                            s"\n\nError while talking with downstream service :(\n\n$exchange\n\n"
                                                          )
                                                        Errors.craftResponseResult(
                                                          "Downstream microservice does not seems to be secured. Cancelling request !",
                                                          BadGateway,
                                                          req,
                                                          Some(descriptor),
                                                          Some("errors.service.not.secured"),
                                                          duration = System.currentTimeMillis - start,
                                                          overhead = (System
                                                            .currentTimeMillis() - secondStart) + firstOverhead,
                                                          cbDuration = cbDuration,
                                                          callAttempts = callAttempts,
                                                          attrs = attrs
                                                        )
                                                      } else {
                                                        Errors.craftResponseResult(
                                                          "The service seems to be down :( come back later",
                                                          Forbidden,
                                                          req,
                                                          Some(descriptor),
                                                          Some("errors.service.down"),
                                                          duration = System.currentTimeMillis - start,
                                                          overhead = (System
                                                            .currentTimeMillis() - secondStart) + firstOverhead,
                                                          cbDuration = cbDuration,
                                                          callAttempts = callAttempts,
                                                          attrs = attrs
                                                        )
                                                      }
                                                    } else {
                                                      val upstreamLatency = System.currentTimeMillis() - upstreamStart
                                                      val _headersOut: Seq[(String, String)] =
                                                        HeadersHelper.composeHeadersOut(
                                                          descriptor = descriptor,
                                                          req = req,
                                                          resp = resp,
                                                          apiKey = apiKey,
                                                          paUsr = paUsr,
                                                          elCtx = elCtx,
                                                          snowflake = snowflake,
                                                          requestTimestamp = requestTimestamp,
                                                          headersOutFiltered = headersOutFiltered,
                                                          overhead = overhead,
                                                          upstreamLatency = upstreamLatency,
                                                          canaryId = canaryId,
                                                          remainingQuotas = remainingQuotas,
                                                          attrs = attrs
                                                        )

                                                      val otoroshiResponse = otoroshi.script.HttpResponse(
                                                        status = resp.status,
                                                        headers = _headersOut.toMap,
                                                        cookies = resp.cookies
                                                      )
                                                      descriptor
                                                        .transformResponse(
                                                          TransformerResponseContext(
                                                            index = -1,
                                                            snowflake = snowflake,
                                                            rawResponse = rawResponse,
                                                            otoroshiResponse = otoroshiResponse,
                                                            descriptor = descriptor,
                                                            apikey = apiKey,
                                                            user = paUsr,
                                                            request = req,
                                                            config = descriptor.transformerConfig,
                                                            attrs = attrs
                                                          )
                                                        )
                                                        .flatMap {
                                                          case Left(badResult) => {
                                                            resp.ignore()
                                                            FastFuture.successful(badResult)
                                                          }
                                                          case Right(httpResponse) => {
                                                            val headersOut = httpResponse.headers.toSeq
                                                            val contentType: Option[String] = httpResponse.headers
                                                              .get("Content-Type")
                                                              .orElse(httpResponse.headers.get("content-type"))

                                                            // val _contentTypeOpt = resp.headers.get("Content-Type").flatMap(_.lastOption)
                                                            // meterOut.mark(responseHeader.length)
                                                            // counterOut.addAndGet(responseHeader.length)

                                                            val noContentLengthHeader: Boolean =
                                                              resp.contentLength.isEmpty
                                                            val hasChunkedHeader: Boolean = resp
                                                              .header("Transfer-Encoding")
                                                              .exists(h => h.toLowerCase().contains("chunked"))
                                                            val isChunked: Boolean = resp.isChunked() match {
                                                              case Some(chunked) => chunked
                                                              case None if !env.emptyContentLengthIsChunked =>
                                                                hasChunkedHeader // false
                                                              case None
                                                                  if env.emptyContentLengthIsChunked && hasChunkedHeader =>
                                                                true
                                                              case None
                                                                  if env.emptyContentLengthIsChunked && !hasChunkedHeader && noContentLengthHeader =>
                                                                true
                                                              case _ => false
                                                            }
                                                            //val hasNoContentLength: Boolean = if (!env.emptyContentLengthIsChunked) false else noContentLengthHeader
                                                            //val isChunked = resp.header("Transfer-Encoding")
                                                            //  .exists(h => h.toLowerCase().contains("chunked")) || hasNoContentLength
                                                            val theStream: Source[ByteString, _] = resp.bodyAsSource
                                                              .concat(snowMonkeyContext.trailingResponseBodyStream)
                                                              .alsoTo(Sink.onComplete {
                                                                case Success(_) =>
                                                                  // debugLogger.trace(s"end of stream for ${protocol}://${req.host}${req.relativeUri}")
                                                                  promise.trySuccess(
                                                                    ProxyDone(httpResponse.status,
                                                                              isChunked,
                                                                              upstreamLatency,
                                                                              headersOut.map(Header.apply))
                                                                  )
                                                                case Failure(e) =>
                                                                  logger.error(
                                                                    s"error while transfering stream for ${protocol}://${req.theHost}${req.relativeUri}",
                                                                    e
                                                                  )
                                                                  resp.ignore()
                                                                  promise.trySuccess(
                                                                    ProxyDone(httpResponse.status,
                                                                              isChunked,
                                                                              upstreamLatency,
                                                                              headersOut.map(Header.apply))
                                                                  )
                                                              })
                                                              .map { bs =>
                                                                // debugLogger.trace(s"chunk on ${req.relativeUri} => ${bs.utf8String}")
                                                                // meterOut.mark(bs.length)
                                                                counterOut.addAndGet(bs.length)
                                                                bs
                                                              }

                                                            val finalStream = descriptor.transformResponseBody(
                                                              TransformerResponseBodyContext(
                                                                index = -1,
                                                                snowflake = snowflake,
                                                                rawResponse = rawResponse,
                                                                otoroshiResponse = otoroshiResponse,
                                                                descriptor = descriptor,
                                                                apikey = apiKey,
                                                                user = paUsr,
                                                                request = req,
                                                                config = descriptor.transformerConfig,
                                                                body = theStream,
                                                                attrs = attrs
                                                              )
                                                            )

                                                            val cookies = httpResponse.cookies.map(
                                                              c =>
                                                                Cookie(
                                                                  name = c.name,
                                                                  value = c.value,
                                                                  maxAge = c.maxAge.map(_.toInt),
                                                                  path = c.path.getOrElse("/"),
                                                                  domain = c.domain,
                                                                  secure = c.secure,
                                                                  httpOnly = c.httpOnly,
                                                                  sameSite = None
                                                              )
                                                            )

                                                            if (req.version == "HTTP/1.0") {
                                                              if (descriptor.allowHttp10) {
                                                                logger.warn(
                                                                  s"HTTP/1.0 request, storing temporary result in memory :( (${protocol}://${req.theHost}${req.relativeUri})"
                                                                )
                                                                finalStream
                                                                  .via(
                                                                    MaxLengthLimiter(
                                                                      globalConfig.maxHttp10ResponseSize.toInt,
                                                                      str => logger.warn(str)
                                                                    )
                                                                  )
                                                                  .runWith(
                                                                    Sink.reduce[ByteString]((bs, n) => bs.concat(n))
                                                                  )
                                                                  .fast
                                                                  .flatMap { body =>
                                                                    val response
                                                                      : Result = Status(httpResponse.status)(body)
                                                                      .withHeaders(
                                                                        headersOut.filterNot { h =>
                                                                          val lower = h._1.toLowerCase()
                                                                          lower == "content-type" || lower == "set-cookie" || lower == "transfer-encoding"
                                                                        }: _*
                                                                      )
                                                                      .withCookies(
                                                                        withTrackingCookies ++ jwtInjection.additionalCookies
                                                                          .map(t => Cookie(t._1, t._2)) ++ cookies: _*
                                                                      )
                                                                    contentType match {
                                                                      case None => desc.gzip.handleResult(req, response)
                                                                      case Some(ctp) =>
                                                                        desc.gzip.handleResult(req, response.as(ctp))
                                                                    }
                                                                  }
                                                              } else {
                                                                resp.ignore()
                                                                Errors.craftResponseResult(
                                                                  "You cannot use HTTP/1.0 here",
                                                                  HttpVersionNotSupported,
                                                                  req,
                                                                  Some(descriptor),
                                                                  Some("errors.http.10.not.allowed"),
                                                                  duration = System.currentTimeMillis - start,
                                                                  overhead = (System
                                                                    .currentTimeMillis() - secondStart) + firstOverhead,
                                                                  cbDuration = cbDuration,
                                                                  callAttempts = callAttempts,
                                                                  attrs = attrs
                                                                )
                                                              }
                                                            } else {
                                                              val response: Result = isChunked match {
                                                                case true => {
                                                                  // stream out
                                                                  val res = Status(httpResponse.status)
                                                                    .chunked(finalStream)
                                                                    .withHeaders(
                                                                      headersOut.filterNot { h =>
                                                                        val lower = h._1.toLowerCase()
                                                                        lower == "content-type" || lower == "set-cookie" || lower == "transfer-encoding"
                                                                      }: _*
                                                                    )
                                                                    .withCookies(
                                                                      (withTrackingCookies ++ jwtInjection.additionalCookies
                                                                        .map(t => Cookie(t._1, t._2)) ++ cookies): _*
                                                                    )
                                                                  contentType match {
                                                                    case None      => res
                                                                    case Some(ctp) => res.as(ctp)
                                                                  }
                                                                }
                                                                case false => {
                                                                  val contentLength: Option[Long] = httpResponse.headers
                                                                    .get("Content-Length")
                                                                    .orElse(httpResponse.headers.get("content-length"))
                                                                    .orElse(resp.contentLengthStr)
                                                                    .map(
                                                                      _.toLong + snowMonkeyContext.trailingResponseBodySize
                                                                    )
                                                                  val actualContentLength: Long =
                                                                    contentLength.getOrElse(0L)
                                                                  if (actualContentLength == 0L) {
                                                                    // here, Play did not run the body because it's empty, so triggering things manually
                                                                    logger
                                                                      .debug(
                                                                        "Triggering promise as content length is 0"
                                                                      )
                                                                    promise.trySuccess(
                                                                      ProxyDone(httpResponse.status,
                                                                                isChunked,
                                                                                upstreamLatency,
                                                                                headersOut.map(Header.apply))
                                                                    )
                                                                  }
                                                                  // stream out
                                                                  val res = Status(httpResponse.status)
                                                                    .sendEntity(
                                                                      HttpEntity.Streamed(
                                                                        finalStream,
                                                                        contentLength,
                                                                        contentType
                                                                      )
                                                                    )
                                                                    .withHeaders(
                                                                      headersOut.filterNot { h =>
                                                                        val lower = h._1.toLowerCase()
                                                                        lower == "content-type" || lower == "set-cookie" || lower == "transfer-encoding"
                                                                      }: _*
                                                                    )
                                                                    .withCookies(
                                                                      (withTrackingCookies ++ jwtInjection.additionalCookies
                                                                        .map(t => Cookie(t._1, t._2)) ++ cookies): _*
                                                                    )
                                                                  contentType match {
                                                                    case None      => res
                                                                    case Some(ctp) => res.as(ctp)
                                                                  }
                                                                }
                                                              }
                                                              desc.gzip.handleResult(req, response)
                                                            }
                                                          }
                                                        }
                                                    }
                                                  }
                                              }
                                            }
                                          }
                                        }

                                        def errorResult(status: Results.Status, message: String, code: String) = {
                                          Errors.craftResponseResult(
                                            message,
                                            status,
                                            req,
                                            Some(descriptor),
                                            Some(code),
                                            duration = System.currentTimeMillis - start,
                                            overhead = (System
                                              .currentTimeMillis() - secondStart) + firstOverhead,
                                            attrs = attrs
                                          )
                                        }

                                        val query = ServiceDescriptorQuery(subdomain, serviceEnv, domain, "/")
                                        ReverseProxyHelper
                                          .handleRequest(
                                            ReverseProxyHelper.HandleRequestContext(req,
                                                                                    query,
                                                                                    descriptor,
                                                                                    isUp,
                                                                                    attrs,
                                                                                    globalConfig,
                                                                                    logger),
                                            (c, oapk, ousr) => callDownstream(c, oapk, ousr).map(v => Right(v)),
                                            (s, mess, cod) => errorResult(s, mess, cod).map(v => Left(v))
                                          )
                                          .map {
                                            case Left(res)  => res
                                            case Right(res) => res
                                          }
                                      }
                                  }
                                }
                              }

                            }
                          }
                        }
                      }
                      .andThen {
                        case _ =>
                          rawDesc.afterRequest(
                            AfterRequestContext(
                              index = -1,
                              snowflake = snowflake,
                              descriptor = rawDesc,
                              request = req,
                              config = rawDesc.transformerConfig,
                              attrs = attrs
                            )
                          )
                      }
                  }
                }
              }
          }
        }
      }
    }
    env.metrics
      .withTimerAsync("otoroshi.core.proxy.handle-http-request")(finalResult)
      .andThen {
        case _ =>
          val requests = env.datastores.requestsDataStore.decrementHandledRequests()
          env.metrics.markLong(s"${env.snowflakeSeed}.concurrent-requests", requests)
      }(env.otoroshiExecutionContext)
  }

  def decodeBase64(encoded: String): String = new String(OtoroshiClaim.decoder.decode(encoded), Charsets.UTF_8)
}

object ReverseProxyHelper {

  case class HandleRequestContext(req: RequestHeader,
                                  query: ServiceDescriptorQuery,
                                  descriptor: ServiceDescriptor,
                                  isUp: Boolean,
                                  attrs: TypedMap,
                                  globalConfig: GlobalConfig,
                                  logger: Logger)

  def handleRequest[T](
      ctx: HandleRequestContext,
      callDownstream: (GlobalConfig, Option[ApiKey], Option[PrivateAppsUser]) => Future[Either[Result, T]],
      errorResult: (Results.Status, String, String) => Future[Either[Result, T]]
  )(implicit ec: ExecutionContext, env: Env): Future[Either[Result, T]] = {

    // Algo is :
    // if (app.private) {
    //   if (uri.isPublic) {
    //      AUTH0
    //   } else {
    //      APIKEY
    //   }
    // } else {
    //   if (uri.isPublic) {
    //     PASSTHROUGH without gateway auth
    //   } else {
    //     APIKEY
    //   }
    // }

    val HandleRequestContext(req, query, descriptor, isUp, attrs, globalConfig, logger) = ctx
    val isSecured                                                                       = req.theSecured
    val remoteAddress                                                                   = req.theIpAddress

    def passWithApiKey(config: GlobalConfig): Future[Either[Result, T]] = {
      ApiKeyHelper.passWithApiKey(
        ApiKeyHelper.PassWithApiKeyContext(req, descriptor, attrs, config),
        callDownstream,
        errorResult
      )
    }

    def passWithAuth0(config: GlobalConfig): Future[Either[Result, T]] = {
      PrivateAppsUserHelper.passWithAuth(
        PrivateAppsUserHelper.PassWithAuthContext(req, query, descriptor, attrs, config, logger),
        callDownstream,
        errorResult
      )
    }

    env.datastores.globalConfigDataStore.quotasValidationFor(remoteAddress).flatMap { r =>
      val (within, secCalls, maybeQuota) = r
      val quota                          = maybeQuota.getOrElse(globalConfig.perIpThrottlingQuota)
      val (restrictionsNotPassing, restrictionsResponse) =
        descriptor.restrictions.handleRestrictions(descriptor, None, req, attrs)
      if (secCalls > (quota * 10L)) {
        errorResult(TooManyRequests, "[IP] You performed too much requests", "errors.too.much.requests")
      } else {
        if (!isSecured && descriptor.forceHttps) {
          val theDomain = req.theDomain
          val protocol  = req.theProtocol
          logger.trace(
            s"redirects prod service from ${protocol}://$theDomain${req.relativeUri} to https://$theDomain${req.relativeUri}"
          )
          //FastFuture.successful(Redirect(s"${env.rootScheme}$theDomain${req.relativeUri}"))
          FastFuture.successful(Redirect(s"https://$theDomain${req.relativeUri}")).map(Left.apply)
        } else if (!within) {
          errorResult(TooManyRequests, "[GLOBAL] You performed too much requests", "errors.too.much.requests")
        } else if (globalConfig.ipFiltering.notMatchesWhitelist(remoteAddress)) {
          /*else if (globalConfig.ipFiltering.whitelist.nonEmpty && !globalConfig.ipFiltering.whitelist
               .exists(ip => utils.RegexPool(ip).matches(remoteAddress))) {*/
          errorResult(Forbidden, "Your IP address is not allowed", "errors.ip.address.not.allowed") // global whitelist
        } else if (globalConfig.ipFiltering.matchesBlacklist(remoteAddress)) {
          /*else if (globalConfig.ipFiltering.blacklist.nonEmpty && globalConfig.ipFiltering.blacklist
                 .exists(ip => utils.RegexPool(ip).matches(remoteAddress))) {*/
          errorResult(Forbidden, "Your IP address is not allowed", "errors.ip.address.not.allowed") // global blacklist
        } else if (descriptor.ipFiltering.notMatchesWhitelist(remoteAddress)) {
          /*else if (descriptor.ipFiltering.whitelist.nonEmpty && !descriptor.ipFiltering.whitelist
               .exists(ip => utils.RegexPool(ip).matches(remoteAddress))) {*/
          errorResult(Forbidden, "Your IP address is not allowed", "errors.ip.address.not.allowed") // service whitelist
        } else if (descriptor.ipFiltering.matchesBlacklist(remoteAddress)) {
          /*else if (descriptor.ipFiltering.blacklist.nonEmpty && descriptor.ipFiltering.blacklist
               .exists(ip => utils.RegexPool(ip).matches(remoteAddress))) {*/
          errorResult(Forbidden, "Your IP address is not allowed", "errors.ip.address.not.allowed") // service blacklist
        } else if (globalConfig.matchesEndlessIpAddresses(remoteAddress)) {
          /*else if (globalConfig.endlessIpAddresses.nonEmpty && globalConfig.endlessIpAddresses
               .exists(ip => RegexPool(ip).matches(remoteAddress))) {*/
          val gigas: Long = 128L * 1024L * 1024L * 1024L
          val middleFingers = ByteString.fromString(
            "\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95"
          )
          val zeros =
            ByteString.fromInts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
          val characters: ByteString =
            if (!globalConfig.middleFingers) middleFingers else zeros
          val expected: Long = (gigas / characters.size) + 1L
          FastFuture
            .successful(
              Status(200)
                .sendEntity(
                  HttpEntity.Streamed(
                    Source
                      .repeat(characters)
                      .take(expected), // 128 Go of zeros or middle fingers
                    None,
                    Some("application/octet-stream")
                  )
                )
            )
            .map(Left.apply)
        } else if (descriptor.maintenanceMode) {
          errorResult(ServiceUnavailable, "Service in maintenance mode", "errors.service.in.maintenance")
        } else if (descriptor.buildMode) {
          errorResult(ServiceUnavailable, "Service under construction", "errors.service.under.construction")
        } else if (descriptor.cors.enabled && req.method == "OPTIONS" && req.headers
                     .get("Access-Control-Request-Method")
                     .isDefined && descriptor.cors.shouldApplyCors(req.path)) {
          // handle cors preflight request
          if (descriptor.cors.enabled && descriptor.cors.shouldNotPass(req)) {
            errorResult(BadRequest, "Cors error", "errors.cors.error")
          } else {
            FastFuture
              .successful(
                Results
                  .Ok(ByteString.empty)
                  .withHeaders(descriptor.cors.asHeaders(req): _*)
              )
              .map(Left.apply)
          }
        } else if (restrictionsNotPassing) {
          restrictionsResponse.map(Left.apply)
        } else if (isUp) {
          if (descriptor.isPrivate && descriptor.authConfigRef.isDefined && !descriptor
                .isExcludedFromSecurity(req.path)) {
            if (descriptor.isUriPublic(req.path)) {
              passWithAuth0(globalConfig)
            } else {
              PrivateAppsUserHelper.isPrivateAppsSessionValid(req, descriptor, attrs).fast.flatMap {
                case Some(_) if descriptor.strictlyPrivate =>
                  passWithApiKey(globalConfig)
                case Some(user) => passWithAuth0(globalConfig)
                case None       => passWithApiKey(globalConfig)
              }
            }
          } else {
            if (descriptor.isUriPublic(req.path)) {
              if (env.detectApiKeySooner && descriptor.detectApiKeySooner && ApiKeyHelper
                    .detectApiKey(req, descriptor, attrs)) {
                passWithApiKey(globalConfig)
              } else {
                callDownstream(globalConfig, None, None)
              }
            } else {
              passWithApiKey(globalConfig)
            }
          }
        } else {
          // fail fast
          errorResult(Forbidden, "The service seems to be down :( come back later", "errors.service.down")
        }
      }
    }
  }
}
