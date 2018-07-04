package gateway

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicLong}

import akka.actor.{Actor, Props}
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.util.FastFuture._
import akka.stream.Materializer
import akka.stream.scaladsl.{Concat, Merge, Sink, Source}
import akka.util.ByteString
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.google.common.base.Charsets
import env.{Env, SidecarConfig}
import events._
import models._
import utils.MaxLengthLimiter
import org.joda.time.DateTime
import play.api.Logger
import play.api.http.{Status => _, _}
import play.api.libs.json.{JsArray, JsString, Json}
import play.api.libs.streams.Accumulator
import play.api.libs.ws.{EmptyBody, SourceBody}
import play.api.mvc.Results._
import play.api.mvc._
import play.api.routing.Router
import security.{IdGenerator, OtoroshiClaim}
import utils.RegexPool

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NoStackTrace
import scala.util.{Failure, Success, Try}
import utils.RequestImplicits._

case class ProxyDone(status: Int, upstreamLatency: Long, headersOut: Seq[Header])

class ErrorHandler()(implicit env: Env) extends HttpErrorHandler {

  implicit val ec = env.otoroshiExecutionContext

  lazy val logger = Logger("otoroshi-error-handler")

  def onClientError(request: RequestHeader, statusCode: Int, mess: String) = {
    val message = Option(mess).filterNot(_.trim.isEmpty).getOrElse("An error occured")
    logger.error(s"Client Error: $message on ${request.relativeUri} ($statusCode)")
    Errors.craftResponseResult(s"Client Error: an error occured on ${request.relativeUri} ($statusCode)",
                               Status(statusCode),
                               request,
                               None,
                               Some("errors.client.error"))
  }

  def onServerError(request: RequestHeader, exception: Throwable) = {
    // exception.printStackTrace()
    logger.error(s"Server Error ${exception.getMessage} on ${request.relativeUri}", exception)
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
    val isSecured =
      request.headers.get("X-Forwarded-Protocol").map(_ == "https").orElse(Some(request.secure)).getOrElse(false)
    val protocol = request.headers
      .get("X-Forwarded-Protocol")
      .map(_ == "https")
      .orElse(Some(request.secure))
      .map {
        case true  => "https"
        case false => "http"
      }
      .getOrElse("http")
    val url     = ByteString(s"$protocol://${request.host}${request.relativeUri}")
    val cookies = request.cookies.map(_.value).map(ByteString.apply)
    val headers = request.headers.toSimpleMap.values.map(ByteString.apply)
    // logger.info(s"[SIZE] url: ${url.size} bytes, cookies: ${cookies.map(_.size).mkString(", ")}, headers: ${headers.map(_.size).mkString(", ")}")
    if (url.size > (4 * 1024)) {
      Some(tooBig("URL should be smaller than 4 Kb"))
    } else if (cookies.exists(_.size > (16 * 1024))) {
      Some(tooBig("Cookies should be smaller than 16 Kb"))
    } else if (headers.exists(_.size > (16 * 1024))) {
      Some(tooBig("Headers should be smaller than 16 Kb"))
    } else {
      val toHttps = env.exposedRootSchemeIsHttps
      val host    = if (request.host.contains(":")) request.host.split(":")(0) else request.host
      host match {
        case str if matchRedirection(str)                                           => Some(redirectToMainDomain())
        case _ if request.relativeUri.contains("__otoroshi_assets")                 => super.routeRequest(request)
        case _ if request.relativeUri.startsWith("/__otoroshi_private_apps_login")  => Some(setPrivateAppsCookies())
        case _ if request.relativeUri.startsWith("/__otoroshi_private_apps_logout") => Some(removePrivateAppsCookies())
        case env.backOfficeHost if !isSecured && toHttps && env.isProd              => Some(redirectToHttps())
        case env.privateAppsHost if !isSecured && toHttps && env.isProd             => Some(redirectToHttps())
        case env.adminApiHost if env.exposeAdminApi                                 => super.routeRequest(request)
        case env.backOfficeHost if env.exposeAdminDashboard                         => super.routeRequest(request)
        case env.privateAppsHost                                                    => super.routeRequest(request)
        case _ =>
          request.headers.get("Sec-WebSocket-Version") match {
            case None    => Some(forwardCall())
            case Some(_) => Some(webSocketHandler.proxyWebSocket())
          }
      }
    }
  }

