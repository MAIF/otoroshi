package gateway

import java.net.URLEncoder
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicLong}

import actions.{PrivateAppsAction, PrivateAppsActionContext}
import akka.NotUsed
import akka.actor.{Actor, Props}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.util.FastFuture._
import akka.stream.Materializer
import akka.stream.scaladsl.{Concat, Merge, Sink, Source}
import akka.util.ByteString
import auth.AuthModuleConfig
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.google.common.base.Charsets
import controllers.routes
import env.{Env, SidecarConfig}
import events._
import models._
import utils.{MaxLengthLimiter, RegexPool, UrlSanitizer}
import org.joda.time.DateTime
import play.api.Logger
import play.api.http.{Status => _, _}
import play.api.libs.json.{JsArray, JsString, Json}
import play.api.libs.streams.Accumulator
import play.api.libs.ws.{DefaultWSCookie, EmptyBody, SourceBody, StandaloneWSRequest}
import play.api.mvc.Results._
import play.api.mvc._
import play.api.routing.Router
import security.{IdGenerator, OtoroshiClaim}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NoStackTrace
import scala.util.{Failure, Success, Try}
import utils.RequestImplicits._
import otoroshi.script.Implicits._
import utils.http.Implicits._
import play.libs.ws.WSCookie

case class ProxyDone(status: Int, isChunked: Boolean, upstreamLatency: Long, headersOut: Seq[Header])

class ErrorHandler()(implicit env: Env) extends HttpErrorHandler {

  implicit val ec = env.otoroshiExecutionContext

  lazy val logger = Logger("otoroshi-error-handler")

