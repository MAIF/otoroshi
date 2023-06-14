package otoroshi.gateway

import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger
import akka.actor.{Actor, Props}
import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.{FileIO, Flow, Source}
import akka.util.ByteString
import com.github.blemale.scaffeine.Scaffeine
import otoroshi.auth.{AuthModuleConfig, SamlAuthModuleConfig, SessionCookieValues}
import com.google.common.base.Charsets
import controllers.Assets
import otoroshi.actions.{ApiAction, BackOfficeAction, PrivateAppsAction}
import otoroshi.controllers.HealthController
import otoroshi.env.Env
import otoroshi.events._
import otoroshi.models._
import otoroshi.next.models.NgRoute
import otoroshi.next.plugins.{MultiAuthModule, NgMultiAuthModuleConfig}
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
import otoroshi.security.{IdGenerator, OtoroshiClaim}
import otoroshi.ssl.{KeyManagerCompatibility, SSLSessionJavaHelper}
import otoroshi.utils.http.RequestImplicits._
import otoroshi.utils.syntax.implicits._

import java.io.File
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
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
    // logger.error(
    //   s"Client Error: $message from ${remoteAddress} on ${request.method} ${request.theProtocol}://${request.theHost}${request.relativeUri} ($statusCode) - ${request.headers.toSimpleMap
    //     .mkString(";")}"
    // )
    env.metrics.counterInc("errors.client")
    env.datastores.globalConfigDataStore.singleton().map { config =>
      env.datastores.serviceDescriptorDataStore.updateMetricsOnError(config)
    }
    val snowflake     = env.snowflakeGenerator.nextIdStr()
    val attrs         = TypedMap.empty
    // TODO: call NgRequestSinks
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
    env.metrics.counterInc("errors.server")
    env.datastores.globalConfigDataStore.singleton().map { config =>
      env.datastores.serviceDescriptorDataStore.updateMetricsOnError(config)
    }
    val snowflake     = env.snowflakeGenerator.nextIdStr()
    val attrs         = TypedMap.empty
    // TODO: call NgRequestSinks
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

object GatewayRequestHandler {

  lazy val logger = Logger("otoroshi-http-handler")