  def setPrivateAppsCookies() = actionBuilder.async { req =>
    val redirectToOpt: Option[String] = req.queryString.get("redirectTo").map(_.last)
    val sessionIdOpt: Option[String]  = req.queryString.get("sessionId").map(_.last)
    val hostOpt: Option[String]       = req.queryString.get("host").map(_.last)
    (redirectToOpt, sessionIdOpt, hostOpt) match {
      case (Some(redirectTo), Some(sessionId), Some(host)) =>
        FastFuture.successful(Redirect(redirectTo).withCookies(env.createPrivateSessionCookies(host, sessionId): _*))
      case _ =>
        Errors.craftResponseResult("Missing parameters", BadRequest, req, None, Some("errors.missing.parameters"))
    }
  }

  def removePrivateAppsCookies() = actionBuilder.async { req =>
    val redirectToOpt: Option[String] = req.queryString.get("redirectTo").map(_.last)
    val hostOpt: Option[String]       = req.queryString.get("host").map(_.last)
    (redirectToOpt, hostOpt) match {
      case (Some(redirectTo), Some(host)) =>
        FastFuture.successful(Redirect(redirectTo).discardingCookies(env.removePrivateSessionCookies(host): _*))
      case _ =>
        Errors.craftResponseResult("Missing parameters", BadRequest, req, None, Some("errors.missing.parameters"))
    }
  }

  def tooBig(message: String) = actionBuilder.async { req =>
    Errors.craftResponseResult(message, BadRequest, req, None, Some("errors.entity.too.big"))
  }

  def isPrivateAppsSessionValid(req: Request[Source[ByteString, _]]): Future[Option[PrivateAppsUser]] = {
    req.cookies
      .get("oto-papps")
      .flatMap { cookie =>
        env.extractPrivateSessionId(cookie)
      }
      .map { id =>
        env.datastores.privateAppsUserDataStore.findById(id)
      } getOrElse {
      FastFuture.successful(None)
    }
  }

  def redirectToHttps() = actionBuilder { req =>
    val domain = req.domain
    val protocol = req.headers
      .get("X-Forwarded-Protocol")
      .map(_ == "https")
      .orElse(Some(req.secure))
      .map {
        case true  => "https"
        case false => "http"
      }
      .getOrElse("http")
    logger.info(
      s"redirectToHttps from ${protocol}://$domain${req.relativeUri} to ${env.rootScheme}$domain${req.relativeUri}"
    )
    Redirect(s"${env.rootScheme}$domain${req.relativeUri}").withHeaders("otoroshi-redirect-to" -> "https")
  }

