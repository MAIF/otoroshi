package otoroshi.gateway

import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger
import akka.actor.{Actor, Props}
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import otoroshi.auth.{AuthModuleConfig, SamlAuthModuleConfig, SessionCookieValues}
import com.google.common.base.Charsets
import otoroshi.controllers.HealthController
import otoroshi.env.Env
import otoroshi.events._
import otoroshi.models._
import otoroshi.script._
import otoroshi.ssl.OcspResponder
import otoroshi.utils.{RegexPool, TypedMap}
import otoroshi.utils.letsencrypt._
import otoroshi.utils.jwk.JWKSHelper
import play.api.ApplicationLoader.DevContext
import play.api.Logger
import play.api.http.{Status => _, _}
import play.api.libs.json._
import play.api.libs.streams.Accumulator
import play.api.mvc.Results._
import play.api.mvc._
import play.api.routing.Router
import play.core.WebCommands
import otoroshi.security.OtoroshiClaim
import otoroshi.ssl.{KeyManagerCompatibility, SSLSessionJavaHelper}
import otoroshi.utils.http.RequestImplicits._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NoStackTrace

case class ProxyDone(
    status: Int,
    isChunked: Boolean,
    upstreamLatency: Long,
    headersOut: Seq[Header],
    otoroshiHeadersOut: Seq[Header],
    otoroshiHeadersIn: Seq[Header]
)

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
    val snowflake     = env.snowflakeGenerator.nextIdStr()
    val attrs         = TypedMap.empty
    RequestSink.maybeSinkRequest(
      snowflake,
      request,
      Source.empty,
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
    val snowflake     = env.snowflakeGenerator.nextIdStr()
    val attrs         = TypedMap.empty
    RequestSink.maybeSinkRequest(
      snowflake,
      request,
      Source.empty,
      attrs,
      RequestOrigin.ErrorHandler,
      500,
      Option(exception).flatMap(e => Option(e.getMessage)).getOrElse("An error occurred ..."),
      Errors.craftResponseResult(
        "An error occurred ...",
        InternalServerError,
        request,
        None,
        Some("errors.server.error"),
        attrs = TypedMap.empty
      )
    )
  }
}

object SameThreadExecutionContext extends ExecutionContext {
  override def reportFailure(t: Throwable): Unit =
    throw new IllegalStateException("exception in SameThreadExecutionContext", t) with NoStackTrace
  override def execute(runnable: Runnable): Unit = runnable.run()
}

case class AnalyticsQueueEvent(
    descriptor: ServiceDescriptor,
    callDuration: Long,
    callOverhead: Long,
    dataIn: Long,
    dataOut: Long,
    upstreamLatency: Long,
    config: otoroshi.models.GlobalConfig
)

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