  def onClientError(request: RequestHeader, statusCode: Int, mess: String) = {
    val message       = Option(mess).filterNot(_.trim.isEmpty).getOrElse("An error occurred")
    val remoteAddress = request.headers.get("X-Forwarded-For").getOrElse(request.remoteAddress)
    logger.error(
      s"Client Error: $message from ${remoteAddress} on ${request.method} ${request.theProtocol}://${request.host}${request.relativeUri} ($statusCode) - ${request.headers.toSimpleMap
        .mkString(";")}"
    )
    env.metrics.counter("errors.client").inc()
    env.datastores.serviceDescriptorDataStore.updateMetricsOnError()
    Errors.craftResponseResult(s"Client Error: an error occurred on ${request.relativeUri} ($statusCode)",
                               Status(statusCode),
                               request,
                               None,
                               Some("errors.client.error"))
  }

  def onServerError(request: RequestHeader, exception: Throwable) = {
    // exception.printStackTrace()
    val remoteAddress = request.headers.get("X-Forwarded-For").getOrElse(request.remoteAddress)
    logger.error(
      s"Server Error ${exception.getMessage} from ${remoteAddress} on ${request.method} ${request.theProtocol}://${request.host}${request.relativeUri} - ${request.headers.toSimpleMap
        .mkString(";")}",
      exception
    )
    env.metrics.counter("errors.server").inc()
    env.datastores.serviceDescriptorDataStore.updateMetricsOnError()
    Errors.craftResponseResult("An error occurred ...", InternalServerError, request, None, Some("errors.server.error"))
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

  override def routeRequest(request: RequestHeader): Option[Handler] = {
    if (env.globalMaintenanceMode) {
      if (request.relativeUri.contains("__otoroshi_assets")) {
        super.routeRequest(request)
      } else {
        Some(globalMaintenanceMode())
      }
    } else {
      val isSecured = getSecuredFor(request)
      val protocol  = getProtocolFor(request)
      val url       = ByteString(s"$protocol://${request.host}${request.relativeUri}")
      val cookies   = request.cookies.map(_.value).map(ByteString.apply)
      val headers   = request.headers.toSimpleMap.values.map(ByteString.apply)
      // logger.trace(s"[SIZE] url: ${url.size} bytes, cookies: ${cookies.map(_.size).mkString(", ")}, headers: ${headers.map(_.size).mkString(", ")}")
      if (env.clusterConfig.mode == cluster.ClusterMode.Worker && env.clusterAgent.cannotServeRequests()) {
        Some(clusterError("Waiting for first Otoroshi leader sync."))
      } else if (url.size > (4 * 1024)) {
        Some(tooBig("URL should be smaller than 4 Kb"))
      } else if (cookies.exists(_.size > (16 * 1024))) {
        Some(tooBig("Cookies should be smaller than 16 Kb"))
      } else if (headers.exists(_.size > (16 * 1024))) {
        Some(tooBig("Headers should be smaller than 16 Kb"))
      } else {
        val toHttps = env.exposedRootSchemeIsHttps
        val host    = if (request.host.contains(":")) request.host.split(":")(0) else request.host
        host match {
          case str if matchRedirection(str)                                          => Some(redirectToMainDomain())
          case _ if request.relativeUri.contains("__otoroshi_assets")                => super.routeRequest(request)
          case _ if request.relativeUri.startsWith("/__otoroshi_private_apps_login") => Some(setPrivateAppsCookies())
          case _ if request.relativeUri.startsWith("/__otoroshi_private_apps_logout") =>
            Some(removePrivateAppsCookies())
          case _ if request.relativeUri.startsWith("/.well-known/otoroshi/login")  => Some(setPrivateAppsCookies())
          case _ if request.relativeUri.startsWith("/.well-known/otoroshi/logout") => Some(removePrivateAppsCookies())
          case _ if request.relativeUri.startsWith("/.well-known/otoroshi/me")     => Some(myProfile())
          case env.backOfficeHost if !isSecured && toHttps                         => Some(redirectToHttps())
          case env.privateAppsHost if !isSecured && toHttps                        => Some(redirectToHttps())
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

  def xForwardedHeader(desc: ServiceDescriptor, request: RequestHeader): Seq[(String, String)] = {
    if (desc.xForwardedHeaders) {
      val xForwardedFor = request.headers
        .get("X-Forwarded-For")
        .map(v => v + ", " + request.remoteAddress)
        .getOrElse(request.remoteAddress)
      val xForwardedProto = getProtocolFor(request)
      val xForwardedHost  = request.headers.get("X-Forwarded-Host").getOrElse(request.host)
      Seq("X-Forwarded-For"   -> xForwardedFor,
          "X-Forwarded-Host"  -> xForwardedHost,
          "X-Forwarded-Proto" -> xForwardedProto)
    } else {
      Seq.empty[(String, String)]
    }
  }

  def setPrivateAppsCookies() = actionBuilder.async { req =>
    val redirectToOpt: Option[String] = req.queryString.get("redirectTo").map(_.last)
    val sessionIdOpt: Option[String]  = req.queryString.get("sessionId").map(_.last)
    val hostOpt: Option[String]       = req.queryString.get("host").map(_.last)
    val cookiePrefOpt: Option[String] = req.queryString.get("cp").map(_.last)
    val maOpt: Option[Int]            = req.queryString.get("ma").map(_.last).map(_.toInt)
    (redirectToOpt, sessionIdOpt, hostOpt, cookiePrefOpt, maOpt) match {
      case (Some(redirectTo), Some(sessionId), Some(host), Some(cp), ma) =>
        FastFuture.successful(
          Redirect(redirectTo).withCookies(
            env.createPrivateSessionCookiesWithSuffix(host, sessionId, cp, ma.getOrElse(86400)): _*
          )
        )
      case _ =>
        Errors.craftResponseResult("Missing parameters", BadRequest, req, None, Some("errors.missing.parameters"))
    }
  }

  def withAuthConfig(descriptor: ServiceDescriptor,
                     req: RequestHeader)(f: AuthModuleConfig => Future[Result]): Future[Result] = {
    descriptor.authConfigRef match {
      case None =>
        Errors.craftResponseResult(
          "Auth. config. ref not found on the descriptor",
          Results.InternalServerError,
          req,
          Some(descriptor),
          Some("errors.auth.config.ref.not.found")
        )
      case Some(ref) => {
        env.datastores.authConfigsDataStore.findById(ref).flatMap {
          case None =>
            Errors.craftResponseResult(
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

  def myProfile() = actionBuilder.async { req =>
    import utils.future.Implicits._

    implicit val request = req

    env.datastores.globalConfigDataStore.singleton().flatMap { globalConfig =>
      ServiceLocation(req.host, globalConfig) match {
        case None => {
          Errors.craftResponseResult(s"Service not found", NotFound, req, None, Some("errors.service.not.found"))
        }
        case Some(ServiceLocation(domain, serviceEnv, subdomain)) => {
          env.datastores.serviceDescriptorDataStore
            .find(ServiceDescriptorQuery(subdomain, serviceEnv, domain, req.relativeUri, req.headers.toSimpleMap), req)
            .flatMap {
              case None => {
                Errors.craftResponseResult(s"Service not found", NotFound, req, None, Some("errors.service.not.found"))
              }
              case Some(desc) if !desc.enabled => {
                Errors.craftResponseResult(s"Service not found", NotFound, req, None, Some("errors.service.not.found"))
              }
              case Some(descriptor) if !descriptor.privateApp => {
                Errors.craftResponseResult(s"Service not found", NotFound, req, None, Some("errors.service.not.found"))
              }
              case Some(descriptor) if descriptor.privateApp && descriptor.id != env.backOfficeDescriptor.id => {
                withAuthConfig(descriptor, req) { auth =>
                  val expected = "oto-papps-" + auth.cookieSuffix(descriptor)
                  req.cookies
                    .get(expected)
                    .flatMap { cookie =>
                      env.extractPrivateSessionId(cookie)
                    }
                    .map { id =>
                      env.datastores.privateAppsUserDataStore.findById(id).flatMap {
                        case Some(session) => FastFuture.successful(Ok(session.profile))
                        case None =>
                          Errors.craftResponseResult(s"Session not found",
                                                     NotFound,
                                                     req,
                                                     None,
                                                     Some("errors.session.not.found"))
                      }
                    } getOrElse {
                    Errors
                      .craftResponseResult(s"Session not found", NotFound, req, None, Some("errors.session.not.found"))
                  }
                }
              }
              case _ => {
                Errors.craftResponseResult(s"Service not found", NotFound, req, None, Some("errors.service.not.found"))
              }
            }
        }
      }
    }
  }

  def removePrivateAppsCookies() = actionBuilder.async { req =>
    import utils.future.Implicits._

    implicit val request = req

    env.datastores.globalConfigDataStore.singleton().flatMap { globalConfig =>
      ServiceLocation(req.host, globalConfig) match {
        case None => {
          Errors.craftResponseResult(s"Service not found for URL ${req.host}::${req.relativeUri}",
                                     NotFound,
                                     req,
                                     None,
                                     Some("errors.service.not.found"))
        }
        case Some(ServiceLocation(domain, serviceEnv, subdomain)) => {
          env.datastores.serviceDescriptorDataStore
            .find(ServiceDescriptorQuery(subdomain, serviceEnv, domain, req.relativeUri, req.headers.toSimpleMap), req)
            .flatMap {
              case None => {
                Errors.craftResponseResult(s"Service not found", NotFound, req, None, Some("errors.service.not.found"))
              }
              case Some(desc) if !desc.enabled => {
                Errors.craftResponseResult(s"Service not found", NotFound, req, None, Some("errors.service.not.found"))
              }
              case Some(descriptor) if !descriptor.privateApp => {
                Errors.craftResponseResult(s"Private apps are not configured",
                                           NotFound,
                                           req,
                                           None,
                                           Some("errors.service.auth.not.configured"))
              }
              case Some(descriptor) if descriptor.privateApp && descriptor.id != env.backOfficeDescriptor.id => {
                withAuthConfig(descriptor, req) { auth =>
                  auth.authModule(globalConfig).paLogout(req, globalConfig, descriptor).map {
                    case None => {
                      val cookieOpt = request.cookies.find(c => c.name.startsWith("oto-papps-"))
                      cookieOpt.flatMap(env.extractPrivateSessionId).map { id =>
                        env.datastores.privateAppsUserDataStore.findById(id).map(_.foreach(_.delete()))
                      }
                      val finalRedirect = req.getQueryString("redirect").getOrElse(req.host)
                      val redirectTo = env.rootScheme + env.privateAppsHost + env.privateAppsPort
                        .map(a => s":$a")
                        .getOrElse("") + controllers.routes.AuthController
                        .confidentialAppLogout()
                        .url + s"?redirectTo=${finalRedirect}&host=${req.host}&cp=${auth.cookieSuffix(descriptor)}"
                      logger.trace("should redirect to " + redirectTo)
                      Redirect(redirectTo)
                        .discardingCookies(env.removePrivateSessionCookies(req.host, descriptor, auth): _*)
                    }
                    case Some(logoutUrl) => {
                      val cookieOpt = request.cookies.find(c => c.name.startsWith("oto-papps-"))
                      cookieOpt.flatMap(env.extractPrivateSessionId).map { id =>
                        env.datastores.privateAppsUserDataStore.findById(id).map(_.foreach(_.delete()))
                      }
                      val finalRedirect = req.getQueryString("redirect").getOrElse(s"http://${req.host}")
                      val redirectTo = env.rootScheme + env.privateAppsHost + env.privateAppsPort
                        .map(a => s":$a")
                        .getOrElse("") + controllers.routes.AuthController
                        .confidentialAppLogout()
                        .url + s"?redirectTo=${finalRedirect}&host=${req.host}&cp=${auth.cookieSuffix(descriptor)}"
                      val actualRedirectUrl = logoutUrl.replace("${redirect}", URLEncoder.encode(redirectTo, "UTF-8"))
                      logger.trace("should redirect to " + actualRedirectUrl)
                      Redirect(actualRedirectUrl)
                        .discardingCookies(env.removePrivateSessionCookies(req.host, descriptor, auth): _*)
                    }
                  }
                }
              }
              case _ => {
                Errors.craftResponseResult(s"Private apps are not configured",
                                           NotFound,
                                           req,
                                           None,
                                           Some("errors.service.auth.not.configured"))
              }
            }
        }
      }
    }
  }

  def clusterError(message: String) = actionBuilder.async { req =>
    Errors.craftResponseResult(message, InternalServerError, req, None, Some("errors.no.state.yet"))
  }

  def tooBig(message: String) = actionBuilder.async { req =>
    Errors.craftResponseResult(message, BadRequest, req, None, Some("errors.entity.too.big"))
  }

  def globalMaintenanceMode() = actionBuilder.async { req =>
    Errors.craftResponseResult(
      "Service in maintenance mode",
      ServiceUnavailable,
      req,
      None,
      Some("errors.service.in.maintenance")
    )
  }

  def isPrivateAppsSessionValid(req: Request[Source[ByteString, _]],
                                desc: ServiceDescriptor): Future[Option[PrivateAppsUser]] = {
    env.datastores.authConfigsDataStore.findById(desc.authConfigRef.get).flatMap {
      case None => FastFuture.successful(None)
      case Some(auth) => {
        val expected = "oto-papps-" + auth.cookieSuffix(desc)
        req.cookies
          .get(expected)
          .flatMap { cookie =>
            env.extractPrivateSessionId(cookie)
          }
          .map { id =>
            env.datastores.privateAppsUserDataStore.findById(id)
          } getOrElse {
          FastFuture.successful(None)
        }
      }
    }
  }

  def redirectToHttps() = actionBuilder { req =>
    val domain   = req.domain
    val protocol = getProtocolFor(req)
    logger.trace(
      s"redirectToHttps from ${protocol}://$domain${req.relativeUri} to ${env.rootScheme}$domain${req.relativeUri}"
    )
    Redirect(s"${env.rootScheme}$domain${req.relativeUri}").withHeaders("otoroshi-redirect-to" -> "https")
  }

  def redirectToMainDomain() = actionBuilder { req =>
    val domain: String = env.redirections.foldLeft(req.domain)((domain, item) => domain.replace(item, env.domain))
    val protocol       = getProtocolFor(req)
    logger.debug(
      s"redirectToMainDomain from $protocol://${req.domain}${req.relativeUri} to $protocol://$domain${req.relativeUri}"
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

  def applyJwtVerifier(service: ServiceDescriptor,
                       req: RequestHeader)(f: JwtInjection => Future[Result])(implicit env: Env): Future[Result] = {
    if (service.jwtVerifier.enabled) {
      service.jwtVerifier.shouldBeVerified(req.path).flatMap {
        case false => f(JwtInjection())
        case true => {
          logger.debug(s"Applying JWT verification for service ${service.id}:${service.name}")
          service.jwtVerifier.verify(req, service)(f)
        }
      }
    } else {
      f(JwtInjection())
    }
  }

  def applySidecar(service: ServiceDescriptor, remoteAddress: String, req: RequestHeader)(
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
          None
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
              None
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

  def passWithHeadersVerification(desc: ServiceDescriptor, req: RequestHeader, apiKey: Option[ApiKey], paUsr: Option[PrivateAppsUser])(f: => Future[Result]): Future[Result] = {
    if (desc.headersVerification.isEmpty) {
      f
    } else {
      val inputHeaders = req.headers.toSimpleMap.mapValues(v => HeadersExpressionLanguage.apply(v, desc, apiKey, paUsr))
      desc.headersVerification.map(tuple => inputHeaders.get(tuple._1).exists(_ == tuple._2)).find(_ == false) match {
        case Some(_) =>
          Errors.craftResponseResult(
            "Missing header(s)",
            Results.BadRequest,
            req,
            Some(desc),
            Some("errors.missing.headers")
          )
        case None => f
      }
    }
  }

  def passWithReadOnly(readOnly: Boolean, req: RequestHeader)(f: => Future[Result]): Future[Result] = {
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
              Some("errors.method.not.allowed")
            )
        }
    }
  }

  @inline
  def getProtocolFor(req: RequestHeader): String = {
    req.headers
      .get("X-Forwarded-Proto")
      .orElse(req.headers.get("X-Forwarded-Protocol"))
      .map(_ == "https")
      .orElse(Some(req.secure))
      .map {
        case true  => "https"
        case false => "http"
      }
      .getOrElse("http")
  }

  @inline
  def getSecuredFor(req: RequestHeader): Boolean = {
    getProtocolFor(req) match {
      case "http"  => false
      case "https" => true
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
            descriptor.secComSettings.asAlgorithm(models.OutputMode)(env) match {
              case None => false
              case Some(algo) => {
                Try {
                  val jwt = JWT
                    .require(algo)
                    .withAudience(env.Headers.OtoroshiIssuer)
                    .withClaim("state-resp", stateValue)
                    .acceptLeeway(10)
                    .build()
                    .verify(resp)
                  val exp =
                    Option(jwt.getClaim("exp")).filterNot(_.isNull).map(_.asLong())
                  val iat =
                    Option(jwt.getClaim("iat")).filterNot(_.isNull).map(_.asLong())
                  if (exp.isEmpty || iat.isEmpty) {
                    false
                  } else {
                    if ((exp.get - iat.get) <= 30) { // seconds
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
    val callDate            = DateTime.now()
    val reqNumber           = reqCounter.incrementAndGet()
    val remoteAddress       = req.headers.get("X-Forwarded-For").getOrElse(req.remoteAddress)
    val isSecured           = getSecuredFor(req)
    val from                = req.headers.get("X-Forwarded-For").getOrElse(req.remoteAddress)
    val counterIn           = new AtomicLong(0L)
    val counterOut          = new AtomicLong(0L)
    val start               = System.currentTimeMillis()
    val bodyAlreadyConsumed = new AtomicBoolean(false)
    val protocol            = getProtocolFor(req)

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
                                   BadGateway,
                                   req,
                                   None,
                                   Some("errors.cant.process.more.request"))
      } else {

        ServiceLocation(req.host, globalConfig) match {
          case None =>
            Errors.craftResponseResult(s"Service not found", NotFound, req, None, Some("errors.service.not.found"))
          case Some(ServiceLocation(domain, serviceEnv, subdomain)) => {
            val uriParts = req.relativeUri.split("/").toSeq

            env.datastores.serviceDescriptorDataStore
              .find(ServiceDescriptorQuery(subdomain, serviceEnv, domain, req.relativeUri, req.headers.toSimpleMap), req)
              .fast
              .flatMap {
                case None =>
                  // val query = ServiceDescriptorQuery(subdomain, serviceEnv, domain, req.relativeUri, req.headers.toSimpleMap)
                  // logger.info(s"Downstream service not found for $query")
                  Errors
                    .craftResponseResult(s"Service not found", NotFound, req, None, Some("errors.service.not.found"))
                case Some(desc) if !desc.enabled =>
                  Errors
                    .craftResponseResult(s"Service not found", NotFound, req, None, Some("errors.service.not.found"))
                case Some(rawDesc) if rawDesc.redirection.enabled && rawDesc.redirection.hasValidCode => {
                  FastFuture.successful(
                    Results
                      .Status(rawDesc.redirection.code)
                      .withHeaders("Location" -> rawDesc.redirection.formattedTo(req))
                  )
                }
                case Some(rawDesc) => {
                  if (rawDesc.id != env.backOfficeServiceId && globalConfig.maintenanceMode) {
                    Errors.craftResponseResult(
                      "Service in maintenance mode",
                      ServiceUnavailable,
                      req,
                      Some(rawDesc),
                      Some("errors.service.in.maintenance")
                    )
                  } else {
                    passWithHeadersVerification(rawDesc, req, None, None) {
                      passWithReadOnly(rawDesc.readOnly, req) {
                        applyJwtVerifier(rawDesc, req) { jwtInjection =>
                          applySidecar(rawDesc, remoteAddress, req) { desc =>
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
                                    jwtInjection.additionalCookies
                                      .map(t => Cookie(t._1, t._2))
                                      .toSeq //Seq.empty[play.api.mvc.Cookie]
                                  else if (maybeCanaryId.isDefined)
                                    jwtInjection.additionalCookies
                                      .map(t => Cookie(t._1, t._2))
                                      .toSeq //Seq.empty[play.api.mvc.Cookie]
                                  else
                                    Seq(
                                      play.api.mvc.Cookie(
                                        name = "otoroshi-canary",
                                        value = s"${env.sign(canaryId)}::$canaryId",
                                        maxAge = Some(2592000),
                                        path = "/",
                                        domain = Some(req.domain),
                                        httpOnly = false
                                      )
                                    ) ++ jwtInjection.additionalCookies.map(t => Cookie(t._1, t._2))
                                } ++ (if (desc.targetsLoadBalancing.needTrackingCookie) {
                                  Seq(
                                    play.api.mvc.Cookie(
                                      name = "otoroshi-tracking",
                                      value = trackingId,
                                      maxAge = Some(2592000),
                                      path = "/",
                                      domain = Some(req.domain),
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
                                                     apiKey: Option[ApiKey] = None,
                                                     paUsr: Option[PrivateAppsUser] = None): Future[Result] = {
                                    desc.validateClientCertificates(req, apiKey, paUsr, config) {
                                      passWithReadOnly(apiKey.map(_.readOnly).getOrElse(false), req) {
                                        if (config.useCircuitBreakers && descriptor.clientConfig.useCircuitBreaker) {
                                          val cbStart = System.currentTimeMillis()
                                          val counter = new AtomicInteger(0)
                                          val relUri = req.relativeUri
                                          val cachedPath: String = descriptor.clientConfig.timeouts(relUri).map(_ => relUri).getOrElse("")
                                          env.circuitBeakersHolder
                                            .get(desc.id + cachedPath, () => new ServiceDescriptorCircuitBreaker())
                                            .call(
                                              descriptor,
                                              reqNumber.toString,
                                              trackingId,
                                              req.relativeUri,
                                              req,
                                              bodyAlreadyConsumed,
                                              s"${req.method} ${req.relativeUri}",
                                              counter,
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
                                                BadGateway,
                                                req,
                                                Some(descriptor),
                                                Some("errors.request.timeout"),
                                                duration = System.currentTimeMillis - start,
                                                overhead = (System.currentTimeMillis() - secondStart) + firstOverhead,
                                                cbDuration = System.currentTimeMillis - cbStart,
                                                callAttempts = counter.get()
                                              )
                                            case RequestTimeoutException =>
                                              Errors.craftResponseResult(
                                                s"Something went wrong, the downstream service does not respond quickly enough, you should try later. Thanks for your understanding",
                                                BadGateway,
                                                req,
                                                Some(descriptor),
                                                Some("errors.request.timeout"),
                                                duration = System.currentTimeMillis - start,
                                                overhead = (System.currentTimeMillis() - secondStart) + firstOverhead,
                                                cbDuration = System.currentTimeMillis - cbStart,
                                                callAttempts = counter.get()
                                              )
                                            case _: scala.concurrent.TimeoutException =>
                                              Errors.craftResponseResult(
                                                s"Something went wrong, the downstream service does not respond quickly enough, you should try later. Thanks for your understanding",
                                                BadGateway,
                                                req,
                                                Some(descriptor),
                                                Some("errors.request.timeout"),
                                                duration = System.currentTimeMillis - start,
                                                overhead = (System.currentTimeMillis() - secondStart) + firstOverhead,
                                                cbDuration = System.currentTimeMillis - cbStart,
                                                callAttempts = counter.get()
                                              )
                                            case AllCircuitBreakersOpenException =>
                                              Errors.craftResponseResult(
                                                s"Something went wrong, the downstream service seems a little bit overwhelmed, you should try later. Thanks for your understanding",
                                                BadGateway,
                                                req,
                                                Some(descriptor),
                                                Some("errors.circuit.breaker.open"),
                                                duration = System.currentTimeMillis - start,
                                                overhead = (System.currentTimeMillis() - secondStart) + firstOverhead,
                                                cbDuration = System.currentTimeMillis - cbStart,
                                                callAttempts = counter.get()
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
                                                overhead = (System.currentTimeMillis() - secondStart) + firstOverhead,
                                                cbDuration = System.currentTimeMillis - cbStart,
                                                callAttempts = counter.get()
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
                                                overhead = (System.currentTimeMillis() - secondStart) + firstOverhead,
                                                cbDuration = System.currentTimeMillis - cbStart,
                                                callAttempts = counter.get()
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
                                                overhead = (System.currentTimeMillis() - secondStart) + firstOverhead,
                                                cbDuration = System.currentTimeMillis - cbStart,
                                                callAttempts = counter.get()
                                              )
                                          }
                                        } else {
                                          val targets: Seq[Target] = descriptor.targets.filter(_.predicate.matches(reqNumber.toString)).flatMap(t => Seq.fill(t.weight)(t))
                                          val target = descriptor.targetsLoadBalancing.select(reqNumber.toString, trackingId, req, targets, descriptor)
                                          //val index = reqCounter.get() % (if (targets.nonEmpty) targets.size else 1)
                                          // Round robin loadbalancing is happening here !!!!!
                                          //val target = targets.apply(index.toInt)
                                          actuallyCallDownstream(target, apiKey, paUsr, 0L, 1)
                                        }
                                      }
                                    }
                                  }

                                  def actuallyCallDownstream(target: Target,
                                                             apiKey: Option[ApiKey] = None,
                                                             paUsr: Option[PrivateAppsUser] = None,
                                                             cbDuration: Long,
                                                             callAttempts: Int): Future[Result] = {
                                    val snowflake        = env.snowflakeGenerator.nextIdStr()
                                    val requestTimestamp = DateTime.now().toString("yyyy-MM-dd'T'HH:mm:ss.SSSZZ")
                                    val jti              = IdGenerator.uuid
                                    val stateValue       = IdGenerator.extendedToken(128)
                                    val stateToken: String = descriptor.secComVersion match {
                                      case SecComVersion.V1 => stateValue
                                      case SecComVersion.V2 =>
                                        OtoroshiClaim(
                                          iss = env.Headers.OtoroshiIssuer,
                                          sub = env.Headers.OtoroshiIssuer,
                                          aud = descriptor.name,
                                          exp = DateTime.now().plusSeconds(30).toDate.getTime,
                                          iat = DateTime.now().toDate.getTime,
                                          jti = jti
                                        ).withClaim("state", stateValue).serialize(descriptor.secComSettings)
                                    }
                                    val rawUri   = req.relativeUri.substring(1)
                                    val uriParts = rawUri.split("/").toSeq
                                    val uri: String =
                                      descriptor.matchingRoot.map(m => req.relativeUri.replace(m, "")).getOrElse(rawUri)
                                    val scheme =
                                      if (descriptor.redirectToLocal) descriptor.localScheme else target.scheme
                                    val host                   = if (descriptor.redirectToLocal) descriptor.localHost else target.host
                                    val root                   = descriptor.root
                                    val url                    = s"$scheme://$host$root$uri"
                                    lazy val currentReqHasBody = hasBody(req)
                                    // val queryString = req.queryString.toSeq.flatMap { case (key, values) => values.map(v => (key, v)) }
                                    val fromOtoroshi = req.headers
                                      .get(env.Headers.OtoroshiRequestId)
                                      .orElse(req.headers.get(env.Headers.OtoroshiGatewayParentRequest))
                                    val promise = Promise[ProxyDone]

                                    val claim = OtoroshiClaim(
                                      iss = env.Headers.OtoroshiIssuer,
                                      sub = paUsr
                                        .filter(_ => descriptor.privateApp)
                                        .map(k => s"pa:${k.email}")
                                        .orElse(apiKey.map(k => s"apikey:${k.clientId}"))
                                        .getOrElse("--"),
                                      aud = descriptor.name,
                                      exp = DateTime.now().plusSeconds(30).toDate.getTime,
                                      iat = DateTime.now().toDate.getTime,
                                      jti = IdGenerator.uuid
                                    ).withClaim("email", paUsr.map(_.email))
                                      .withClaim("name", paUsr.map(_.name).orElse(apiKey.map(_.clientName)))
                                      .withClaim("picture", paUsr.flatMap(_.picture))
                                      .withClaim("user_id", paUsr.flatMap(_.userId).orElse(apiKey.map(_.clientId)))
                                      .withClaim("given_name", paUsr.flatMap(_.field("given_name")))
                                      .withClaim("family_name", paUsr.flatMap(_.field("family_name")))
                                      .withClaim("gender", paUsr.flatMap(_.field("gender")))
                                      .withClaim("locale", paUsr.flatMap(_.field("locale")))
                                      .withClaim("nickname", paUsr.flatMap(_.field("nickname")))
                                      .withClaims(paUsr.flatMap(_.otoroshiData).orElse(apiKey.map(_.metadataJson)))
                                      .withClaim("metadata",
                                                 paUsr
                                                   .flatMap(_.otoroshiData)
                                                   .orElse(apiKey.map(_.metadataJson))
                                                   .map(m => Json.stringify(Json.toJson(m))))
                                      .withClaim("tags", apiKey.map(a => Json.stringify(JsArray(a.tags.map(JsString.apply)))))
                                      .withClaim("user", paUsr.map(u => Json.stringify(u.asJsonCleaned)))
                                      .withClaim("apikey",
                                                 apiKey.map(
                                                   ak =>
                                                     Json.stringify(
                                                       Json.obj(
                                                         "clientId"   -> ak.clientId,
                                                         "clientName" -> ak.clientName,
                                                         "metadata"   -> ak.metadata,
                                                         "tags" -> ak.tags
                                                       )
                                                   )
                                                 ))
                                      .serialize(desc.secComSettings)(env)
                                    logger.trace(s"Claim is : $claim")
                                    val headersIn: Seq[(String, String)] =
                                    (req.headers.toMap.toSeq
                                      .flatMap(c => c._2.map(v => (c._1, v))) //.map(tuple => (tuple._1, tuple._2.mkString(","))) //.toSimpleMap
                                      .filterNot(
                                        t =>
                                          if (t._1.toLowerCase == "content-type" && !currentReqHasBody) true
                                          else if (t._1.toLowerCase == "content-length") true
                                          else false
                                      )
                                      .filterNot(t => headersInFiltered.contains(t._1.toLowerCase)) ++ Map(
                                      env.Headers.OtoroshiProxiedHost -> req.headers.get("Host").getOrElse("--"),
                                      //"Host"                               -> host,
                                      "Host" -> (if (desc.overrideHost) host
                                                 else req.headers.get("Host").getOrElse("--")),
                                      env.Headers.OtoroshiRequestId        -> snowflake,
                                      env.Headers.OtoroshiRequestTimestamp -> requestTimestamp
                                    ) ++ (if (descriptor.enforceSecureCommunication && descriptor.sendStateChallenge) {
                                            Map(
                                              env.Headers.OtoroshiState -> stateToken,
                                              env.Headers.OtoroshiClaim -> claim
                                            )
                                          } else if (descriptor.enforceSecureCommunication && !descriptor.sendStateChallenge) {
                                            Map(
                                              env.Headers.OtoroshiClaim -> claim
                                            )
                                          } else {
                                            Map.empty[String, String]
                                          }) ++
                                    req.headers
                                      .get("Content-Length")
                                      .map(l => {
                                        Map(
                                          "Content-Length" -> (l.toInt + snowMonkeyContext.trailingRequestBodySize).toString
                                        )
                                      })
                                      .getOrElse(Map.empty[String, String]) ++
                                    descriptor.additionalHeaders.filter(t => t._1.trim.nonEmpty)
                                      .mapValues(v => HeadersExpressionLanguage.apply(v, descriptor, apiKey, paUsr)) ++ fromOtoroshi
                                      .map(v => Map(env.Headers.OtoroshiGatewayParentRequest -> fromOtoroshi.get))
                                      .getOrElse(Map.empty[String, String]) ++ jwtInjection.additionalHeaders).toSeq
                                      .filterNot(t => jwtInjection.removeHeaders.contains(t._1)) ++ xForwardedHeader(
                                      desc,
                                      req
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
                                          scheme = getProtocolFor(req),
                                          host = req.host,
                                          uri = req.relativeUri
                                        )
                                      ).toAnalytics()
                                    }
                                    val quotas: Future[RemainingQuotas] =
                                      apiKey.map(_.updateQuotas()).getOrElse(FastFuture.successful(RemainingQuotas()))
                                    promise.future.andThen {
                                      case Success(resp) => {

                                        val actualDuration: Long = System.currentTimeMillis() - start
                                        val duration: Long =
                                          if (descriptor.id == env.backOfficeServiceId && actualDuration > 300L) 300L
                                          else actualDuration

                                        analyticsQueue ! AnalyticsQueueEvent(descriptor,
                                                                             duration,
                                                                             overhead,
                                                                             counterIn.get(),
                                                                             counterOut.get(),
                                                                             resp.upstreamLatency,
                                                                             globalConfig)

                                        BestResponseTime.incrementAverage(descriptor, target, duration)

                                        quotas.andThen {
                                          case Success(q) => {
                                            val fromLbl =
                                              req.headers.get(env.Headers.OtoroshiVizFromLabel).getOrElse("internet")
                                            val viz: OtoroshiViz = OtoroshiViz(
                                              to = descriptor.id,
                                              toLbl = descriptor.name,
                                              from = req.headers.get(env.Headers.OtoroshiVizFrom).getOrElse("internet"),
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
                                                scheme = getProtocolFor(req),
                                                host = req.host,
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
                                              viz = Some(viz)
                                            )
                                            evt.toAnalytics()
                                            if (descriptor.logAnalyticsOnServer) {
                                              evt.log()
                                            }
                                          }
                                        }(env.otoroshiExecutionContext) // pressure EC
                                      }
                                    }(env.otoroshiExecutionContext) // pressure EC
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
                                      url = s"${req.theProtocol}://${req.host}${req.relativeUri}",
                                      method = req.method,
                                      headers = req.headers.toSimpleMap,
                                      cookies = wsCookiesIn
                                    )
                                    val otoroshiRequest = otoroshi.script.HttpRequest(
                                      url = url,
                                      method = req.method,
                                      headers = headersIn.toMap,
                                      cookies = wsCookiesIn
                                    )
                                    val upstreamStart = System.currentTimeMillis()
                                    val finalRequest = descriptor
                                      .transformRequest(
                                        snowflake = snowflake,
                                        rawRequest = rawRequest,
                                        otoroshiRequest = otoroshiRequest,
                                        desc = descriptor,
                                        apiKey = apiKey,
                                        user = paUsr
                                      )
                                    val finalBody = descriptor.transformRequestBody(
                                      body = lazySource,
                                      snowflake = snowflake,
                                      rawRequest = rawRequest,
                                      otoroshiRequest = otoroshiRequest,
                                      desc = descriptor,
                                      apiKey = apiKey,
                                      user = paUsr
                                    )
                                    finalRequest.flatMap {
                                      case Left(badResult) => {
                                        quotas.fast.map { remainingQuotas =>
                                          val _headersOut: Seq[(String, String)] = badResult.header.headers.toSeq
                                            .filterNot(t => headersOutFiltered.contains(t._1.toLowerCase)) ++ (
                                            if (descriptor.sendOtoroshiHeadersBack) {
                                              Seq(
                                                env.Headers.OtoroshiRequestId        -> snowflake,
                                                env.Headers.OtoroshiRequestTimestamp -> requestTimestamp,
                                                env.Headers.OtoroshiProxyLatency     -> s"$overhead",
                                                env.Headers.OtoroshiUpstreamLatency  -> s"0"
                                              )
                                            } else {
                                              Seq.empty[(String, String)]
                                            }
                                            ) ++ Some(canaryId)
                                            .filter(_ => desc.canary.enabled)
                                            .map(
                                              _ => env.Headers.OtoroshiTrackerId -> s"${env.sign(canaryId)}::$canaryId"
                                            ) ++ (if (descriptor.sendOtoroshiHeadersBack && apiKey.isDefined) {
                                            Seq(
                                              env.Headers.OtoroshiDailyCallsRemaining   -> remainingQuotas.remainingCallsPerDay.toString,
                                              env.Headers.OtoroshiMonthlyCallsRemaining -> remainingQuotas.remainingCallsPerMonth.toString
                                            )
                                          } else {
                                            Seq.empty[(String, String)]
                                          }) ++ descriptor.cors.asHeaders(req) ++ desc.additionalHeadersOut.mapValues(v => HeadersExpressionLanguage.apply(v, descriptor, apiKey, paUsr)).toSeq
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

                                        val clientReq = descriptor.useAkkaHttpClient match {
                                          case true => env.gatewayClient.akkaUrlWithTarget(UrlSanitizer.sanitize(httpRequest.url), target, descriptor.clientConfig)
                                          case false => env.gatewayClient.urlWithTarget(UrlSanitizer.sanitize(httpRequest.url), target, descriptor.clientConfig)
                                        }

                                        val builder = clientReq
                                          .withRequestTimeout(descriptor.clientConfig.extractTimeout(req.relativeUri, _.callAndStreamTimeout, _.callAndStreamTimeout))
                                          //.withRequestTimeout(env.requestTimeout) // we should monitor leaks
                                          .withMethod(httpRequest.method)
                                          .withHttpHeaders(httpRequest.headers.toSeq.filterNot(_._1 == "Cookie"): _*)
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
                                            val _headersForOut: Seq[(String, String)] = resp.headers.toSeq.flatMap(
                                              c => c._2.map(v => (c._1, v))
                                            ) //.map(tuple => (tuple._1, tuple._2.mkString(","))) //.toSimpleMap // .mapValues(_.head)
                                            val rawResponse = otoroshi.script.HttpResponse(
                                              status = resp.status,
                                              headers = headers.toMap,
                                              cookies = resp.cookies
                                            )
                                            // logger.trace(s"Connection: ${resp.headers.headers.get("Connection").map(_.last)}")
                                            // if (env.notDev && !headers.get(env.Headers.OtoroshiStateResp).contains(state)) {
                                            // val validState = headers.get(env.Headers.OtoroshiStateResp).filter(c => env.crypto.verifyString(state, c)).orElse(headers.get(env.Headers.OtoroshiStateResp).contains(state)).getOrElse(false)
                                            val stateResp = headers.get(env.Headers.OtoroshiStateResp).orElse(headers.get(env.Headers.OtoroshiStateResp.toLowerCase))
                                            if ((descriptor.enforceSecureCommunication && descriptor.sendStateChallenge)
                                                && !descriptor.isUriExcludedFromSecuredCommunication("/" + uri)
                                                && !stateRespValid(stateValue, stateResp, jti, descriptor)) {
                                              // && !headers.get(env.Headers.OtoroshiStateResp).contains(state)) {
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
                                                  callAttempts = callAttempts
                                                )
                                              } else if (isUp) {
                                                // val body = Await.result(resp.body.runFold(ByteString.empty)((a, b) => a.concat(b)).map(_.utf8String), Duration("10s"))
                                                val exchange = Json.stringify(
                                                  Json.obj(
                                                    "uri"   -> req.relativeUri,
                                                    "url"   -> url,
                                                    "state" -> stateValue,
                                                    "reveivedState" -> JsString(stateResp.getOrElse("--")),
                                                    "claim"  -> claim,
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
                                                  callAttempts = callAttempts
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
                                                  callAttempts = callAttempts
                                                )
                                              }
                                            } else {
                                              val upstreamLatency = System.currentTimeMillis() - upstreamStart
                                              val _headersOut: Seq[(String, String)] = _headersForOut
                                                .filterNot(t => headersOutFiltered.contains(t._1.toLowerCase)) ++ (
                                                if (descriptor.sendOtoroshiHeadersBack) {
                                                  Seq(
                                                    env.Headers.OtoroshiRequestId        -> snowflake,
                                                    env.Headers.OtoroshiRequestTimestamp -> requestTimestamp,
                                                    env.Headers.OtoroshiProxyLatency     -> s"$overhead",
                                                    env.Headers.OtoroshiUpstreamLatency  -> s"$upstreamLatency" //,
                                                    //env.Headers.OtoroshiTrackerId              -> s"${env.sign(trackingId)}::$trackingId"
                                                  )
                                                } else {
                                                  Seq.empty[(String, String)]
                                                }
                                              ) ++ Some(canaryId)
                                                .filter(_ => desc.canary.enabled)
                                                .map(
                                                  _ => env.Headers.OtoroshiTrackerId -> s"${env.sign(canaryId)}::$canaryId"
                                                ) ++ (if (descriptor.sendOtoroshiHeadersBack && apiKey.isDefined) {
                                                        Seq(
                                                          env.Headers.OtoroshiDailyCallsRemaining   -> remainingQuotas.remainingCallsPerDay.toString,
                                                          env.Headers.OtoroshiMonthlyCallsRemaining -> remainingQuotas.remainingCallsPerMonth.toString
                                                        )
                                                      } else {
                                                        Seq.empty[(String, String)]
                                                      }) ++ descriptor.cors
                                                .asHeaders(req) ++ desc.additionalHeadersOut.mapValues(v => HeadersExpressionLanguage.apply(v, descriptor, apiKey, paUsr)).toSeq

                                              val otoroshiResponse = otoroshi.script.HttpResponse(
                                                status = resp.status,
                                                headers = _headersOut.toMap,
                                                cookies = resp.cookies
                                              )
                                              descriptor
                                                .transformResponse(
                                                  snowflake = snowflake,
                                                  rawResponse = rawResponse,
                                                  otoroshiResponse = otoroshiResponse,
                                                  desc = descriptor,
                                                  apiKey = apiKey,
                                                  user = paUsr
                                                )
                                                .flatMap {
                                                  case Left(badResult) => {
                                                    resp.ignore()
                                                    FastFuture.successful(badResult)
                                                  }
                                                  case Right(httpResponse) => {
                                                    val headersOut = httpResponse.headers.toSeq
                                                    val contentType =
                                                      httpResponse.headers.getOrElse("Content-Type", MimeTypes.TEXT)
                                                    // val _contentTypeOpt = resp.headers.get("Content-Type").flatMap(_.lastOption)
                                                    // meterOut.mark(responseHeader.length)
                                                    // counterOut.addAndGet(responseHeader.length)
                                                    val isChunked =
                                                      resp.header("Transfer-Encoding").contains("chunked")
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
                                                            s"error while transfering stream for ${protocol}://${req.host}${req.relativeUri}",
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
                                                      snowflake = snowflake,
                                                      rawResponse = rawResponse,
                                                      otoroshiResponse = otoroshiResponse,
                                                      desc = descriptor,
                                                      apiKey = apiKey,
                                                      user = paUsr,
                                                      body = theStream
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
                                                          s"HTTP/1.0 request, storing temporary result in memory :( (${protocol}://${req.host}${req.relativeUri})"
                                                        )
                                                        finalStream
                                                          .via(
                                                            MaxLengthLimiter(globalConfig.maxHttp10ResponseSize.toInt,
                                                                             str => logger.warn(str))
                                                          )
                                                          .runWith(Sink.reduce[ByteString]((bs, n) => bs.concat(n)))
                                                          .fast
                                                          .flatMap { body =>
                                                            val response: Result = Status(httpResponse.status)(body)
                                                              .withHeaders(
                                                                headersOut.filterNot(
                                                                  h => h._1 == "Content-Type" || h._1 == "Set-Cookie" || h._1 == "Transfer-Encoding"
                                                                ): _*
                                                              )
                                                              .as(contentType)
                                                              .withCookies((withTrackingCookies ++ cookies): _*)
                                                            desc.gzip.handleResult(req, response)
                                                          }
                                                      } else {
                                                        resp.ignore()
                                                        Errors.craftResponseResult(
                                                          "You cannot use HTTP/1.0 here",
                                                          Forbidden,
                                                          req,
                                                          Some(descriptor),
                                                          Some("errors.http.10.not.allowed"),
                                                          duration = System.currentTimeMillis - start,
                                                          overhead = (System
                                                            .currentTimeMillis() - secondStart) + firstOverhead,
                                                          cbDuration = cbDuration,
                                                          callAttempts = callAttempts
                                                        )
                                                      }
                                                    } else if (globalConfig.streamEntityOnly) { // only temporary
                                                      // stream out
                                                      val response: Result = isChunked match {
                                                        case true => {
                                                          // stream out
                                                          Status(httpResponse.status)
                                                            .chunked(finalStream)
                                                            .withHeaders(
                                                              headersOut.filterNot(h => h._1 == "Set-Cookie" || h._1 == "Content-Type"): _*
                                                              /* ++ (if (isChunked)
                                                                    Seq(("Transfer-Encoding" -> "chunked"))
                                                                  else Seq.empty): _* */
                                                            )
                                                            .withCookies((withTrackingCookies ++ cookies): _*)
                                                            .as(contentType)
                                                        }
                                                        case false => {
                                                          // stream out
                                                          Status(httpResponse.status)
                                                            .sendEntity(
                                                              HttpEntity.Streamed(
                                                                finalStream,
                                                                httpResponse.headers
                                                                  .get("Content-Length")
                                                                  //.flatMap(_.lastOption)
                                                                  .map(
                                                                    _.toLong + snowMonkeyContext.trailingResponseBodySize
                                                                  ),
                                                                httpResponse.headers.get("Content-Type")
                                                              )
                                                            )
                                                            .withHeaders(
                                                              headersOut.filterNot(
                                                                h => h._1 == "Content-Type" || h._1 == "Set-Cookie" || h._1 == "Transfer-Encoding"
                                                              ): _*
                                                            )
                                                            .withCookies((withTrackingCookies ++ cookies): _*)
                                                            .as(contentType)
                                                        }
                                                      }
                                                      desc.gzip.handleResult(req, response)
                                                      // val entity =
                                                      //   if (isChunked) {
                                                      //     HttpEntity.Chunked(
                                                      //       finalStream
                                                      //         .map(i => play.api.http.HttpChunk.Chunk(i))
                                                      //         .concat(
                                                      //           Source.single(
                                                      //             play.api.http.HttpChunk
                                                      //               .LastChunk(play.api.mvc.Headers())
                                                      //           )
                                                      //         ),
                                                      //       Some(contentType) // contentTypeOpt
                                                      //     )
                                                      //   } else {
                                                      //     HttpEntity.Streamed(
                                                      //       finalStream,
                                                      //       httpResponse.headers
                                                      //         .get("Content-Length")
                                                      //         //.flatMap(_.lastOption)
                                                      //         .map(_.toLong + snowMonkeyContext.trailingResponseBodySize),
                                                      //       Some(contentType) // contentTypeOpt
                                                      //     )
                                                      //   }
                                                      // val response: Result = Status(httpResponse.status)
                                                      //   .sendEntity(entity)
                                                      //   .withHeaders(
                                                      //     headersOut.filterNot(
                                                      //       h => h._1 == "Content-Type" || h._1 == "Set-Cookie"
                                                      //     ) ++ (if (isChunked) Seq(("Transfer-Encoding" -> "chunked")) else Seq.empty) : _*
                                                      //   )
                                                      //   .as(contentType)
                                                      //   .withCookies((withTrackingCookies ++ cookies): _*)
                                                      // desc.gzip.handleResult(req, response)
                                                      // FastFuture.successful(response)
                                                    } else {
                                                      val response: Result = isChunked match {
                                                        case true => {
                                                          // stream out
                                                          Status(httpResponse.status)
                                                            .chunked(finalStream)
                                                            .withHeaders(
                                                              headersOut.filterNot(h => h._1 == "Set-Cookie" || h._1 == "Content-Type"): _*
                                                              /* ++ (if (isChunked)
                                                                    Seq(("Transfer-Encoding" -> "chunked"))
                                                                  else Seq.empty): _* */
                                                            )
                                                            .withCookies((withTrackingCookies ++ cookies): _*)
                                                            .as(contentType)
                                                        }
                                                        case false => {
                                                          // stream out
                                                          Status(httpResponse.status)
                                                            .sendEntity(
                                                              HttpEntity.Streamed(
                                                                finalStream,
                                                                httpResponse.headers
                                                                  .get("Content-Length")
                                                                  //.flatMap(_.lastOption)
                                                                  .map(
                                                                    _.toLong + snowMonkeyContext.trailingResponseBodySize
                                                                  ),
                                                                httpResponse.headers.get("Content-Type")
                                                              )
                                                            )
                                                            .withHeaders(
                                                              headersOut.filterNot(
                                                                h => h._1 == "Content-Type" || h._1 == "Set-Cookie" || h._1 == "Transfer-Encoding"
                                                              ): _*
                                                            )
                                                            .withCookies((withTrackingCookies ++ cookies): _*)
                                                            .as(contentType)
                                                        }
                                                      }
                                                      desc.gzip.handleResult(req, response)
                                                      // val response: Result = httpResponse.headers
                                                      //   .get("Transfer-Encoding")
                                                      //   //.flatMap(_.lastOption)
                                                      //   .filter(_ == "chunked")
                                                      //   .map { _ =>
                                                      //     // stream out
                                                      //     logger.warn(s"2: Chunking response for ${desc.name} ${req.relativeUri}")
                                                      //     Status(httpResponse.status)
                                                      //       .chunked(finalStream)
                                                      //       .withHeaders(
                                                      //         headersOut.filterNot(h => h._1 == "Set-Cookie")
                                                      //           ++ (if (isChunked) Seq(("Transfer-Encoding" -> "chunked")) else Seq.empty) : _*
                                                      //       )
                                                      //       .withCookies((withTrackingCookies ++ cookies): _*)
                                                      //       .as(contentType)
                                                      //   } getOrElse {
                                                      //   // stream out
                                                      //   Status(httpResponse.status)
                                                      //     .sendEntity(
                                                      //       HttpEntity.Streamed(
                                                      //         finalStream,
                                                      //         httpResponse.headers
                                                      //           .get("Content-Length")
                                                      //           //.flatMap(_.lastOption)
                                                      //           .map(
                                                      //             _.toLong + snowMonkeyContext.trailingResponseBodySize
                                                      //           ),
                                                      //         httpResponse.headers.get("Content-Type")
                                                      //       )
                                                      //     )
                                                      //     .withHeaders(
                                                      //       headersOut.filterNot(
                                                      //         h => h._1 == "Content-Type" || h._1 == "Set-Cookie"
                                                      //       ): _*
                                                      //     )
                                                      //     .withCookies((withTrackingCookies ++ cookies): _*)
                                                      //     .as(contentType)
                                                      // }
                                                      // desc.gzip.handleResult(req, response)
                                                      //FastFuture.successful(response)
                                                    }
                                                  }
                                                }
                                            }

                                          }
                                      }
                                    }
                                  }

                                  def passWithApiKey(config: GlobalConfig): Future[Result] = {
                                    if (descriptor.thirdPartyApiKey.enabled) {
                                      descriptor.thirdPartyApiKey.handle(req, descriptor, config) { key =>
                                        callDownstream(config, key)
                                      }
                                    } else {
                                      val authByJwtToken = req.headers
                                        .get(
                                          descriptor.apiKeyConstraints.jwtAuth.headerName
                                            .getOrElse(env.Headers.OtoroshiBearer)
                                        )
                                        .orElse(
                                          req.headers.get("Authorization").filter(_.startsWith("Bearer "))
                                        )
                                        .map(_.replace("Bearer ", ""))
                                        .orElse(
                                          req.queryString
                                            .get(
                                              descriptor.apiKeyConstraints.jwtAuth.queryName
                                                .getOrElse(env.Headers.OtoroshiBearerAuthorization)
                                            )
                                            .flatMap(_.lastOption)
                                        )
                                        .orElse(
                                          req.cookies
                                            .get(
                                              descriptor.apiKeyConstraints.jwtAuth.cookieName
                                                .getOrElse(env.Headers.OtoroshiJWTAuthorization)
                                            )
                                            .map(_.value)
                                        )
                                        .filter(_.split("\\.").length == 3)
                                      val authBasic = req.headers
                                        .get(
                                          descriptor.apiKeyConstraints.basicAuth.headerName
                                            .getOrElse(env.Headers.OtoroshiAuthorization)
                                        )
                                        .orElse(
                                          req.headers.get("Authorization").filter(_.startsWith("Basic "))
                                        )
                                        .map(_.replace("Basic ", ""))
                                        .flatMap(e => Try(decodeBase64(e)).toOption)
                                        .orElse(
                                          req.queryString
                                            .get(
                                              descriptor.apiKeyConstraints.basicAuth.queryName
                                                .getOrElse(env.Headers.OtoroshiBasicAuthorization)
                                            )
                                            .flatMap(_.lastOption)
                                            .flatMap(e => Try(decodeBase64(e)).toOption)
                                        )
                                      val authByCustomHeaders = req.headers
                                        .get(
                                          descriptor.apiKeyConstraints.customHeadersAuth.clientIdHeaderName
                                            .getOrElse(env.Headers.OtoroshiClientId)
                                        )
                                        .flatMap(
                                          id =>
                                            req.headers
                                              .get(
                                                descriptor.apiKeyConstraints.customHeadersAuth.clientSecretHeaderName
                                                  .getOrElse(env.Headers.OtoroshiClientSecret)
                                              )
                                              .map(s => (id, s))
                                        )
                                      val authBySimpleApiKeyClientId = req.headers
                                        .get(
                                          descriptor.apiKeyConstraints.clientIdAuth.headerName
                                            .getOrElse(env.Headers.OtoroshiSimpleApiKeyClientId)
                                        )
                                        .orElse(
                                          req.queryString
                                            .get(
                                              descriptor.apiKeyConstraints.clientIdAuth.queryName
                                                .getOrElse(env.Headers.OtoroshiSimpleApiKeyClientId)
                                            )
                                            .flatMap(_.lastOption)
                                        )
                                      if (authBySimpleApiKeyClientId.isDefined && descriptor.apiKeyConstraints.clientIdAuth.enabled) {
                                        val clientId = authBySimpleApiKeyClientId.get
                                        env.datastores.apiKeyDataStore
                                          .findAuthorizeKeyFor(clientId, descriptor.id)
                                          .flatMap {
                                            case None =>
                                              Errors.craftResponseResult(
                                                "Invalid API key",
                                                Unauthorized,
                                                req,
                                                Some(descriptor),
                                                Some("errors.invalid.api.key"),
                                                duration = System.currentTimeMillis - start,
                                                overhead = (System.currentTimeMillis() - secondStart) + firstOverhead
                                              )
                                            case Some(key) if !key.allowClientIdOnly => {
                                              Errors.craftResponseResult(
                                                "Bad API key",
                                                BadRequest,
                                                req,
                                                Some(descriptor),
                                                Some("errors.bad.api.key"),
                                                duration = System.currentTimeMillis - start,
                                                overhead = (System.currentTimeMillis() - secondStart) + firstOverhead
                                              )
                                            }
                                            case Some(key) if !key.matchRouting(descriptor) => {
                                              Errors.craftResponseResult(
                                                "Invalid API key",
                                                Unauthorized,
                                                req,
                                                Some(descriptor),
                                                Some("errors.bad.api.key"),
                                                duration = System.currentTimeMillis - start,
                                                overhead = (System.currentTimeMillis() - secondStart) + firstOverhead
                                              )
                                            }
                                            case Some(key) if key.allowClientIdOnly =>
                                              key.withingQuotas().flatMap {
                                                case true => callDownstream(config, Some(key))
                                                case false =>
                                                  Errors.craftResponseResult(
                                                    "You performed too much requests",
                                                    TooManyRequests,
                                                    req,
                                                    Some(descriptor),
                                                    Some("errors.too.much.requests"),
                                                    duration = System.currentTimeMillis - start,
                                                    overhead = (System
                                                      .currentTimeMillis() - secondStart) + firstOverhead
                                                  )
                                              }
                                          }
                                      } else if (authByCustomHeaders.isDefined && descriptor.apiKeyConstraints.customHeadersAuth.enabled) {
                                        val (clientId, clientSecret) = authByCustomHeaders.get
                                        env.datastores.apiKeyDataStore
                                          .findAuthorizeKeyFor(clientId, descriptor.id)
                                          .flatMap {
                                            case None =>
                                              Errors.craftResponseResult(
                                                "Invalid API key",
                                                Unauthorized,
                                                req,
                                                Some(descriptor),
                                                Some("errors.invalid.api.key"),
                                                duration = System.currentTimeMillis - start,
                                                overhead = (System.currentTimeMillis() - secondStart) + firstOverhead
                                              )
                                            case Some(key) if key.isInvalid(clientSecret) => {
                                              Alerts.send(
                                                RevokedApiKeyUsageAlert(env.snowflakeGenerator.nextIdStr(),
                                                                        DateTime.now(),
                                                                        env.env,
                                                                        req,
                                                                        key,
                                                                        descriptor)
                                              )
                                              Errors.craftResponseResult(
                                                "Bad API key",
                                                BadRequest,
                                                req,
                                                Some(descriptor),
                                                Some("errors.bad.api.key"),
                                                duration = System.currentTimeMillis - start,
                                                overhead = (System.currentTimeMillis() - secondStart) + firstOverhead
                                              )
                                            }
                                            case Some(key) if !key.matchRouting(descriptor) => {
                                              Errors.craftResponseResult(
                                                "Bad API key",
                                                Unauthorized,
                                                req,
                                                Some(descriptor),
                                                Some("errors.bad.api.key"),
                                                duration = System.currentTimeMillis - start,
                                                overhead = (System.currentTimeMillis() - secondStart) + firstOverhead
                                              )
                                            }
                                            case Some(key) if key.isValid(clientSecret) =>
                                              key.withingQuotas().flatMap {
                                                case true => callDownstream(config, Some(key))
                                                case false =>
                                                  Errors.craftResponseResult(
                                                    "You performed too much requests",
                                                    TooManyRequests,
                                                    req,
                                                    Some(descriptor),
                                                    Some("errors.too.much.requests"),
                                                    duration = System.currentTimeMillis - start,
                                                    overhead = (System
                                                      .currentTimeMillis() - secondStart) + firstOverhead
                                                  )
                                              }
                                          }
                                      } else if (authByJwtToken.isDefined && descriptor.apiKeyConstraints.jwtAuth.enabled) {
                                        val jwtTokenValue = authByJwtToken.get
                                        Try {
                                          JWT.decode(jwtTokenValue)
                                        } map { jwt =>
                                          Option(jwt.getClaim("iss"))
                                            .filterNot(_.isNull)
                                            .map(_.asString())
                                            .orElse(
                                              Option(jwt.getClaim("clientId")).filterNot(_.isNull).map(_.asString())
                                            ) match {
                                            case Some(clientId) =>
                                              env.datastores.apiKeyDataStore
                                                .findAuthorizeKeyFor(clientId, descriptor.id)
                                                .flatMap {
                                                  case Some(apiKey) => {
                                                    val algorithm = Option(jwt.getAlgorithm).map {
                                                      case "HS256" => Algorithm.HMAC256(apiKey.clientSecret)
                                                      case "HS512" => Algorithm.HMAC512(apiKey.clientSecret)
                                                    } getOrElse Algorithm.HMAC512(apiKey.clientSecret)
                                                    val exp =
                                                      Option(jwt.getClaim("exp")).filterNot(_.isNull).map(_.asLong())
                                                    val iat =
                                                      Option(jwt.getClaim("iat")).filterNot(_.isNull).map(_.asLong())
                                                    val httpPath = Option(jwt.getClaim("httpPath"))
                                                      .filterNot(_.isNull)
                                                      .map(_.asString())
                                                    val httpVerb = Option(jwt.getClaim("httpVerb"))
                                                      .filterNot(_.isNull)
                                                      .map(_.asString())
                                                    val httpHost = Option(jwt.getClaim("httpHost"))
                                                      .filterNot(_.isNull)
                                                      .map(_.asString())
                                                    val verifier =
                                                      JWT.require(algorithm).withIssuer(clientId).acceptLeeway(10).build
                                                    Try(verifier.verify(jwtTokenValue))
                                                      .filter { token =>
                                                        val xsrfToken       = token.getClaim("xsrfToken")
                                                        val xsrfTokenHeader = req.headers.get("X-XSRF-TOKEN")
                                                        if (!xsrfToken.isNull && xsrfTokenHeader.isDefined) {
                                                          xsrfToken.asString() == xsrfTokenHeader.get
                                                        } else if (!xsrfToken.isNull && xsrfTokenHeader.isEmpty) {
                                                          false
                                                        } else {
                                                          true
                                                        }
                                                      }
                                                      .filter { _ =>
                                                        desc.apiKeyConstraints.jwtAuth.maxJwtLifespanSecs.map {
                                                          maxJwtLifespanSecs =>
                                                            if (exp.isEmpty || iat.isEmpty) {
                                                              false
                                                            } else {
                                                              if ((exp.get - iat.get) <= maxJwtLifespanSecs) {
                                                                true
                                                              } else {
                                                                false
                                                              }
                                                            }
                                                        } getOrElse {
                                                          true
                                                        }
                                                      }
                                                      .filter { _ =>
                                                        if (descriptor.apiKeyConstraints.jwtAuth.includeRequestAttributes) {
                                                          val matchPath = httpPath.exists(_ == req.relativeUri)
                                                          val matchVerb =
                                                            httpVerb.exists(_.toLowerCase == req.method.toLowerCase)
                                                          val matchHost = httpHost.exists(_.toLowerCase == req.host)
                                                          matchPath && matchVerb && matchHost
                                                        } else {
                                                          true
                                                        }
                                                      } match {
                                                      case Success(_) if !apiKey.matchRouting(descriptor) => {
                                                        Errors.craftResponseResult(
                                                          "Invalid API key",
                                                          Unauthorized,
                                                          req,
                                                          Some(descriptor),
                                                          Some("errors.bad.api.key"),
                                                          duration = System.currentTimeMillis - start,
                                                          overhead = (System.currentTimeMillis() - secondStart) + firstOverhead
                                                        )
                                                      }
                                                      case Success(_) =>
                                                        apiKey.withingQuotas().flatMap {
                                                          case true => callDownstream(config, Some(apiKey))
                                                          case false =>
                                                            Errors.craftResponseResult(
                                                              "You performed too much requests",
                                                              TooManyRequests,
                                                              req,
                                                              Some(descriptor),
                                                              Some("errors.too.much.requests"),
                                                              duration = System.currentTimeMillis - start,
                                                              overhead = (System
                                                                .currentTimeMillis() - secondStart) + firstOverhead
                                                            )
                                                        }
                                                      case Failure(e) => {
                                                        Alerts.send(
                                                          RevokedApiKeyUsageAlert(env.snowflakeGenerator.nextIdStr(),
                                                                                  DateTime.now(),
                                                                                  env.env,
                                                                                  req,
                                                                                  apiKey,
                                                                                  descriptor)
                                                        )
                                                        Errors.craftResponseResult(
                                                          s"Bad API key",
                                                          BadRequest,
                                                          req,
                                                          Some(descriptor),
                                                          Some("errors.bad.api.key"),
                                                          duration = System.currentTimeMillis - start,
                                                          overhead = (System
                                                            .currentTimeMillis() - secondStart) + firstOverhead
                                                        )
                                                      }
                                                    }
                                                  }
                                                  case None =>
                                                    Errors.craftResponseResult(
                                                      "Invalid ApiKey provided",
                                                      Unauthorized,
                                                      req,
                                                      Some(descriptor),
                                                      Some("errors.invalid.api.key"),
                                                      duration = System.currentTimeMillis - start,
                                                      overhead = (System
                                                        .currentTimeMillis() - secondStart) + firstOverhead
                                                    )
                                                }
                                            case None =>
                                              Errors.craftResponseResult(
                                                "Invalid ApiKey provided",
                                                Unauthorized,
                                                req,
                                                Some(descriptor),
                                                Some("errors.invalid.api.key"),
                                                duration = System.currentTimeMillis - start,
                                                overhead = (System.currentTimeMillis() - secondStart) + firstOverhead
                                              )
                                          }
                                        } getOrElse Errors.craftResponseResult(
                                          s"Invalid ApiKey provided",
                                          Unauthorized,
                                          req,
                                          Some(descriptor),
                                          Some("errors.invalid.api.key"),
                                          duration = System.currentTimeMillis - start,
                                          overhead = (System.currentTimeMillis() - secondStart) + firstOverhead
                                        )
                                      } else if (authBasic.isDefined && descriptor.apiKeyConstraints.basicAuth.enabled) {
                                        val auth   = authBasic.get
                                        val id     = auth.split(":").headOption.map(_.trim)
                                        val secret = auth.split(":").lastOption.map(_.trim)
                                        (id, secret) match {
                                          case (Some(apiKeyClientId), Some(apiKeySecret)) => {
                                            env.datastores.apiKeyDataStore
                                              .findAuthorizeKeyFor(apiKeyClientId, descriptor.id)
                                              .flatMap {
                                                case None =>
                                                  Errors.craftResponseResult(
                                                    "Invalid API key",
                                                    Unauthorized,
                                                    req,
                                                    Some(descriptor),
                                                    Some("errors.invalid.api.key"),
                                                    duration = System.currentTimeMillis - start,
                                                    overhead = (System
                                                      .currentTimeMillis() - secondStart) + firstOverhead
                                                  )
                                                case Some(key) if key.isInvalid(apiKeySecret) => {
                                                  Alerts.send(
                                                    RevokedApiKeyUsageAlert(env.snowflakeGenerator.nextIdStr(),
                                                                            DateTime.now(),
                                                                            env.env,
                                                                            req,
                                                                            key,
                                                                            descriptor)
                                                  )
                                                  Errors.craftResponseResult(
                                                    "Bad API key",
                                                    BadGateway,
                                                    req,
                                                    Some(descriptor),
                                                    Some("errors.bad.api.key"),
                                                    duration = System.currentTimeMillis - start,
                                                    overhead = (System
                                                      .currentTimeMillis() - secondStart) + firstOverhead
                                                  )
                                                }
                                                case Some(key) if !key.matchRouting(descriptor) => {
                                                  Errors.craftResponseResult(
                                                    "Invalid API key",
                                                    Unauthorized,
                                                    req,
                                                    Some(descriptor),
                                                    Some("errors.bad.api.key"),
                                                    duration = System.currentTimeMillis - start,
                                                    overhead = (System.currentTimeMillis() - secondStart) + firstOverhead
                                                  )
                                                }
                                                case Some(key) if key.isValid(apiKeySecret) =>
                                                  key.withingQuotas().flatMap {
                                                    case true => callDownstream(config, Some(key))
                                                    case false =>
                                                      Errors.craftResponseResult(
                                                        "You performed too much requests",
                                                        TooManyRequests,
                                                        req,
                                                        Some(descriptor),
                                                        Some("errors.too.much.requests"),
                                                        duration = System.currentTimeMillis - start,
                                                        overhead = (System
                                                          .currentTimeMillis() - secondStart) + firstOverhead
                                                      )
                                                  }
                                              }
                                          }
                                          case _ =>
                                            Errors.craftResponseResult(
                                              "No ApiKey provided",
                                              BadRequest,
                                              req,
                                              Some(descriptor),
                                              Some("errors.no.api.key"),
                                              duration = System.currentTimeMillis - start,
                                              overhead = (System.currentTimeMillis() - secondStart) + firstOverhead
                                            )
                                        }
                                      } else {
                                        Errors.craftResponseResult(
                                          "No ApiKey provided",
                                          BadRequest,
                                          req,
                                          Some(descriptor),
                                          Some("errors.no.api.key"),
                                          duration = System.currentTimeMillis - start,
                                          overhead = (System.currentTimeMillis() - secondStart) + firstOverhead
                                        )
                                      }
                                    }
                                  }

                                  def passWithAuth0(config: GlobalConfig): Future[Result] = {
                                    isPrivateAppsSessionValid(req, descriptor).flatMap {
                                      case Some(paUsr) => callDownstream(config, paUsr = Some(paUsr))
                                      case None => {
                                        val redirectTo = env.rootScheme + env.privateAppsHost + env.privateAppsPort
                                          .map(a => s":$a")
                                          .getOrElse("") + controllers.routes.AuthController
                                          .confidentialAppLoginPage()
                                          .url + s"?desc=${descriptor.id}&redirect=${protocol}://${req.host}${req.relativeUri}"
                                        logger.trace("should redirect to " + redirectTo)
                                        descriptor.authConfigRef match {
                                          case None =>
                                            Errors.craftResponseResult(
                                              "Auth. config. ref not found on the descriptor",
                                              InternalServerError,
                                              req,
                                              Some(descriptor),
                                              Some("errors.auth.config.ref.not.found"),
                                              duration = System.currentTimeMillis - start,
                                              overhead = (System.currentTimeMillis() - secondStart) + firstOverhead
                                            )
                                          case Some(ref) => {
                                            env.datastores.authConfigsDataStore.findById(ref).flatMap {
                                              case None =>
                                                Errors.craftResponseResult(
                                                  "Auth. config. not found on the descriptor",
                                                  InternalServerError,
                                                  req,
                                                  Some(descriptor),
                                                  Some("errors.auth.config.not.found"),
                                                  duration = System.currentTimeMillis - start,
                                                  overhead = (System.currentTimeMillis() - secondStart) + firstOverhead
                                                )
                                              case Some(auth) => {
                                                FastFuture.successful(
                                                  Results
                                                    .Redirect(redirectTo)
                                                    .discardingCookies(
                                                      env.removePrivateSessionCookies(
                                                        ServiceDescriptorQuery(subdomain, serviceEnv, domain, "/").toHost,
                                                        descriptor,
                                                        auth
                                                      ): _*
                                                    )
                                                )
                                              }
                                            }
                                          }
                                        }
                                      }
                                    }
                                  }

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

                                  env.datastores.globalConfigDataStore.quotasValidationFor(from).flatMap { r =>
                                    val (within, secCalls, maybeQuota) = r
                                    val quota                          = maybeQuota.getOrElse(globalConfig.perIpThrottlingQuota)
                                    if (secCalls > (quota * 10L)) {
                                      Errors.craftResponseResult(
                                        "[IP] You performed too much requests",
                                        TooManyRequests,
                                        req,
                                        Some(descriptor),
                                        Some("errors.too.much.requests"),
                                        duration = System.currentTimeMillis - start,
                                        overhead = (System.currentTimeMillis() - secondStart) + firstOverhead
                                      )
                                    } else {
                                      if (!isSecured && desc.forceHttps) {
                                        val theDomain = req.domain
                                        val protocol  = getProtocolFor(req)
                                        logger.trace(
                                          s"redirects prod service from ${protocol}://$theDomain${req.relativeUri} to https://$theDomain${req.relativeUri}"
                                        )
                                        //FastFuture.successful(Redirect(s"${env.rootScheme}$theDomain${req.relativeUri}"))
                                        FastFuture.successful(Redirect(s"https://$theDomain${req.relativeUri}"))
                                      } else if (!within) {
                                        // TODO : count as served req here !!!
                                        Errors.craftResponseResult(
                                          "[GLOBAL] You performed too much requests",
                                          TooManyRequests,
                                          req,
                                          Some(descriptor),
                                          Some("errors.too.much.requests"),
                                          duration = System.currentTimeMillis - start,
                                          overhead = (System.currentTimeMillis() - secondStart) + firstOverhead
                                        )
                                      } else if (globalConfig.ipFiltering.whitelist.nonEmpty && !globalConfig.ipFiltering.whitelist
                                                   .exists(ip => utils.RegexPool(ip).matches(remoteAddress))) {
                                        Errors.craftResponseResult(
                                          "Your IP address is not allowed",
                                          Forbidden,
                                          req,
                                          Some(descriptor),
                                          Some("errors.ip.address.not.allowed"),
                                          duration = System.currentTimeMillis - start,
                                          overhead = (System.currentTimeMillis() - secondStart) + firstOverhead
                                        ) // global whitelist
                                      } else if (globalConfig.ipFiltering.blacklist.nonEmpty && globalConfig.ipFiltering.blacklist
                                                   .exists(ip => utils.RegexPool(ip).matches(remoteAddress))) {
                                        Errors.craftResponseResult(
                                          "Your IP address is not allowed",
                                          Forbidden,
                                          req,
                                          Some(descriptor),
                                          Some("errors.ip.address.not.allowed"),
                                          duration = System.currentTimeMillis - start,
                                          overhead = (System.currentTimeMillis() - secondStart) + firstOverhead
                                        ) // global blacklist
                                      } else if (descriptor.ipFiltering.whitelist.nonEmpty && !descriptor.ipFiltering.whitelist
                                                   .exists(ip => utils.RegexPool(ip).matches(remoteAddress))) {
                                        Errors.craftResponseResult(
                                          "Your IP address is not allowed",
                                          Forbidden,
                                          req,
                                          Some(descriptor),
                                          Some("errors.ip.address.not.allowed"),
                                          duration = System.currentTimeMillis - start,
                                          overhead = (System.currentTimeMillis() - secondStart) + firstOverhead
                                        ) // service whitelist
                                      } else if (descriptor.ipFiltering.blacklist.nonEmpty && descriptor.ipFiltering.blacklist
                                                   .exists(ip => utils.RegexPool(ip).matches(remoteAddress))) {
                                        Errors.craftResponseResult(
                                          "Your IP address is not allowed",
                                          Forbidden,
                                          req,
                                          Some(descriptor),
                                          Some("errors.ip.address.not.allowed"),
                                          duration = System.currentTimeMillis - start,
                                          overhead = (System.currentTimeMillis() - secondStart) + firstOverhead
                                        ) // service blacklist
                                      } else if (globalConfig.endlessIpAddresses.nonEmpty && globalConfig.endlessIpAddresses
                                                   .exists(ip => RegexPool(ip).matches(remoteAddress))) {
                                        val gigas: Long = 128L * 1024L * 1024L * 1024L
                                        val middleFingers = ByteString.fromString(
                                          "\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95"
                                        )
                                        val zeros =
                                          ByteString.fromInts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
                                        val characters: ByteString =
                                          if (!globalConfig.middleFingers) middleFingers else zeros
                                        val expected: Long = (gigas / characters.size) + 1L
                                        FastFuture.successful(
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
                                      } else if (descriptor.maintenanceMode) {
                                        Errors.craftResponseResult(
                                          "Service in maintenance mode",
                                          ServiceUnavailable,
                                          req,
                                          Some(descriptor),
                                          Some("errors.service.in.maintenance"),
                                          duration = System.currentTimeMillis - start,
                                          overhead = (System.currentTimeMillis() - secondStart) + firstOverhead
                                        )
                                      } else if (descriptor.buildMode) {
                                        Errors.craftResponseResult(
                                          "Service under construction",
                                          ServiceUnavailable,
                                          req,
                                          Some(descriptor),
                                          Some("errors.service.under.construction"),
                                          duration = System.currentTimeMillis - start,
                                          overhead = (System.currentTimeMillis() - secondStart) + firstOverhead
                                        )
                                      } else if (descriptor.cors.enabled && req.method == "OPTIONS" && req.headers
                                                   .get("Access-Control-Request-Method")
                                                   .isDefined && descriptor.cors.shouldApplyCors(req.path)) {
                                        // handle cors preflight request
                                        if (descriptor.cors.enabled && descriptor.cors.shouldNotPass(req)) {
                                          Errors.craftResponseResult(
                                            "Cors error",
                                            BadRequest,
                                            req,
                                            Some(descriptor),
                                            Some("errors.cors.error"),
                                            duration = System.currentTimeMillis - start,
                                            overhead = (System.currentTimeMillis() - secondStart) + firstOverhead
                                          )
                                        } else {
                                          FastFuture.successful(
                                            Results.Ok(ByteString.empty).withHeaders(descriptor.cors.asHeaders(req): _*)
                                          )
                                        }
                                      } else if (isUp) {
                                        if (descriptor.isPrivate && descriptor.authConfigRef.isDefined && !descriptor
                                              .isExcludedFromSecurity(req.path)) {
                                          if (descriptor.isUriPublic(req.path)) {
                                            passWithAuth0(globalConfig)
                                          } else {
                                            isPrivateAppsSessionValid(req, descriptor).fast.flatMap {
                                              case Some(_) if descriptor.strictlyPrivate => passWithApiKey(globalConfig)
                                              case Some(user)                            => passWithAuth0(globalConfig)
                                              case None                                  => passWithApiKey(globalConfig)
                                            }
                                          }
                                        } else {
                                          if (descriptor.isUriPublic(req.path)) {
                                            callDownstream(globalConfig)
                                          } else {
                                            passWithApiKey(globalConfig)
                                          }
                                        }
                                      } else {
                                        // fail fast
                                        Errors.craftResponseResult(
                                          "The service seems to be down :( come back later",
                                          Forbidden,
                                          req,
                                          Some(descriptor),
                                          Some("errors.service.down"),
                                          duration = System.currentTimeMillis - start,
                                          overhead = (System.currentTimeMillis() - secondStart) + firstOverhead
                                        )

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
        }
      }
    }
    finalResult.andThen {
      case _ =>
        val requests = env.datastores.requestsDataStore.decrementHandledRequests()
        // globalConfig.statsdConfig
        // env.datastores.globalConfigDataStore
        //  .singleton()
        //  .fast
        //  .map(
        //    config =>
        env.metrics.markLong(s"${env.snowflakeSeed}.concurrent-requests", requests)
      //  )
    }(env.otoroshiExecutionContext)
  }

  def decodeBase64(encoded: String): String = new String(OtoroshiClaim.decoder.decode(encoded), Charsets.UTF_8)
}