  def redirectToMainDomain() = actionBuilder { req =>
    val domain: String = env.redirections.foldLeft(req.domain)((domain, item) => domain.replace(item, env.domain))
    val protocol = req.headers
      .get("X-Forwarded-Protocol")
      .map(_ == "https")
      .orElse(Some(req.secure))
      .map {
        case true  => "https"
        case false => "http"
      }
      .getOrElse("http")
    logger.warn(
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

  def forwardCall() = actionBuilder.async(sourceBodyParser) { req =>
    // TODO : add metrics + JMX
    // val meterIn             = Metrics.metrics.meter("GatewayDataIn")
    // val meterOut            = Metrics.metrics.meter("GatewayDataOut")
    val reqNumber           = reqCounter.incrementAndGet()
    val remoteAddress       = req.headers.get("X-Forwarded-For").getOrElse(req.remoteAddress)
    val isSecured           = req.headers.get("X-Forwarded-Protocol").map(_ == "https").orElse(Some(req.secure)).getOrElse(false)
    val from                = req.headers.get("X-Forwarded-For").getOrElse(req.remoteAddress)
    val counterIn           = new AtomicLong(0L)
    val counterOut          = new AtomicLong(0L)
    val start               = System.currentTimeMillis()
    val bodyAlreadyConsumed = new AtomicBoolean(false)
    val protocol = req.headers
      .get("X-Forwarded-Protocol")
      .map(_ == "https")
      .orElse(Some(req.secure))
      .map {
        case true  => "https"
        case false => "http"
      }
      .getOrElse("http")

    val currentHandledRequests = env.datastores.requestsDataStore.incrementHandledRequests()
    // val currentProcessedRequests = env.datastores.requestsDataStore.incrementProcessedRequests()
    val globalConfig = env.datastores.globalConfigDataStore.latest()
    val finalResult = {
      // env.datastores.globalConfigDataStore.singleton().flatMap { globalConfig => // Very consuming but eh !!!
      env.statsd.meter(s"${env.snowflakeSeed}.concurrent-requests", currentHandledRequests.toDouble)(
        globalConfig.statsdConfig
      )
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
            Errors.craftResponseResult(s"Service not found for URL ${req.host}::${req.relativeUri}",
                                       NotFound,
                                       req,
                                       None,
                                       Some("errors.service.not.found"))
          case Some(ServiceLocation(domain, serviceEnv, subdomain)) => {
            val uriParts = req.relativeUri.split("/").toSeq

            env.datastores.serviceDescriptorDataStore
              .find(ServiceDescriptorQuery(subdomain, serviceEnv, domain, req.relativeUri, req.headers.toSimpleMap))
              .fast
              .flatMap {
                case None =>
                  Errors.craftResponseResult(s"Downstream service not found",
                                             NotFound,
                                             req,
                                             None,
                                             Some("errors.service.not.found"))
                case Some(desc) if !desc.enabled =>
                  Errors
                    .craftResponseResult(s"Service not found", NotFound, req, None, Some("errors.service.not.found"))
                case Some(rawDesc) =>
                  applySidecar(rawDesc, remoteAddress, req) { desc =>
                    val firstOverhead = System.currentTimeMillis() - start
                    snowMonkey.introduceChaos(reqNumber, globalConfig, desc, hasBody(req)) { snowMonkeyContext =>
                      val secondStart = System.currentTimeMillis()
                      val maybeTrackingId = req.cookies
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
                      val trackingId: String = maybeTrackingId.getOrElse(IdGenerator.uuid + "-" + reqNumber)

                      if (maybeTrackingId.isDefined) {
                        logger.debug(s"request already has tracking id : $trackingId")
                      } else {
                        logger.debug(s"request has a new tracking id : $trackingId")
                      }

                      val withTrackingCookies =
                        if (!desc.canary.enabled) Seq.empty[play.api.mvc.Cookie]
                        else if (maybeTrackingId.isDefined) Seq.empty[play.api.mvc.Cookie]
                        else
                          Seq(
                            play.api.mvc.Cookie(
                              name = "otoroshi-canary",
                              value = s"${env.sign(trackingId)}::$trackingId",
                              maxAge = Some(2592000),
                              path = "/",
                              domain = Some(req.host),
                              httpOnly = false
                            )
                          )

                      //desc.isUp.flatMap(iu => splitToCanary(desc, trackingId).fast.map(d => (iu, d))).fast.flatMap {
                      splitToCanary(desc, trackingId, reqNumber, globalConfig).fast.flatMap { _desc =>
                        val isUp = true

                        val descriptor = if (env.redirectToDev) _desc.copy(env = "dev") else _desc

                        def callDownstream(config: GlobalConfig,
                                           apiKey: Option[ApiKey] = None,
                                           paUsr: Option[PrivateAppsUser] = None): Future[Result] =
                          if (config.useCircuitBreakers && descriptor.clientConfig.useCircuitBreaker) {
                            val cbStart = System.currentTimeMillis()
                            val counter = new AtomicInteger(0)
                            env.circuitBeakersHolder
                              .get(desc.id, () => new ServiceDescriptorCircuitBreaker())
                              .call(
                                descriptor,
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
                            val index = reqCounter.get() % (if (descriptor.targets.nonEmpty)
                                                              descriptor.targets.size
                                                            else 1)
                            // Round robin loadbalancing is happening here !!!!!
                            val target = descriptor.targets.apply(index.toInt)
                            actuallyCallDownstream(target, apiKey, paUsr, 0L, 1)
                          }

                        def actuallyCallDownstream(target: Target,
                                                   apiKey: Option[ApiKey] = None,
                                                   paUsr: Option[PrivateAppsUser] = None,
                                                   cbDuration: Long,
                                                   callAttempts: Int): Future[Result] = {
                          val snowflake        = env.snowflakeGenerator.nextIdStr()
                          val requestTimestamp = DateTime.now().toString("yyyy-MM-dd'T'HH:mm:ss.SSSZZ")
                          val state            = IdGenerator.extendedToken(128)
                          val rawUri           = req.relativeUri.substring(1)
                          val uriParts         = rawUri.split("/").toSeq
                          val uri: String =
                            descriptor.matchingRoot.map(m => req.relativeUri.replace(m, "")).getOrElse(rawUri)
                          val scheme                 = if (descriptor.redirectToLocal) descriptor.localScheme else target.scheme
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
                            .withClaims(paUsr.flatMap(_.otoroshiData).orElse(apiKey.map(_.metadata)))
                            .serialize(env)
                          logger.trace(s"Claim is : $claim")
                          val headersIn: Seq[(String, String)] =
                            (req.headers.toSimpleMap
                              .filterNot(
                                t =>
                                  if (t._1.toLowerCase == "content-type" && !currentReqHasBody) true
                                  else if (t._1.toLowerCase == "content-length") true
                                  else false
                              )
                              .filterNot(t => headersInFiltered.contains(t._1.toLowerCase)) ++ Map(
                              env.Headers.OtoroshiProxiedHost      -> req.headers.get("Host").getOrElse("--"),
                              "Host"                               -> host,
                              env.Headers.OtoroshiRequestId        -> snowflake,
                              env.Headers.OtoroshiRequestTimestamp -> requestTimestamp
                            ) ++ (if (descriptor.enforceSecureCommunication) {
                                    Map(
                                      env.Headers.OtoroshiState -> state,
                                      env.Headers.OtoroshiClaim -> claim
                                    )
                                  } else {
                                    Map.empty[String, String]
                                  }) ++
                            req.headers
                              .get("Content-Length")
                              .map(l => {
                                Map("Content-Length" -> (l.toInt + snowMonkeyContext.trailingRequestBodySize).toString)
                              })
                              .getOrElse(Map.empty[String, String]) ++
                            descriptor.additionalHeaders.filter(t => t._1.trim.nonEmpty) ++ fromOtoroshi
                              .map(v => Map(env.Headers.OtoroshiGatewayParentRequest -> fromOtoroshi.get))
                              .getOrElse(Map.empty[String, String])).toSeq

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
                          val body = if (currentReqHasBody) SourceBody(lazySource) else EmptyBody // Stream IN
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
                                scheme = req.headers
                                  .get("X-Forwarded-Protocol")
                                  .map(_ == "https")
                                  .orElse(Some(req.secure))
                                  .map {
                                    case true  => "https"
                                    case false => "http"
                                  }
                                  .getOrElse("http"),
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

                              quotas.andThen {
                                case Success(q) => {
                                  val fromLbl = req.headers.get(env.Headers.OtoroshiVizFromLabel).getOrElse("internet")
                                  val viz: OtoroshiViz = OtoroshiViz(
                                    to = descriptor.id,
                                    toLbl = descriptor.name,
                                    from = req.headers.get(env.Headers.OtoroshiVizFrom).getOrElse("internet"),
                                    fromLbl = fromLbl,
                                    fromTo = s"$fromLbl###${descriptor.name}"
                                  )
                                  GatewayEvent(
                                    `@id` = env.snowflakeGenerator.nextIdStr(),
                                    reqId = snowflake,
                                    parentReqId = fromOtoroshi,
                                    `@timestamp` = DateTime.now(),
                                    protocol = req.version,
                                    to = Location(
                                      scheme = req.headers
                                        .get("X-Forwarded-Protocol")
                                        .map(_ == "https")
                                        .orElse(Some(req.secure))
                                        .map {
                                          case true  => "https"
                                          case false => "http"
                                        }
                                        .getOrElse("http"),
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
                                    `@serviceId` = descriptor.id,
                                    `@service` = descriptor.name,
                                    descriptor = descriptor,
                                    `@product` = descriptor.metadata.getOrElse("product", "--"),
                                    remainingQuotas = q,
                                    viz = Some(viz)
                                  ).toAnalytics()
                                }
                              }(env.otoroshiExecutionContext) // pressure EC
                            }
                          }(env.otoroshiExecutionContext) // pressure EC
                          //.andThen {
                          //  case _ => env.datastores.requestsDataStore.decrementProcessedRequests()
                          //}
                          val upstreamStart = System.currentTimeMillis()
                          env.gatewayClient
                            .url(url)
                            //.withRequestTimeout(descriptor.clientConfig.callTimeout.millis)
                            .withRequestTimeout(6.hour) // we should monitor leaks
                            .withMethod(req.method)
                            // .withQueryString(queryString: _*)
                            .withHttpHeaders(headersIn: _*)
                            .withBody(body)
                            .withFollowRedirects(false)
                            .stream()
                            .fast
                            .flatMap(resp => quotas.fast.map(q => (resp, q)))
                            .flatMap { tuple =>
                              val (resp, remainingQuotas) = tuple
                              // val responseHeader          = ByteString(s"HTTP/1.1 ${resp.headers.status}")
                              val headers = resp.headers.mapValues(_.head)
                              // logger.warn(s"Connection: ${resp.headers.headers.get("Connection").map(_.last)}")
                              // if (env.notDev && !headers.get(env.Headers.OtoroshiStateResp).contains(state)) {
                              // val validState = headers.get(env.Headers.OtoroshiStateResp).filter(c => env.crypto.verifyString(state, c)).orElse(headers.get(env.Headers.OtoroshiStateResp).contains(state)).getOrElse(false)
                              if (env.notDev && descriptor.enforceSecureCommunication
                                  && !descriptor.isUriExcludedFromSecuredCommunication("/" + uri)
                                  && !headers.get(env.Headers.OtoroshiStateResp).contains(state)) {
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
                                    overhead = (System.currentTimeMillis() - secondStart) + firstOverhead,
                                    cbDuration = cbDuration,
                                    callAttempts = callAttempts
                                  )
                                } else if (isUp) {
                                  // val body = Await.result(resp.body.runFold(ByteString.empty)((a, b) => a.concat(b)).map(_.utf8String), Duration("10s"))
                                  val exchange = Json.prettyPrint(
                                    Json.obj(
                                      "uri"   -> req.relativeUri,
                                      "url"   -> url,
                                      "state" -> state,
                                      "reveivedState" -> JsString(
                                        headers.getOrElse(env.Headers.OtoroshiStateResp, "--")
                                      ),
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
                                        headers.map(t => Json.obj("name" -> t._1, "values" -> t._2)).toSeq
                                      )
                                    )
                                  )
                                  logger.error(s"\n\nError while talking with downstream service :(\n\n$exchange\n\n")
                                  Errors.craftResponseResult(
                                    "Downstream microservice does not seems to be secured. Cancelling request !",
                                    BadGateway,
                                    req,
                                    Some(descriptor),
                                    Some("errors.service.not.secured"),
                                    duration = System.currentTimeMillis - start,
                                    overhead = (System.currentTimeMillis() - secondStart) + firstOverhead,
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
                                    overhead = (System.currentTimeMillis() - secondStart) + firstOverhead,
                                    cbDuration = cbDuration,
                                    callAttempts = callAttempts
                                  )
                                }
                              } else {
                                val upstreamLatency = System.currentTimeMillis() - upstreamStart
                                val headersOut = headers.toSeq
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
                                ) ++ Some(trackingId)
                                  .filter(_ => desc.canary.enabled)
                                  .map(_ => env.Headers.OtoroshiTrackerId -> s"${env.sign(trackingId)}::$trackingId") ++ (if (descriptor.sendOtoroshiHeadersBack && apiKey.isDefined) {
                                                                                                                            Seq(
                                                                                                                              env.Headers.OtoroshiDailyCallsRemaining   -> remainingQuotas.remainingCallsPerDay.toString,
                                                                                                                              env.Headers.OtoroshiMonthlyCallsRemaining -> remainingQuotas.remainingCallsPerMonth.toString
                                                                                                                            )
                                                                                                                          } else {
                                                                                                                            Seq.empty[(String, String)]
                                                                                                                          })
                                val contentType    = headers.getOrElse("Content-Type", MimeTypes.TEXT)
                                val contentTypeOpt = resp.headers.get("Content-Type").flatMap(_.lastOption)
                                // meterOut.mark(responseHeader.length)
                                // counterOut.addAndGet(responseHeader.length)

                                val finalStream = resp.bodyAsSource
                                  .concat(snowMonkeyContext.trailingResponseBodyStream)
                                  .alsoTo(Sink.onComplete {
                                    case Success(_) =>
                                      // debugLogger.trace(s"end of stream for ${protocol}://${req.host}${req.relativeUri}")
                                      promise.trySuccess(
                                        ProxyDone(resp.status, upstreamLatency, headersOut.map(Header.apply))
                                      )
                                    case Failure(e) =>
                                      logger.error(
                                        s"error while transfering stream for ${protocol}://${req.host}${req.relativeUri}",
                                        e
                                      )
                                      promise.trySuccess(
                                        ProxyDone(resp.status, upstreamLatency, headersOut.map(Header.apply))
                                      )
                                  })
                                  .map { bs =>
                                    // debugLogger.trace(s"chunk on ${req.relativeUri} => ${bs.utf8String}")
                                    // meterOut.mark(bs.length)
                                    counterOut.addAndGet(bs.length)
                                    bs
                                  }

                                if (req.version == "HTTP/1.0") {
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
                                    .map { body =>
                                      Status(resp.status)(body)
                                        .withHeaders(headersOut.filterNot(_._1 == "Content-Type"): _*)
                                        .as(contentType)
                                        .withCookies(withTrackingCookies: _*)
                                    }
                                } else if (globalConfig.streamEntityOnly) { // only temporary
                                  // stream out
                                  val entity =
                                    if (resp.headers
                                          .get("Transfer-Encoding")
                                          .flatMap(_.lastOption)
                                          .contains("chunked")) {
                                      HttpEntity.Chunked(
                                        finalStream
                                          .map(i => play.api.http.HttpChunk.Chunk(i))
                                          .concat(
                                            Source.single(play.api.http.HttpChunk.LastChunk(play.api.mvc.Headers()))
                                          ),
                                        Some(contentType) // contentTypeOpt
                                      )
                                    } else {
                                      HttpEntity.Streamed(
                                        finalStream,
                                        resp.headers
                                          .get("Content-Length")
                                          .flatMap(_.lastOption)
                                          .map(_.toLong + snowMonkeyContext.trailingResponseBodySize),
                                        Some(contentType) // contentTypeOpt
                                      )
                                    }
                                  FastFuture.successful(
                                    Status(resp.status)
                                      .sendEntity(entity)
                                      .withHeaders(headersOut.filterNot(_._1 == "Content-Type"): _*)
                                      .as(contentType)
                                      .withCookies(withTrackingCookies: _*)
                                  )
                                } else {
                                  val response = resp.headers
                                    .get("Transfer-Encoding")
                                    .flatMap(_.lastOption)
                                    .filter(_ == "chunked")
                                    .map { _ =>
                                      // stream out
                                      Status(resp.status)
                                        .chunked(finalStream)
                                        .withHeaders(headersOut: _*)
                                        .withCookies(withTrackingCookies: _*)
                                      // .as(contentType)
                                    } getOrElse {
                                    // stream out
                                    Status(resp.status)
                                      .sendEntity(
                                        HttpEntity.Streamed(
                                          finalStream,
                                          resp.headers
                                            .get("Content-Length")
                                            .flatMap(_.lastOption)
                                            .map(_.toLong + snowMonkeyContext.trailingResponseBodySize),
                                          resp.headers.get("Content-Type").flatMap(_.lastOption)
                                        )
                                      )
                                      .withHeaders(headersOut.filterNot(_._1 == "Content-Type"): _*)
                                      .withCookies(withTrackingCookies: _*)
                                      .as(contentType)
                                  }
                                  FastFuture.successful(response)
                                }
                              }
                            }
                        }

                        def passWithApiKey(config: GlobalConfig): Future[Result] = {
                          val authByJwtToken = req.headers
                            .get(env.Headers.OtoroshiAuthorization)
                            .orElse(req.headers.get("Authorization"))
                            .filter(_.startsWith("Bearer "))
                            .map(_.replace("Bearer ", ""))
                            .orElse(req.queryString.get(env.Headers.OtoroshiBearerAuthorization).flatMap(_.lastOption))
                            .orElse(req.cookies.get(env.Headers.OtoroshiJWTAuthorization).map(_.value))
                          val authBasic = req.headers
                            .get(env.Headers.OtoroshiAuthorization)
                            .orElse(req.headers.get("Authorization"))
                            .filter(_.startsWith("Basic "))
                            .map(_.replace("Basic ", ""))
                            .flatMap(e => Try(decodeBase64(e)).toOption)
                            .orElse(
                              req.queryString
                                .get(env.Headers.OtoroshiBasicAuthorization)
                                .flatMap(_.lastOption)
                                .flatMap(e => Try(decodeBase64(e)).toOption)
                            )
                          val authByCustomHeaders = req.headers
                            .get(env.Headers.OtoroshiClientId)
                            .flatMap(id => req.headers.get(env.Headers.OtoroshiClientSecret).map(s => (id, s)))
                          if (authByCustomHeaders.isDefined) {
                            val (clientId, clientSecret) = authByCustomHeaders.get
                            env.datastores.apiKeyDataStore.findAuthorizeKeyFor(clientId, descriptor.id).flatMap {
                              case None =>
                                Errors.craftResponseResult(
                                  "Invalid API key",
                                  BadGateway,
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
                                  BadGateway,
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
                                      overhead = (System.currentTimeMillis() - secondStart) + firstOverhead
                                    )
                                }
                            }
                          } else if (authByJwtToken.isDefined) {
                            val jwtTokenValue = authByJwtToken.get
                            Try {
                              JWT.decode(jwtTokenValue)
                            } map { jwt =>
                              Option(jwt.getClaim("iss")).map(_.asString()) match {
                                case Some(clientId) =>
                                  env.datastores.apiKeyDataStore.findAuthorizeKeyFor(clientId, descriptor.id).flatMap {
                                    case Some(apiKey) => {
                                      val algorithm = Option(jwt.getAlgorithm).map {
                                        case "HS256" => Algorithm.HMAC256(apiKey.clientSecret)
                                        case "HS512" => Algorithm.HMAC512(apiKey.clientSecret)
                                      } getOrElse Algorithm.HMAC512(apiKey.clientSecret)
                                      val verifier = JWT.require(algorithm).withIssuer(apiKey.clientName).build
                                      Try(verifier.verify(jwtTokenValue)).filter { token =>
                                        val xsrfToken       = token.getClaim("xsrfToken")
                                        val xsrfTokenHeader = req.headers.get("X-XSRF-TOKEN")
                                        if (!xsrfToken.isNull && xsrfTokenHeader.isDefined) {
                                          xsrfToken.asString() == xsrfTokenHeader.get
                                        } else if (!xsrfToken.isNull && xsrfTokenHeader.isEmpty) {
                                          false
                                        } else {
                                          true
                                        }
                                      } match {
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
                                                overhead = (System.currentTimeMillis() - secondStart) + firstOverhead
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
                                            "Bad API key",
                                            BadRequest,
                                            req,
                                            Some(descriptor),
                                            Some("errors.bad.api.key"),
                                            duration = System.currentTimeMillis - start,
                                            overhead = (System.currentTimeMillis() - secondStart) + firstOverhead
                                          )
                                        }
                                      }
                                    }
                                    case None =>
                                      Errors.craftResponseResult(
                                        "Invalid ApiKey provided",
                                        BadRequest,
                                        req,
                                        Some(descriptor),
                                        Some("errors.invalid.api.key"),
                                        duration = System.currentTimeMillis - start,
                                        overhead = (System.currentTimeMillis() - secondStart) + firstOverhead
                                      )
                                  }
                                case None =>
                                  Errors.craftResponseResult(
                                    "Invalid ApiKey provided",
                                    BadRequest,
                                    req,
                                    Some(descriptor),
                                    Some("errors.invalid.api.key"),
                                    duration = System.currentTimeMillis - start,
                                    overhead = (System.currentTimeMillis() - secondStart) + firstOverhead
                                  )
                              }
                            } getOrElse Errors.craftResponseResult(
                              "Invalid ApiKey provided",
                              BadRequest,
                              req,
                              Some(descriptor),
                              Some("errors.invalid.api.key"),
                              duration = System.currentTimeMillis - start,
                              overhead = (System.currentTimeMillis() - secondStart) + firstOverhead
                            )
                          } else if (authBasic.isDefined) {
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
                                        BadGateway,
                                        req,
                                        Some(descriptor),
                                        Some("errors.invalid.api.key"),
                                        duration = System.currentTimeMillis - start,
                                        overhead = (System.currentTimeMillis() - secondStart) + firstOverhead
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
                                            overhead = (System.currentTimeMillis() - secondStart) + firstOverhead
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

                        def passWithAuth0(config: GlobalConfig): Future[Result] =
                          isPrivateAppsSessionValid(req).flatMap {
                            case Some(paUsr) => callDownstream(config, paUsr = Some(paUsr))
                            case None => {
                              val redirectTo = env.rootScheme + env.privateAppsHost + controllers.routes.Auth0Controller
                                .privateAppsLoginPage(Some(s"http://${req.host}${req.relativeUri}"))
                                .url
                              logger.trace("should redirect to " + redirectTo)
                              FastFuture.successful(
                                Results
                                  .Redirect(redirectTo)
                                  .discardingCookies(
                                    env.removePrivateSessionCookies(
                                      ServiceDescriptorQuery(subdomain, serviceEnv, domain, "/").toHost
                                    ): _*
                                  )
                              )
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
                            if (env.isProd && !isSecured && desc.forceHttps) {
                              val theDomain = req.domain
                              val protocol = req.headers
                                .get("X-Forwarded-Protocol")
                                .map(_ == "https")
                                .orElse(Some(req.secure))
                                .map {
                                  case true  => "https"
                                  case false => "http"
                                }
                                .getOrElse("http")
                              logger.info(
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
                              val zeros                  = ByteString.fromInts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
                              val characters: ByteString = if (!globalConfig.middleFingers) middleFingers else zeros
                              val expected: Long         = (gigas / characters.size) + 1L
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
                            } else if (isUp) {
                              if (descriptor.isPrivate) {
                                if (descriptor.isUriPublic(req.path)) {
                                  passWithAuth0(globalConfig)
                                } else {
                                  isPrivateAppsSessionValid(req).fast.flatMap {
                                    case Some(user) => passWithAuth0(globalConfig)
                                    case None       => passWithApiKey(globalConfig)
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
    finalResult.andThen {
      case _ =>
        val requests = env.datastores.requestsDataStore.decrementHandledRequests()
        // globalConfig.statsdConfig
        // env.datastores.globalConfigDataStore
        //  .singleton()
        //  .fast
        //  .map(
        //    config =>
        env.statsd.meter(s"${env.snowflakeSeed}.concurrent-requests", requests.toDouble)(globalConfig.statsdConfig)
      //  )
    }(env.otoroshiExecutionContext)
  }

  def decodeBase64(encoded: String): String = new String(OtoroshiClaim.decoder.decode(encoded), Charsets.UTF_8)
}