  def removePrivateAppsCookies(route: NgRoute, req: RequestHeader, attrs: TypedMap)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Result] = {
    lazy val routeLegacy = route.legacy
    val globalConfig     = env.datastores.globalConfigDataStore.latest()
    val request          = req

    withAuthConfig(route, req, attrs) { auth =>
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
        auth.authModule(globalConfig).paLogout(req, optUser, globalConfig, routeLegacy).map {
          case Left(body)   =>
            body.discardingCookies(env.removePrivateSessionCookies(req.theHost, routeLegacy, auth): _*)
            body
          case Right(value) =>
            value match {
              case None            => {
                val cookieOpt     = request.cookies.find(c => c.name.startsWith("oto-papps-"))
                cookieOpt.flatMap(env.extractPrivateSessionId).map { id =>
                  env.datastores.privateAppsUserDataStore.findById(id).map(_.foreach(_.delete()))
                }
                val finalRedirect =
                  req.getQueryString("redirect").getOrElse(s"${req.theProtocol}://${req.theHost}")
                val redirectTo    =
                  env.rootScheme + env.privateAppsHost + env.privateAppsPort + otoroshi.controllers.routes.AuthController
                    .confidentialAppLogout()
                    .url + s"?redirectTo=${finalRedirect}&host=${req.theHost}&cp=${auth.routeCookieSuffix(route)}"
                if (logger.isTraceEnabled) logger.trace("should redirect to " + redirectTo)
                Redirect(redirectTo)
                  .discardingCookies(env.removePrivateSessionCookies(req.theHost, routeLegacy, auth): _*)
              }
              case Some(logoutUrl) => {
                val cookieOpt         = request.cookies.find(c => c.name.startsWith("oto-papps-"))
                cookieOpt.flatMap(env.extractPrivateSessionId).map { id =>
                  env.datastores.privateAppsUserDataStore.findById(id).map(_.foreach(_.delete()))
                }
                val finalRedirect     =
                  req.getQueryString("redirect").getOrElse(s"${req.theProtocol}://${req.theHost}")
                val redirectTo        =
                  env.rootScheme + env.privateAppsHost + env.privateAppsPort + otoroshi.controllers.routes.AuthController
                    .confidentialAppLogout()
                    .url + s"?redirectTo=${finalRedirect}&host=${req.theHost}&cp=${auth.routeCookieSuffix(route)}"
                val actualRedirectUrl =
                  logoutUrl.replace("${redirect}", URLEncoder.encode(redirectTo, "UTF-8"))
                if (logger.isTraceEnabled) logger.trace("should redirect to " + actualRedirectUrl)
                Redirect(actualRedirectUrl)
                  .discardingCookies(env.removePrivateSessionCookies(req.theHost, routeLegacy, auth): _*)
              }
            }
        }
      }
    }
  }

  def withAuthConfig(route: NgRoute, req: RequestHeader, attrs: TypedMap)(
      f: AuthModuleConfig => Future[Result]
  )(implicit env: Env, ec: ExecutionContext): Future[Result] = {

    lazy val missingAuthRefError = Errors.craftResponseResult(
      "Auth. config. ref not found on the route",
      Results.InternalServerError,
      req,
      None,
      Some("errors.auth.config.ref.not.found"),
      attrs = attrs,
      maybeRoute = Some(route)
    )

    route.legacy.authConfigRef match {
      case None      =>
        route.plugins
          .getPluginByClass[MultiAuthModule]
          .map(multiAuth =>
            NgMultiAuthModuleConfig.format.reads(multiAuth.config.raw) match {
              case JsSuccess(config, _) =>
                req.cookies.filter(cookie => cookie.name.startsWith("oto-papps")) match {
                  case Nil                         => missingAuthRefError
                  case cookies if cookies.nonEmpty =>
                    config.modules
                      .flatMap(module => env.proxyState.authModule(module))
                      .find(module =>
                        cookies.exists(cookie => cookie.name == s"oto-papps-${module.routeCookieSuffix(route)}")
                      ) match {
                      case Some(authModuleConfig) => f(authModuleConfig)
                      case None                   => missingAuthRefError
                    }
                }
              case JsError(_)           => missingAuthRefError
            }
          )
          .getOrElse(missingAuthRefError)
      case Some(ref) =>
        env.proxyState.authModuleAsync(ref).flatMap {
          case None       => missingAuthRefError
          case Some(auth) => f(auth)
        }
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
    apiActionBuilder: ApiAction,
    backofficeActionBuilder: BackOfficeAction,
    privateActionBuilder: PrivateAppsAction,
    healthController: HealthController
)(implicit env: Env, mat: Materializer)
    extends DefaultHttpRequestHandler(webCommands, optDevContext, router, errorHandler, configuration, filters) {

  implicit lazy val ec        = env.otoroshiExecutionContext
  implicit lazy val scheduler = env.otoroshiScheduler

  lazy val logger = Logger("otoroshi-http-handler")
  // lazy val debugLogger = Logger("otoroshi-http-handler-debug")

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
    "Keep-Alive",
    "Transfer-Encoding",
    "Content-Length",
    "Raw-Request-Uri",
    "Remote-Address",
    "Timeout-Access",
    "Tls-Session-Info"
  ).map(_.toLowerCase)

  // TODO : very dirty ... fix it using Play 2.6 request.hasBody
  // def hasBody(request: Request[_]): Boolean = request.hasBody
  def hasBody(request: Request[_]): Boolean = {
    request.theHasBody
    // (request.method, request.headers.get("Content-Length")) match {
    //   case ("GET", Some(_))    => true
    //   case ("GET", None)       => false
    //   case ("HEAD", Some(_))   => true
    //   case ("HEAD", None)      => false
    //   case ("PATCH", _)        => true
    //   case ("POST", _)         => true
    //   case ("PUT", _)          => true
    //   case ("DELETE", Some(_)) => true
    //   case ("DELETE", None)    => false
    //   case _                   => true
    // }
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

  def incrementCounters(request: RequestHeader): Unit = {
    val ws    = request.headers.get("Sec-WebSocket-Version").isDefined
    val tls   = request.theSecured
    val http2 = request.version.toLowerCase == "http/2" || request.headers.get("x-http2-stream-id").isDefined
    val http3 = request.version.toLowerCase == "http/3"
    val http1 = !http2 && !http3
    val grpc  = request.headers.get("Content-Type").exists(_.contains("application/grpc"))
    env.clusterAgent.incrementCounter("requests", 1)
    if (ws) {
      if (tls) env.clusterAgent.incrementCounter("wss", 1)
      if (!tls) env.clusterAgent.incrementCounter("ws", 1)
    } else if (grpc) {
      if (tls) env.clusterAgent.incrementCounter("grpcs", 1)
      if (!tls) env.clusterAgent.incrementCounter("grpc", 1)
    } else {
      if (tls) {
        if (http1) env.clusterAgent.incrementCounter("https", 1)
        if (http2) env.clusterAgent.incrementCounter("h2", 1)
        if (http3) env.clusterAgent.incrementCounter("h3", 1)
      } else {
        if (http1) env.clusterAgent.incrementCounter("http", 1)
        if (http2) env.clusterAgent.incrementCounter("h2c", 1)
      }
    }
  }

  override def routeRequest(request: RequestHeader): Option[Handler] = {
    incrementCounters(request)
    val config = env.datastores.globalConfigDataStore.latestSafe
    if (request.theSecured && config.isDefined && config.get.autoCert.enabled) { // && config.get.autoCert.replyNicely) { // to avoid cache effet
      request.headers.get("Tls-Session-Info").flatMap(SSLSessionJavaHelper.computeKey) match {
        case Some(key) => {
          KeyManagerCompatibility.session(key) match {
            case Some((_, _, chain))
                if chain.headOption.exists(_.getSubjectDN.getName.contains(SSLSessionJavaHelper.NotAllowed)) =>
              Some(badCertReply(request))
            case a => internalRouteRequest(request, config)
          }
        }
        case _         => Some(badCertReply(request)) // TODO: is it accurate ?
      }
    } else {
      internalRouteRequest(request, config)
    }
  }

  def internalRouteRequest(request: RequestHeader, config: Option[GlobalConfig]): Option[Handler] = {
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
        if (env.revolver) {
          if (relativeUri.startsWith("/__otoroshi_assets/")) {
            return Some(serveDevAssets()) // I know ...
          } else if (host == env.backOfficeHost && relativeUri.startsWith("/assets/")) {
            return Some(serveDevAssets()) // I know ...
          } else if (host == env.privateAppsHost && relativeUri.startsWith("/assets/")) {
            return Some(serveDevAssets()) // I know ...
          }
        }
        host match {
          case _ if relativeUri.contains("__otoroshi_assets")                 =>
            super.routeRequest(request) // TODO additional assets routes
          case _ if relativeUri.startsWith("/__otoroshi_private_apps_login")  => Some(setPrivateAppsCookies())
          case _ if relativeUri.startsWith("/__otoroshi_private_apps_logout") => Some(removePrivateAppsCookies())

          case _ if relativeUri.startsWith("/.well-known/otoroshi/monitoring/health")  => Some(healthController.health())
          case _ if relativeUri.startsWith("/.well-known/otoroshi/monitoring/metrics") =>
            Some(healthController.processMetrics())
          case _ if relativeUri.startsWith("/.well-known/otoroshi/monitoring/live")    => Some(healthController.live())
          case _ if relativeUri.startsWith("/.well-known/otoroshi/monitoring/ready")   => Some(healthController.ready())
          case _ if relativeUri.startsWith("/.well-known/otoroshi/monitoring/startup") =>
            Some(healthController.startup())

          case _ if relativeUri.startsWith("/.well-known/otoroshi/security/jwks.json")     => Some(jwks())
          case _ if relativeUri.startsWith("/.well-known/otoroshi/security/ocsp")          => Some(ocsp())
          case _ if relativeUri.startsWith("/.well-known/otoroshi/security/certificates/") =>
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
          case env.adminApiHost if env.exposeAdminApi         =>
            env.adminExtensions.handleAdminApiCall(request, actionBuilder, apiActionBuilder, sourceBodyParser)(
              super.routeRequest(request)
            )
          case env.backOfficeHost if env.exposeAdminDashboard =>
            env.adminExtensions.handleBackofficeCall(request, actionBuilder, backofficeActionBuilder, sourceBodyParser)(
              super.routeRequest(request)
            )
          case env.privateAppsHost                            =>
            env.adminExtensions.handlePrivateAppsCall(request, actionBuilder, privateActionBuilder, sourceBodyParser)(
              super.routeRequest(request)
            )

          case env.adminApiHost if !env.exposeAdminApi && relativeUri.startsWith("/api/cluster/") =>
            super.routeRequest(request)

          case h if env.adminApiExposedDomains.contains(h) && relativeUri.startsWith("/.well-known/jwks.json")     =>
            Some(jwks())
          case h if env.backofficeDomains.contains(h) && relativeUri.startsWith("/.well-known/jwks.json")          =>
            Some(jwks())
          case h if env.adminApiExposedDomains.contains(h) && relativeUri.startsWith("/.well-known/otoroshi/ocsp") =>
            Some(ocsp())
          case h if env.backofficeDomains.contains(h) && relativeUri.startsWith("/.well-known/otoroshi/ocsp")      =>
            Some(ocsp())
          case h
              if env.backofficeDomains.contains(h) && relativeUri.startsWith("/.well-known/otoroshi/certificates/") =>
            Some(aia(relativeUri.replace("/.well-known/otoroshi/certificates/", "")))
          case h
              if env.adminApiExposedDomains
                .contains(h) && relativeUri.startsWith("/.well-known/otoroshi/certificates/") =>
            Some(aia(relativeUri.replace("/.well-known/otoroshi/certificates/", "")))
          case h if env.backofficeDomains.contains(h) && !isSecured && toHttps                                     => Some(redirectToHttps())
          case h if env.privateAppsDomains.contains(h) && !isSecured && toHttps                                    => Some(redirectToHttps())
          case h if env.privateAppsDomains.contains(h) && monitoring                                               => Some(forbidden())
          case h if env.adminApiExposedDomains.contains(h) && monitoring                                           => super.routeRequest(request)
          case h if env.backofficeDomains.contains(h) && monitoring                                                => super.routeRequest(request)
          case h if env.adminApiDomains.contains(h) && env.exposeAdminApi                                          =>
            env.adminExtensions.handleAdminApiCall(request, actionBuilder, apiActionBuilder, sourceBodyParser)(
              super.routeRequest(request)
            )
          case h if env.backofficeDomains.contains(h) && env.exposeAdminDashboard                                  =>
            env.adminExtensions.handleBackofficeCall(request, actionBuilder, backofficeActionBuilder, sourceBodyParser)(
              super.routeRequest(request)
            )
          case h if env.privateAppsDomains.contains(h)                                                             =>
            env.adminExtensions.handlePrivateAppsCall(request, actionBuilder, privateActionBuilder, sourceBodyParser)(
              super.routeRequest(request)
            )
          case _                                                                                                   => {
            if (relativeUri.startsWith("/.well-known/otoroshi/extensions/")) {
              env.adminExtensions.handleWellKnownCall(request, actionBuilder, sourceBodyParser)(
                reverseProxyCall(request, config)
              )
            } else {
              reverseProxyCall(request, config)
            }
          }
        }
      }
    }
  }

  @inline
  def reverseProxyCall(request: RequestHeader, config: Option[GlobalConfig]): Option[Handler] = {
    val exists = env.metrics.withTimer("handle-search-handler")(config.exists(_.plugins.canHandleRequest(request)))
    request.headers.get("Sec-WebSocket-Version") match {
      case None    => {
        if (exists) {
          Some(actionBuilder.async(sourceBodyParser) { zeRequest =>
            config.get.plugins.handleRequest(
              zeRequest,
              httpHandler.forwardAction(
                reverseProxyAction,
                env.analyticsQueue,
                snowMonkey,
                headersInFiltered,
                headersOutFiltered
              )
            )
          })
        } else {
          Some(
            httpHandler.forwardCall(
              actionBuilder,
              reverseProxyAction,
              env.analyticsQueue,
              snowMonkey,
              headersInFiltered,
              headersOutFiltered
            )
          )
        }
      }
      case Some(_) => {
        if (exists) {
          Some(WebSocket.acceptOrResult[play.api.http.websocket.Message, play.api.http.websocket.Message] { zeRequest =>
            config.get.plugins.handleWsRequest(
              zeRequest,
              r =>
                webSocketHandler.forwardCallRaw(
                  r,
                  reverseProxyAction,
                  snowMonkey,
                  headersInFiltered,
                  headersOutFiltered
                )
            )
          })
        } else {
          Some(
            webSocketHandler.forwardCall(reverseProxyAction, snowMonkey, headersInFiltered, headersOutFiltered)
          )
        }
      }
    }
  }

  private val devCache                               = Scaffeine().maximumSize(10000).build[String, (String, ByteString)]
  private lazy val devMimetypes: Map[String, String] = env.configuration
    .betterGetOptional[String]("play.http.fileMimeTypes")
    .map { types =>
      types
        .split("\\n")
        .toSeq
        .map(_.trim)
        .filter(_.nonEmpty)
        .map(_.split("=").toSeq)
        .filter(_.size == 2)
        .map(v => (v.head, v.tail.head))
        .toMap
    }
    .getOrElse(Map.empty[String, String])

  def serveDevAssets() = actionBuilder.async { req =>
    val wholePath = req.relativeUri
    if (logger.isDebugEnabled) logger.debug(s"dev serving asset '${wholePath}'")
    devCache.getIfPresent(wholePath) match {
      case Some((contentType, content)) => Results.Ok(content).as(contentType).future
      case None                         => {
        val path = wholePath
          .replaceFirst("/assets", "")
          .replaceFirst("/__otoroshi_assets", "")
          .applyOnIf(wholePath.contains("?"))(_.split("\\?").head)
        if (path.startsWith("/javascripts/bundle/")) {
          val host = req.theHost.replace(":9999", ":3040")
          Results.Redirect(s"${req.theProtocol}://$host/assets${path}").future
        } else {
          val ext              = path.split("\\.").toSeq.lastOption.getOrElse("txt").toLowerCase
          val mimeType: String = devMimetypes.getOrElse(ext, "text/plain")
          val fileSource = {
            val file = new File("./public" + path)
            if (file.exists()) {
              FileIO.fromPath(file.toPath).some
            } else {
              None
            }
          }
          fileSource.map { source =>
            source
              .runFold(ByteString.empty)(_ ++ _)
              .map { contentRaw =>
                devCache.put(wholePath, (mimeType, contentRaw))
                Results.Ok(contentRaw).as(mimeType)
              }
          } getOrElse {
            Results.NotFound(s"file '${wholePath}' not found !").future
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
      val redirectToOpt: Option[String]   = req.queryString.get("redirectTo").map(_.last)
      val sessionIdOpt: Option[String]    = req.queryString.get("sessionId").map(_.last)
      val hostOpt: Option[String]         = req.queryString.get("host").map(_.last)
      val cookiePrefOpt: Option[String]   = req.queryString.get("cp").map(_.last)
      val maOpt: Option[Int]              = req.queryString.get("ma").map(_.last).map(_.toInt)
      val httpOnlyOpt: Option[Boolean]    = req.queryString.get("httpOnly").map(_.last).map(_.toBoolean)
      val secureOpt: Option[Boolean]      = req.queryString.get("secure").map(_.last).map(_.toBoolean)
      val hashOpt: Option[String]         = req.queryString.get("hash").map(_.last)
      val secOpt: Option[PrivateAppsUser] = req.queryString
        .get("sec")
        .map(_.last)
        .flatMap(sec => Try(env.aesDecrypt(sec)).toOption)
        .flatMap(s => PrivateAppsUser.fmt.reads(s.parseJson).asOpt)

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
                    SessionCookieValues(httpOnly.getOrElse(true), secure.getOrElse(true)),
                    secOpt
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
                    SessionCookieValues(httpOnly.getOrElse(true), secure.getOrElse(true)),
                    secOpt
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

  private def serviceNotFound(request: RequestHeader, attrs: TypedMap): Future[Result] = {
    Errors.craftResponseResult(
      s"Service not found",
      NotFound,
      request,
      None,
      Some("errors.service.not.found"),
      attrs = attrs
    )
  }

  def withRoute(request: RequestHeader, attrs: TypedMap)(
      f: NgRoute => Future[Result]
  ): Future[Result] = {
    attrs.put(otoroshi.plugins.Keys.SnowFlakeKey -> env.snowflakeGenerator.nextIdStr())

    env.proxyState.findRoute(request, attrs) match {
      case None                                => serviceNotFound(request, attrs)
      case Some(route) if !route.route.enabled => serviceNotFound(request, attrs)
      case Some(route)                         => f(route.route)
    }
  }

  def myProfile() =
    actionBuilder.async { req =>
      val attrs = TypedMap.empty

      withRoute(req, attrs) {
        case route
            if !route.legacy.privateApp && route.id != env.backOfficeDescriptor.id && route.legacy.isUriPublic(
              req.path
            ) => {
          // Public service, no profile but no error either ???
          FastFuture.successful(Ok(Json.obj("access_type" -> "public")))
        }
        case route
            if !route.legacy.privateApp && route.id != env.backOfficeDescriptor.id && !route.legacy.isUriPublic(
              req.path
            ) => {
          // ApiKey
          ApiKeyHelper.extractApiKey(req, route.legacy, attrs).flatMap {
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
        case route if route.legacy.privateApp && route.id != env.backOfficeDescriptor.id =>
          GatewayRequestHandler.withAuthConfig(route, req, attrs) { _ =>
            PrivateAppsUserHelper.isPrivateAppsSessionValid(req, route.legacy, attrs).flatMap {
              case None          =>
                PrivateAppsUserHelper.isPrivateAppsSessionValidWithMultiAuth(req, route).flatMap {
                  case Some(session) => FastFuture.successful(Ok(session))
                  case None          =>
                    Errors.craftResponseResult(
                      s"Invalid session",
                      Unauthorized,
                      req,
                      None,
                      Some("errors.invalid.session"),
                      attrs = attrs,
                      maybeRoute = Some(route)
                    )
                }
              case Some(session) =>
                FastFuture.successful(Ok(session.profile.as[JsObject] ++ Json.obj("access_type" -> "session")))
            }
          }
        case _                                                                           =>
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

  def removePrivateAppsCookies() =
    actionBuilder.async { req =>
      val attrs = TypedMap.empty

      lazy val privateAppNotConfigure = Errors.craftResponseResult(
        s"Private apps are not configured",
        InternalServerError,
        req,
        None,
        Some("errors.service.auth.not.configured"),
        attrs = attrs
      )

      withRoute(req, attrs) {
        case route if !route.legacy.privateApp                                           => privateAppNotConfigure
        case route if route.legacy.privateApp && route.id != env.backOfficeDescriptor.id =>
          GatewayRequestHandler.removePrivateAppsCookies(route, req, attrs)
        case _                                                                           => privateAppNotConfigure
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
      if (logger.isTraceEnabled)
        logger.trace(
          s"redirectToHttps from ${protocol}://$domain${req.relativeUri} to ${env.rootScheme}$domain${req.relativeUri}"
        )
      Redirect(s"${env.rootScheme}$domain${req.relativeUri}").withHeaders("otoroshi-redirect-to" -> "https")
    }

  def redirectToMainDomain() =
    actionBuilder { req =>
      val domain: String = env.redirections.foldLeft(req.theDomain)((domain, item) => domain.replace(item, env.domain))
      val protocol       = req.theProtocol
      if (logger.isDebugEnabled)
        logger.debug(
          s"redirectToMainDomain from $protocol://${req.theDomain}${req.relativeUri} to $protocol://$domain${req.relativeUri}"
        )
      Redirect(s"$protocol://$domain${req.relativeUri}")
    }

  def decodeBase64(encoded: String): String = new String(OtoroshiClaim.decoder.decode(encoded), Charsets.UTF_8)
}