class GatewayRequestHandler(
    snowMonkey: SnowMonkey,
    httpHandler: HttpHandler,
    webSocketHandler: WebSocketHandler,
    reverseProxyAction: ReverseProxyAction,
    router: Router,
    errorHandler: HttpErrorHandler,
    configuration: HttpConfiguration,
    filters: Seq[EssentialFilter],
    webCommands: WebCommands,
    optDevContext: Option[DevContext],
    actionBuilder: ActionBuilder[Request, AnyContent],
    healthController: HealthController
)(implicit env: Env, mat: Materializer)
    extends DefaultHttpRequestHandler(webCommands, optDevContext, router, errorHandler, configuration, filters) {

  implicit lazy val ec        = env.otoroshiExecutionContext
  implicit lazy val scheduler = env.otoroshiScheduler

  lazy val logger = Logger("otoroshi-http-handler")
  // lazy val debugLogger = Logger("otoroshi-http-handler-debug")

  lazy val analyticsQueue = env.otoroshiActorSystem.actorOf(AnalyticsQueue.props(env))

  lazy val ipRegex         = RegexPool.regex(
    "^(([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\.){3}([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])(:\\d{2,5})?$"
  )
  lazy val monitoringPaths = Seq("/health", "/metrics", "/live", "/ready", "/startup")

  val sourceBodyParser = BodyParser("Gateway BodyParser") { _ =>
    Accumulator.source[ByteString].map(Right.apply)
  }

  val reqCounter = new AtomicInteger(0)

  val ocspResponder = OcspResponder(env, ec)

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
    "Tls-Session-Info"
  ).map(_.toLowerCase)

  val headersOutFiltered = Seq(
    env.Headers.OtoroshiStateResp,
    "Transfer-Encoding",
    "Content-Length",
    "Raw-Request-Uri",
    "Remote-Address",
    "Timeout-Access",
    "Tls-Session-Info"
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

  def badCertReply(request: RequestHeader) =
    actionBuilder.async { req =>
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
          KeyManagerCompatibility.session(key) match {
            case Some((_, _, chain))
                if chain.headOption.exists(_.getSubjectDN.getName.contains(SSLSessionJavaHelper.NotAllowed)) =>
              Some(badCertReply(request))
            case a => internalRouteRequest(request)
          }
        }
        case _         => Some(badCertReply(request)) // TODO: is it accurate ?
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
      if (env.clusterConfig.mode == otoroshi.cluster.ClusterMode.Worker && env.clusterAgent.cannotServeRequests()) {
        Some(clusterError("Waiting for first Otoroshi leader sync."))
      } else if (env.validateRequests && url.size > env.maxUrlLength) {
        Some(tooBig("URL should be smaller", UriTooLong))
      } else if (env.validateRequests && cookies.exists(_.size > env.maxCookieLength)) {
        Some(tooBig("Cookies should be smaller"))
      } else if (
        env.validateRequests && headers
          .exists(t => t._1.size > env.maxHeaderNameLength || t._2.size > env.maxHeaderValueLength)
      ) {
        Some(tooBig(s"Headers should be smaller"))
      } else {
        val toHttps     = env.exposedRootSchemeIsHttps
        val host        = request.theDomain // if (request.host.contains(":")) request.host.split(":")(0) else request.host
        val relativeUri = request.relativeUri
        val monitoring  = monitoringPaths.exists(p => relativeUri.startsWith(p))
        host match {
          case _ if relativeUri.contains("__otoroshi_assets")                 => super.routeRequest(request)
          case _ if relativeUri.startsWith("/__otoroshi_private_apps_login")  => Some(setPrivateAppsCookies())
          case _ if relativeUri.startsWith("/__otoroshi_private_apps_logout") => Some(removePrivateAppsCookies())

          case _ if relativeUri.startsWith("/.well-known/otoroshi/monitoring/health")  => Some(healthController.health())
          case _ if relativeUri.startsWith("/.well-known/otoroshi/monitoring/metrics") =>
            Some(healthController.processMetrics())
          case _ if relativeUri.startsWith("/.well-known/otoroshi/monitoring/live")    => Some(healthController.live())
          case _ if relativeUri.startsWith("/.well-known/otoroshi/monitoring/ready")   => Some(healthController.ready())
          case _ if relativeUri.startsWith("/.well-known/otoroshi/monitoring/startup") =>
            Some(healthController.startup())

          case _ if relativeUri.startsWith("/.well-known/otoroshi/security/jwks.json")                  => Some(jwks())
          case _ if relativeUri.startsWith("/.well-known/otoroshi/security/ocsp")                       => Some(ocsp())
          case _ if relativeUri.startsWith("/.well-known/otoroshi/security/certificates/")              =>
            Some(aia(relativeUri.replace("/.well-known/otoroshi/security/certificates/", ""))())
          case env.adminApiExposedHost if relativeUri.startsWith("/.well-known/jwks.json")              => Some(jwks())
          case env.backOfficeHost if relativeUri.startsWith("/.well-known/jwks.json")                   => Some(jwks())
          case env.adminApiExposedHost if relativeUri.startsWith("/.well-known/otoroshi/ocsp")          => Some(ocsp())
          case env.backOfficeHost if relativeUri.startsWith("/.well-known/otoroshi/ocsp")               => Some(ocsp())
          case env.backOfficeHost if relativeUri.startsWith("/.well-known/otoroshi/certificates/")      =>
            Some(aia(relativeUri.replace("/.well-known/otoroshi/certificates/", "")))
          case env.adminApiExposedHost if relativeUri.startsWith("/.well-known/otoroshi/certificates/") =>
            Some(aia(relativeUri.replace("/.well-known/otoroshi/certificates/", "")))

          case _ if relativeUri.startsWith("/.well-known/otoroshi/login")  => Some(setPrivateAppsCookies())
          case _ if relativeUri.startsWith("/.well-known/otoroshi/logout") => Some(removePrivateAppsCookies())
          case _ if relativeUri.startsWith("/.well-known/otoroshi/me")     => Some(myProfile())
          case _ if relativeUri.startsWith("/.well-known/acme-challenge/") => Some(letsEncrypt())

          case _ if ipRegex.matches(request.theHost) && monitoring => super.routeRequest(request)
          case str if matchRedirection(str)                        => Some(redirectToMainDomain())

          case env.backOfficeHost if !isSecured && toHttps  => Some(redirectToHttps())
          case env.privateAppsHost if !isSecured && toHttps => Some(redirectToHttps())
          case env.privateAppsHost if monitoring            => Some(forbidden())

          case env.adminApiExposedHost if monitoring          => super.routeRequest(request)
          case env.backOfficeHost if monitoring               => super.routeRequest(request)
          case env.adminApiHost if env.exposeAdminApi         => super.routeRequest(request)
          case env.backOfficeHost if env.exposeAdminDashboard => super.routeRequest(request)
          case env.privateAppsHost                            => super.routeRequest(request)
          case _                                              =>
            request.headers.get("Sec-WebSocket-Version") match {
              case None    =>
                Some(
                  httpHandler.forwardCall(
                    actionBuilder,
                    reverseProxyAction,
                    analyticsQueue,
                    snowMonkey,
                    headersInFiltered,
                    headersOutFiltered
                  )
                )
              case Some(_) =>
                Some(
                  webSocketHandler.forwardCall(reverseProxyAction, snowMonkey, headersInFiltered, headersOutFiltered)
                )
            }
        }
      }
    }
  }

  def jwks() =
    actionBuilder.async { req =>
      JWKSHelper.jwks(req, Seq.empty).map {
        case Left(body)  => Results.NotFound(body)
        case Right(body) => Results.Ok(body)
      }
    }

  def ocsp() =
    actionBuilder.async(sourceBodyParser) { req =>
      ocspResponder.respond(req, req.body)
    }

  def aia(id: String) =
    actionBuilder.async { req =>
      ocspResponder.aia(id, req)
    }

  def letsEncrypt() =
    actionBuilder.async { req =>
      if (!req.theSecured) {
        env.datastores.globalConfigDataStore.latestSafe match {
          case None                                                => FastFuture.successful(InternalServerError(Json.obj("error" -> "no config found !")))
          case Some(config) if !config.letsEncryptSettings.enabled =>
            FastFuture.successful(InternalServerError(Json.obj("error" -> "config disabled !")))
          case Some(config)                                        => {
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

  def setPrivateAppsCookies() =
    actionBuilder.async { req =>
      val redirectToOpt: Option[String] = req.queryString.get("redirectTo").map(_.last)
      val sessionIdOpt: Option[String]  = req.queryString.get("sessionId").map(_.last)
      val hostOpt: Option[String]       = req.queryString.get("host").map(_.last)
      val cookiePrefOpt: Option[String] = req.queryString.get("cp").map(_.last)
      val maOpt: Option[Int]            = req.queryString.get("ma").map(_.last).map(_.toInt)
      val httpOnlyOpt: Option[Boolean]  = req.queryString.get("httpOnly").map(_.last).map(_.toBoolean)
      val secureOpt: Option[Boolean]    = req.queryString.get("secure").map(_.last).map(_.toBoolean)
      val hashOpt: Option[String]       = req.queryString.get("hash").map(_.last)

      (hashOpt.map(h => env.sign(req.theUrl.replace(s"&hash=$h", ""))), hashOpt) match {
        case (Some(hashedUrl), Some(hash)) if hashedUrl == hash =>
          (redirectToOpt, sessionIdOpt, hostOpt, cookiePrefOpt, maOpt, httpOnlyOpt, secureOpt) match {
            case (Some("urn:ietf:wg:oauth:2.0:oob"), Some(sessionId), Some(host), Some(cp), ma, httpOnly, secure) =>
              FastFuture.successful(
                Ok(otoroshi.views.html.oto.token(env.signPrivateSessionId(sessionId), env)).withCookies(
                  env.createPrivateSessionCookiesWithSuffix(
                    host,
                    sessionId,
                    cp,
                    ma.getOrElse(86400),
                    SessionCookieValues(httpOnly.getOrElse(true), secure.getOrElse(true))
                  ): _*
                )
              )
            case (Some(redirectTo), Some(sessionId), Some(host), Some(cp), ma, httpOnly, secure)                  =>
              FastFuture.successful(
                Redirect(redirectTo).withCookies(
                  env.createPrivateSessionCookiesWithSuffix(
                    host,
                    sessionId,
                    cp,
                    ma.getOrElse(86400),
                    SessionCookieValues(httpOnly.getOrElse(true), secure.getOrElse(true))
                  ): _*
                )
              )
            case _                                                                                                =>
              Errors.craftResponseResult(
                "Missing parameters",
                BadRequest,
                req,
                None,
                Some("errors.missing.parameters"),
                attrs = TypedMap.empty
              )
          }
        case (_, _)                                             =>
          logger.warn(s"Unsecure redirection from privateApps login to ${redirectToOpt.getOrElse("no url")}")
          Errors.craftResponseResult(
            "Invalid redirection url",
            BadRequest,
            req,
            None,
            Some("errors.invalid.redirection.url"),
            attrs = TypedMap.empty
          )
      }
    }

  def withAuthConfig(descriptor: ServiceDescriptor, req: RequestHeader, attrs: TypedMap)(
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
          attrs = attrs
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
              attrs = attrs
            )
          case Some(auth) => f(auth)
        }
      }
    }
  }

  def myProfile() =
    actionBuilder.async { req =>
      implicit val request = req

      val attrs = TypedMap.empty

      env.datastores.globalConfigDataStore.singleton().flatMap { globalConfig =>
        ServiceLocation(req.theHost, globalConfig) match {
          case None                                                 => {
            Errors.craftResponseResult(
              s"Service not found",
              NotFound,
              req,
              None,
              Some("errors.service.not.found"),
              attrs = attrs
            )
          }
          case Some(ServiceLocation(domain, serviceEnv, subdomain)) => {
            env.datastores.serviceDescriptorDataStore
              .find(
                ServiceDescriptorQuery(subdomain, serviceEnv, domain, req.relativeUri, req.headers.toSimpleMap),
                req,
                attrs
              )
              .flatMap {
                case None                                                                                      => {
                  Errors.craftResponseResult(
                    s"Service not found",
                    NotFound,
                    req,
                    None,
                    Some("errors.service.not.found"),
                    attrs = attrs
                  )
                }
                case Some(desc) if !desc.enabled                                                               => {
                  Errors.craftResponseResult(
                    s"Service not found",
                    NotFound,
                    req,
                    None,
                    Some("errors.service.not.found"),
                    attrs = attrs
                  )
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
                    case None         =>
                      Errors
                        .craftResponseResult(
                          s"Invalid API key",
                          Unauthorized,
                          req,
                          None,
                          Some("errors.invalid.api.key"),
                          attrs = attrs
                        )
                    case Some(apiKey) =>
                      FastFuture.successful(Ok(apiKey.lightJson ++ Json.obj("access_type" -> "apikey")))
                  }
                }
                case Some(descriptor) if descriptor.privateApp && descriptor.id != env.backOfficeDescriptor.id => {
                  withAuthConfig(descriptor, req, attrs) { auth =>
                    PrivateAppsUserHelper.isPrivateAppsSessionValid(req, descriptor, attrs).flatMap {
                      case None          =>
                        Errors.craftResponseResult(
                          s"Invalid session",
                          Unauthorized,
                          req,
                          None,
                          Some("errors.invalid.session"),
                          attrs = attrs
                        )
                      case Some(session) =>
                        FastFuture.successful(Ok(session.profile.as[JsObject] ++ Json.obj("access_type" -> "session")))
                    }
                  }
                }
                case _                                                                                         => {
                  Errors.craftResponseResult(
                    s"Unauthorized",
                    Unauthorized,
                    req,
                    None,
                    Some("errors.unauthorized"),
                    attrs = attrs
                  )
                }
              }
          }
        }
      }
    }

  def removePrivateAppsCookies() =
    actionBuilder.async { req =>
      implicit val request = req

      val attrs = TypedMap.empty

      env.datastores.globalConfigDataStore.singleton().flatMap { globalConfig =>
        ServiceLocation(req.theHost, globalConfig) match {
          case None                                                 => {
            Errors.craftResponseResult(
              s"Service not found for URL ${req.theHost}::${req.relativeUri}",
              NotFound,
              req,
              None,
              Some("errors.service.not.found"),
              attrs = attrs
            )
          }
          case Some(ServiceLocation(domain, serviceEnv, subdomain)) => {
            env.datastores.serviceDescriptorDataStore
              .find(
                ServiceDescriptorQuery(subdomain, serviceEnv, domain, req.relativeUri, req.headers.toSimpleMap),
                req,
                attrs
              )
              .flatMap {
                case None                                                                                      => {
                  Errors.craftResponseResult(
                    s"Service not found",
                    NotFound,
                    req,
                    None,
                    Some("errors.service.not.found"),
                    attrs = attrs
                  )
                }
                case Some(desc) if !desc.enabled                                                               => {
                  Errors.craftResponseResult(
                    s"Service not found",
                    NotFound,
                    req,
                    None,
                    Some("errors.service.not.found"),
                    attrs = attrs
                  )
                }
                case Some(descriptor) if !descriptor.privateApp                                                => {
                  Errors.craftResponseResult(
                    s"Private apps are not configured",
                    InternalServerError,
                    req,
                    None,
                    Some("errors.service.auth.not.configured"),
                    attrs = attrs
                  )
                }
                case Some(descriptor) if descriptor.privateApp && descriptor.id != env.backOfficeDescriptor.id => {
                  withAuthConfig(descriptor, req, attrs) { auth =>
                    val u: Future[Option[PrivateAppsUser]] = auth match {
                      case _: SamlAuthModuleConfig =>
                        request.cookies
                          .find(c => c.name.startsWith("oto-papps-"))
                          .flatMap(env.extractPrivateSessionId)
                          .map {
                            env.datastores.privateAppsUserDataStore.findById(_)
                          }
                          .getOrElse(FastFuture.successful(None))
                      case _                       => FastFuture.successful(None)
                    }

                    u.flatMap { optUser =>
                      auth.authModule(globalConfig).paLogout(req, optUser, globalConfig, descriptor).map {
                        case Left(body)   =>
                          body.discardingCookies(env.removePrivateSessionCookies(req.theHost, descriptor, auth): _*)
                          body
                        case Right(value) =>
                          value match {
                            case None            => {
                              val cookieOpt     = request.cookies.find(c => c.name.startsWith("oto-papps-"))
                              cookieOpt.flatMap(env.extractPrivateSessionId).map { id =>
                                env.datastores.privateAppsUserDataStore.findById(id).map(_.foreach(_.delete()))
                              }
                              val finalRedirect = req.getQueryString("redirect").getOrElse(s"http://${req.theHost}")
                              val redirectTo    =
                                env.rootScheme + env.privateAppsHost + env.privateAppsPort + otoroshi.controllers.routes.AuthController
                                  .confidentialAppLogout()
                                  .url + s"?redirectTo=${finalRedirect}&host=${req.theHost}&cp=${auth.cookieSuffix(descriptor)}"
                              logger.trace("should redirect to " + redirectTo)
                              Redirect(redirectTo)
                                .discardingCookies(env.removePrivateSessionCookies(req.theHost, descriptor, auth): _*)
                            }
                            case Some(logoutUrl) => {
                              val cookieOpt         = request.cookies.find(c => c.name.startsWith("oto-papps-"))
                              cookieOpt.flatMap(env.extractPrivateSessionId).map { id =>
                                env.datastores.privateAppsUserDataStore.findById(id).map(_.foreach(_.delete()))
                              }
                              val finalRedirect     = req.getQueryString("redirect").getOrElse(s"http://${req.theHost}")
                              val redirectTo        =
                                env.rootScheme + env.privateAppsHost + env.privateAppsPort + otoroshi.controllers.routes.AuthController
                                  .confidentialAppLogout()
                                  .url + s"?redirectTo=${finalRedirect}&host=${req.theHost}&cp=${auth.cookieSuffix(descriptor)}"
                              val actualRedirectUrl =
                                logoutUrl.replace("${redirect}", URLEncoder.encode(redirectTo, "UTF-8"))
                              logger.trace("should redirect to " + actualRedirectUrl)
                              Redirect(actualRedirectUrl)
                                .discardingCookies(env.removePrivateSessionCookies(req.theHost, descriptor, auth): _*)
                            }
                          }
                      }
                    }
                  }
                }
                case _                                                                                         => {
                  Errors.craftResponseResult(
                    s"Private apps are not configured",
                    InternalServerError,
                    req,
                    None,
                    Some("errors.service.auth.not.configured"),
                    attrs = attrs
                  )
                }
              }
          }
        }
      }
    }

  def clusterError(message: String) =
    actionBuilder.async { req =>
      Errors.craftResponseResult(
        message,
        InternalServerError,
        req,
        None,
        Some("errors.no.cluster.state.yet"),
        attrs = TypedMap.empty
      )
    }

  def tooBig(message: String, status: Status = BadRequest) =
    actionBuilder.async { req =>
      Errors.craftResponseResult(message, BadRequest, req, None, Some("errors.entity.too.big"), attrs = TypedMap.empty)
    }

  def globalMaintenanceMode(attrs: TypedMap) =
    actionBuilder.async { req =>
      Errors.craftResponseResult(
        "Service in maintenance mode",
        ServiceUnavailable,
        req,
        None,
        Some("errors.service.in.maintenance"),
        attrs = attrs
      )
    }

  def forbidden() =
    actionBuilder { req =>
      Forbidden(Json.obj("error" -> "forbidden"))
    }

  def redirectToHttps() =
    actionBuilder { req =>
      val domain   = req.theDomain
      val protocol = req.theProtocol
      logger.trace(
        s"redirectToHttps from ${protocol}://$domain${req.relativeUri} to ${env.rootScheme}$domain${req.relativeUri}"
      )
      Redirect(s"${env.rootScheme}$domain${req.relativeUri}").withHeaders("otoroshi-redirect-to" -> "https")
    }

  def redirectToMainDomain() =
    actionBuilder { req =>
      val domain: String = env.redirections.foldLeft(req.theDomain)((domain, item) => domain.replace(item, env.domain))
      val protocol       = req.theProtocol
      logger.debug(
        s"redirectToMainDomain from $protocol://${req.theDomain}${req.relativeUri} to $protocol://$domain${req.relativeUri}"
      )
      Redirect(s"$protocol://$domain${req.relativeUri}")
    }

  def decodeBase64(encoded: String): String = new String(OtoroshiClaim.decoder.decode(encoded), Charsets.UTF_8)
}
