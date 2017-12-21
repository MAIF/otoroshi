package gateway

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicLong}

import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.util.FastFuture._
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.google.common.base.Charsets
import env.Env
import events._
import models._
import utils.MaxLengthLimiter
import org.joda.time.DateTime
import play.api.Logger
import play.api.http.{Status => _, _}
import play.api.libs.json.{JsArray, JsString, Json}
import play.api.libs.streams.Accumulator
import play.api.libs.ws.{EmptyBody, StreamedBody}
import play.api.mvc.Results._
import play.api.mvc._
import play.api.routing.Router
import security.{IdGenerator, OpunClaim}
import utils.RegexPool

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NoStackTrace
import scala.util.{Failure, Success, Try}

case class ProxyDone(status: Int, upstreamLatency: Long)

class ErrorHandler()(implicit env: Env) extends HttpErrorHandler {

  import env.gatewayActorSystem.dispatcher

  lazy val logger = Logger("otoroshi-error-handler")

  def onClientError(request: RequestHeader, statusCode: Int, mess: String) = {
    val (message, image): (String, String) = Option
      .apply(mess)
      .filterNot(_.trim.isEmpty)
      // .map(m => (m, "hugeMistake.jpg"))
      .map(m => ("An error occurred ...", "hugeMistake.jpg"))
      .orElse(Errors.messages.get(statusCode))
      .getOrElse(("Client Error", "hugeMistake.jpg"))
    logger.error(s"Client Error: $message on ${request.uri} ($statusCode)")
    Errors.craftResponseResult(message, Status(statusCode), request, None, Some("errors.client.error"))
  }

  def onServerError(request: RequestHeader, exception: Throwable) = {
    logger.error(s"Server Error ${exception.getMessage} on ${request.uri}", exception)
    Errors.craftResponseResult("An error occurred ...", InternalServerError, request, None, Some("errors.server.error"))
  }
}

object SameThreadExecutionContext extends ExecutionContext {
  override def reportFailure(t: Throwable): Unit =
    throw new IllegalStateException("exception in SameThreadExecutionContext", t) with NoStackTrace
  override def execute(runnable: Runnable): Unit = runnable.run()
}

class GatewayRequestHandler(webSocketHandler: WebSocketHandler,
                            router: Router,
                            errorHandler: HttpErrorHandler,
                            configuration: HttpConfiguration,
                            filters: HttpFilters)(implicit env: Env, mat: Materializer)
    extends DefaultHttpRequestHandler(router, errorHandler, configuration, filters) {

  implicit lazy val ec        = env.gatewayExecutor
  implicit lazy val scheduler = env.gatewayActorSystem.scheduler

  lazy val logger = Logger("otoroshi-http-handler")
  lazy val debugLogger = Logger("otoroshi-http-handler-debug")

  val sourceBodyParser = BodyParser("Gateway BodyParser") { _ =>
    Accumulator.source[ByteString].map(Right.apply)
  }

  val reqCounter = new AtomicInteger(0)

  val headersInFiltered = Seq(
    env.Headers.OpunGatewayState,
    env.Headers.OpunGatewayClaim,
    env.Headers.OpunGatewayRequestId,
    env.Headers.OpunClientId,
    env.Headers.OpunClientSecret,
    "Host",
    "X-Forwarded-For",
    "X-Forwarded-Proto",
    "X-Forwarded-Protocol"
  ).map(_.toLowerCase)

  val headersOutFiltered = Seq(
    env.Headers.OpunGatewayStateResp,
    "Transfer-Encoding",
    "Content-Length"
  ).map(_.toLowerCase)

  // TODO : very dirty ... fix it using Play 2.6 request.hasBody
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
    val url     = ByteString(s"$protocol://${request.host}${request.uri}")
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
        case str if matchRedirection(str)                                   => Some(redirectToMainDomain())
        case _ if request.uri.contains("__opun_assets")                     => super.routeRequest(request)
        case _ if request.uri.startsWith("/__otoroshi_private_apps_login")  => Some(setPrivateAppsCookies())
        case _ if request.uri.startsWith("/__otoroshi_private_apps_logout") => Some(removePrivateAppsCookies())
        case env.backOfficeHost if !isSecured && toHttps && env.isProd      => Some(redirectToHttps())
        case env.privateAppsHost if !isSecured && toHttps && env.isProd     => Some(redirectToHttps())
        case env.adminApiHost                                               => super.routeRequest(request)
        case env.backOfficeHost                                             => super.routeRequest(request)
        case env.privateAppsHost                                            => super.routeRequest(request)
        case _ =>
          request.headers.get("Sec-WebSocket-Version") match {
            case None    => Some(forwardCall())
            case Some(_) => Some(webSocketHandler.proxyWebSocket())
          }
      }
    }
  }

  def setPrivateAppsCookies() = Action.async { req =>
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

  def removePrivateAppsCookies() = Action.async { req =>
    val redirectToOpt: Option[String] = req.queryString.get("redirectTo").map(_.last)
    val hostOpt: Option[String]       = req.queryString.get("host").map(_.last)
    (redirectToOpt, hostOpt) match {
      case (Some(redirectTo), Some(host)) =>
        FastFuture.successful(Redirect(redirectTo).discardingCookies(env.removePrivateSessionCookies(host): _*))
      case _ =>
        Errors.craftResponseResult("Missing parameters", BadRequest, req, None, Some("errors.missing.parameters"))
    }
  }

  def tooBig(message: String) = Action.async { req =>
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

  def redirectToHttps() = Action { req =>
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
      s"redirectToHttps from ${protocol}://$domain${req.uri} to ${env.rootScheme}$domain${req.uri}"
    )
    Redirect(s"${env.rootScheme}$domain${req.uri}").withHeaders("opun-redirect-to" -> "https")
  }

  def redirectToMainDomain() = Action { req =>
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
    logger.warn(s"redirectToMainDomain from $protocol://${req.domain}${req.uri} to $protocol://$domain${req.uri}")
    Redirect(s"$protocol://$domain${req.uri}")
  }

  def splitToCanary(desc: ServiceDescriptor, trackingId: String)(implicit env: Env): Future[ServiceDescriptor] = {
    if (desc.canary.enabled) {
      env.datastores.canaryDataStore.isCanary(desc.id, trackingId, desc.canary.traffic).fast.map {
        case false => desc
        case true  => desc.copy(targets = desc.canary.targets, root = desc.canary.root)
      }
    } else {
      FastFuture.successful(desc)
    }
  }

  def forwardCall() = Action.async(sourceBodyParser) { req =>
    // TODO : add metrics + JMX
    // val meterIn             = Metrics.metrics.meter("GatewayDataIn")
    // val meterOut            = Metrics.metrics.meter("GatewayDataOut")
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
    val finalResult =
      env.datastores.globalConfigDataStore.singleton().flatMap { globalConfig => // Very consuming but eh !!!
        env.statsd.meter(s"${env.snowflakeSeed}.concurrent-requests", currentHandledRequests.toDouble)(
          globalConfig.statsdConfig
        )
        if (currentHandledRequests > globalConfig.maxConcurrentRequests) {
          Audit.send(
            MaxConcurrentRequestReachedEvent(
              env.snowflakeGenerator.nextId().toString,
              env.env,
              globalConfig.maxConcurrentRequests,
              currentHandledRequests
            )
          )
          Alerts.send(
            MaxConcurrentRequestReachedAlert(
              env.snowflakeGenerator.nextId().toString,
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
              Errors.craftResponseResult(s"Service not found for URL ${req.host}::${req.uri}",
                                         NotFound,
                                         req,
                                         None,
                                         Some("errors.service.not.found"))
            case Some(ServiceLocation(domain, serviceEnv, subdomain)) => {
              val uriParts = req.uri.split("/").toSeq
              val root     = if (uriParts.isEmpty) "/" else "/" + uriParts.tail.head

              env.datastores.serviceDescriptorDataStore
                .find(ServiceDescriptorQuery(subdomain, serviceEnv, domain, root, req.headers.toSimpleMap))
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
                  case Some(desc) => {

                    val maybeTrackingId = req.cookies
                      .get("otoroshi-canary")
                      .map(_.value)
                      .orElse(req.headers.get(env.Headers.OpunTrackerId))
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
                    val trackingId: String = maybeTrackingId.getOrElse(IdGenerator.uuid)

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

                    desc.isUp.flatMap(iu => splitToCanary(desc, trackingId).fast.map(d => (iu, d))).fast.flatMap {
                      tuple =>
                        val (isUp, _desc) = tuple

                        val descriptor = if (env.redirectToDev) _desc.copy(env = "dev") else _desc

                        def callDownstream(config: GlobalConfig,
                                           apiKey: Option[ApiKey] = None,
                                           paUsr: Option[PrivateAppsUser] = None): Future[Result] =
                          if (config.useCircuitBreakers && descriptor.clientConfig.useCircuitBreaker) {
                            env.circuitBeakersHolder
                              .get(desc.id, () => new ServiceDescriptorCircuitBreaker())
                              .call(desc, bodyAlreadyConsumed, (t) => actuallyCallDownstream(t, apiKey, paUsr)) recoverWith {
                              case BodyAlreadyConsumedException =>
                                Errors.craftResponseResult(
                                  s"Something went wrong, the downstream service does not respond quickly enough but consumed all the request body, you should try later. Thanks for your understanding",
                                  BadGateway,
                                  req,
                                  Some(descriptor),
                                  Some("errors.request.timeout")
                                )
                              case RequestTimeoutException =>
                                Errors.craftResponseResult(
                                  s"Something went wrong, the downstream service does not respond quickly enough, you should try later. Thanks for your understanding",
                                  BadGateway,
                                  req,
                                  Some(descriptor),
                                  Some("errors.request.timeout")
                                )
                              case AllCircuitBreakersOpenException =>
                                Errors.craftResponseResult(
                                  s"Something went wrong, the downstream service seems a little bit overwhelmed, you should try later. Thanks for your understanding",
                                  BadGateway,
                                  req,
                                  Some(descriptor),
                                  Some("errors.circuit.breaker.open")
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
                                  Some("errors.connection.refused")
                                )
                              case error if error != null && error.getMessage != null =>
                                Errors.craftResponseResult(
                                  s"Something went wrong, you should try later. Thanks for your understanding. ${error.getMessage}",
                                  BadGateway,
                                  req,
                                  Some(descriptor),
                                  Some("errors.proxy.error")
                                )
                              case error =>
                                Errors.craftResponseResult(
                                  s"Something went wrong, you should try later. Thanks for your understanding",
                                  BadGateway,
                                  req,
                                  Some(descriptor),
                                  Some("errors.proxy.error")
                                )
                            }
                          } else {
                            val index = reqCounter.incrementAndGet() % (if (descriptor.targets.nonEmpty)
                                                                          descriptor.targets.size
                                                                        else 1)
                            // Round robin loadbalancing is happening here !!!!!
                            val target = descriptor.targets.apply(index.toInt)
                            actuallyCallDownstream(target, apiKey, paUsr)
                          }

                        def actuallyCallDownstream(target: Target,
                                                   apiKey: Option[ApiKey] = None,
                                                   paUsr: Option[PrivateAppsUser] = None): Future[Result] = {
                          val snowflake = env.snowflakeGenerator.nextId().toString
                          val state     = IdGenerator.extendedToken(128)
                          val rawUri    = req.uri.substring(1)
                          val uriParts  = rawUri.split("/").toSeq
                          val uri: String =
                            if (descriptor.matchingRoot.isDefined) uriParts.tail.mkString("/") else rawUri
                          val scheme = if (descriptor.redirectToLocal) descriptor.localScheme else target.scheme
                          val host   = if (descriptor.redirectToLocal) descriptor.localHost else target.host
                          val root   = descriptor.root
                          val url    = s"$scheme://$host$root$uri"
                          // val queryString = req.queryString.toSeq.flatMap { case (key, values) => values.map(v => (key, v)) }
                          val fromOtoroshi = req.headers
                            .get(env.Headers.OpunGatewayRequestId)
                            .orElse(req.headers.get(env.Headers.OpunGatewayParentRequest))
                          val promise = Promise[ProxyDone]

                          val claim = OpunClaim(
                            iss = env.Headers.OpunGateway,
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
                            .withClaims(paUsr.flatMap(_.opunData).orElse(apiKey.map(_.metadata)))
                            .serialize(env)
                          logger.trace(s"Claim is : $claim")
                          val headersIn: Seq[(String, String)] =
                            (req.headers.toSimpleMap
                              .filterNot(t => headersInFiltered.contains(t._1.toLowerCase)) ++ Map(
                              env.Headers.OpunProxiedHost      -> req.headers.get("Host").getOrElse("--"),
                              "Host"                           -> host,
                              env.Headers.OpunGatewayRequestId -> snowflake,
                              env.Headers.OpunGatewayState     -> state,
                              env.Headers.OpunGatewayClaim     -> claim
                            ) ++ descriptor.additionalHeaders ++ fromOtoroshi
                              .map(v => Map(env.Headers.OpunGatewayParentRequest -> fromOtoroshi.get))
                              .getOrElse(Map.empty[String, String])).toSeq

                          val lazySource = Source.single(ByteString.empty).flatMapConcat { _ =>
                            bodyAlreadyConsumed.compareAndSet(false, true)
                            req.body.map(bs => {
                              // meterIn.mark(bs.length)
                              counterIn.addAndGet(bs.length)
                              bs
                            })
                          }
                          val body = if (hasBody(req)) StreamedBody(lazySource) else EmptyBody // Stream IN
                          // val requestHeader = ByteString(
                          //   req.method + " " + req.uri + " HTTP/1.1\n" + headersIn
                          //     .map(h => s"${h._1}: ${h._2}")
                          //     .mkString("\n") + "\n"
                          // )
                          // meterIn.mark(requestHeader.length)
                          // counterIn.addAndGet(requestHeader.length)
                          // logger.trace(s"curl -X ${req.method.toUpperCase()} ${headersIn.map(h => s"-H '${h._1}: ${h._2}'").mkString(" ")} '$url?${queryString.map(h => s"${h._1}=${h._2}").mkString("&")}' --include")
                          debugLogger.trace(
                            s"curl -X ${req.method.toUpperCase()} ${headersIn.map(h => s"-H '${h._1}: ${h._2}'").mkString(" ")} '$url' --include"
                          )
                          val overhead = System.currentTimeMillis() - start
                          val quotas: Future[RemainingQuotas] =
                            apiKey.map(_.updateQuotas()).getOrElse(FastFuture.successful(RemainingQuotas()))
                          promise.future.andThen {
                            case Success(resp) => {

                              val actualDuration: Long = System.currentTimeMillis() - start
                              val duration: Long =
                                if (descriptor.id == env.backOfficeServiceId && actualDuration > 300L) 300L
                                else actualDuration
                              // logger.trace(s"[$snowflake] Call forwarded in $duration ms. with $overhead ms overhead for (${req.version}, http://${req.host}${req.uri} => $url, $from)")
                              descriptor
                                .updateMetrics(duration,
                                               overhead,
                                               counterIn.get(),
                                               counterOut.get(),
                                               resp.upstreamLatency,
                                               globalConfig)
                                .andThen {
                                  case Failure(e) => logger.error("Error while updating call metrics reporting", e)
                                }
                              env.datastores.globalConfigDataStore.updateQuotas(globalConfig)
                              quotas.andThen {
                                case Success(q) => {
                                  val fromLbl = req.headers.get(env.Headers.OpunVizFromLabel).getOrElse("internet")
                                  val viz: OpunViz = OpunViz(
                                    to = descriptor.id,
                                    toLbl = descriptor.name,
                                    from = req.headers.get(env.Headers.OpunVizFrom).getOrElse("internet"),
                                    fromLbl = fromLbl,
                                    fromTo = s"$fromLbl###${descriptor.name}"
                                  )
                                  GatewayEvent(
                                    `@id` = env.snowflakeGenerator.nextId().toString,
                                    reqId = snowflake.toLong,
                                    parentReqId = fromOtoroshi.map(_.toLong),
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
                                      uri = req.uri
                                    ),
                                    target = Location(
                                      scheme = scheme,
                                      host = host,
                                      uri = req.uri
                                    ),
                                    duration = duration,
                                    overhead = overhead,
                                    url = url,
                                    from = from,
                                    env = descriptor.env,
                                    data = DataInOut(
                                      dataIn = counterIn.get(),
                                      dataOut = counterOut.get()
                                    ),
                                    status = resp.status,
                                    headers = req.headers.toSimpleMap.toSeq.map(Header.apply),
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
                              }
                            }
                          }
                          //.andThen {
                          //  case _ => env.datastores.requestsDataStore.decrementProcessedRequests()
                          //}
                          val upstreamStart = System.currentTimeMillis()
                          env.gatewayClient
                            .url(url)
                            //.withRequestTimeout(descriptor.clientConfig.callTimeout.millis)
                            .withRequestTimeout(1.hour) // we should monitor leaks
                            .withMethod(req.method)
                            // .withQueryString(queryString: _*)
                            .withHeaders(headersIn: _*)
                            .withBody(body)
                            .withFollowRedirects(false)
                            .stream()
                            .fast
                            .flatMap(resp => quotas.fast.map(q => (resp, q)))
                            .flatMap { tuple =>
                              val (resp, remainingQuotas) = tuple
                              // val responseHeader          = ByteString(s"HTTP/1.1 ${resp.headers.status}")
                              val headers = resp.headers.headers.mapValues(_.head)
                              // logger.warn(s"Connection: ${resp.headers.headers.get("Connection").map(_.last)}")
                              // if (env.notDev && !headers.get(env.Headers.OpunGatewayStateResp).contains(state)) {
                              // val validState = headers.get(env.Headers.OpunGatewayStateResp).filter(c => env.crypto.verifyString(state, c)).orElse(headers.get(env.Headers.OpunGatewayStateResp).contains(state)).getOrElse(false)
                              if (env.notDev && descriptor.enforceSecureCommunication
                                  && !descriptor.isUriExcludedFromSecuredCommunication(uri)
                                  && !headers.get(env.Headers.OpunGatewayStateResp).contains(state)) {
                                if (resp.headers.status == 404 && headers
                                      .get("X-CleverCloudUpgrade")
                                      .contains("true")) {
                                  Errors.craftResponseResult(
                                    "No service found for the specified target host, the service descriptor should be verified !",
                                    NotFound,
                                    req,
                                    Some(descriptor),
                                    Some("errors.no.service.found")
                                  )
                                } else if (isUp) {
                                  // val body = Await.result(resp.body.runFold(ByteString.empty)((a, b) => a.concat(b)).map(_.utf8String), Duration("10s"))
                                  val exchange = Json.prettyPrint(
                                    Json.obj(
                                      "uri"   -> req.uri,
                                      "url"   -> url,
                                      "state" -> state,
                                      "reveivedState" -> JsString(
                                        headers.getOrElse(env.Headers.OpunGatewayStateResp, "--")
                                      ),
                                      "claim"  -> claim,
                                      "method" -> req.method,
                                      "query"  -> req.rawQueryString,
                                      "status" -> resp.headers.status,
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
                                    Some("errors.service.not.secured")
                                  )
                                } else {
                                  Errors.craftResponseResult("The service seems to be down :( come back later",
                                                             Forbidden,
                                                             req,
                                                             Some(descriptor),
                                                             Some("errors.service.down"))
                                }
                              } else {
                                val upstreamLatency = System.currentTimeMillis() - upstreamStart
                                val headersOut = headers.toSeq
                                  .filterNot(t => headersOutFiltered.contains(t._1.toLowerCase)) ++ Seq(
                                  env.Headers.OpunGatewayRequestId       -> snowflake,
                                  env.Headers.OpunGatewayProxyLatency    -> s"$overhead",
                                  env.Headers.OpunGatewayUpstreamLatency -> s"$upstreamLatency" //,
                                  //env.Headers.OpunTrackerId              -> s"${env.sign(trackingId)}::$trackingId"
                                ) ++ Some(trackingId)
                                  .filter(_ => desc.canary.enabled)
                                  .map(_ => env.Headers.OpunTrackerId -> s"${env.sign(trackingId)}::$trackingId") ++ (if (apiKey.isDefined) {
                                                                                                                        Seq(
                                                                                                                          env.Headers.OpunDailyCallsRemaining   -> remainingQuotas.remainingCallsPerDay.toString,
                                                                                                                          env.Headers.OpunMonthlyCallsRemaining -> remainingQuotas.remainingCallsPerMonth.toString
                                                                                                                        )
                                                                                                                      } else {
                                                                                                                        Seq.empty[(String, String)]
                                                                                                                      })
                                val contentType = headers.getOrElse("Content-Type", MimeTypes.TEXT)
                                // meterOut.mark(responseHeader.length)
                                // counterOut.addAndGet(responseHeader.length)

                                val finalStream = resp.body
                                  .alsoTo(Sink.onComplete {
                                    case Success(_) =>
                                      debugLogger.trace(s"end of stream for ${protocol}://${req.host}${req.uri}")
                                      promise.trySuccess(ProxyDone(resp.headers.status, upstreamLatency))
                                    case Failure(e) =>
                                      logger.error(s"error while transfering stream for ${protocol}://${req.host}${req.uri}", e)
                                      promise.trySuccess(ProxyDone(resp.headers.status, upstreamLatency))
                                  })
                                  .map { bs =>
                                    debugLogger.trace(s"chunk on ${req.uri} => ${bs.utf8String}")
                                    // meterOut.mark(bs.length)
                                    counterOut.addAndGet(bs.length)
                                    bs
                                  }

                                if (req.version == "HTTP/1.0") {
                                  logger.warn(
                                    s"HTTP/1.0 request, storing temporary result in memory :( (${protocol}://${req.host}${req.uri})"
                                  )
                                  finalStream
                                    .via(
                                      MaxLengthLimiter(globalConfig.maxHttp10ResponseSize.toInt,
                                                       str => logger.warn(str))
                                    )
                                    .runWith(Sink.reduce[ByteString]((bs, n) => bs.concat(n)))
                                    .fast
                                    .map { body =>
                                      Status(resp.headers.status)(body)
                                        .withHeaders(headersOut.filterNot(_._1 == "Content-Type"): _*)
                                        .as(contentType)
                                        .withCookies(withTrackingCookies: _*)
                                    }
                                } else if (globalConfig.streamEntityOnly) { // only temporary
                                  // stream out
                                  FastFuture.successful(
                                    Status(resp.headers.status)
                                      .sendEntity(HttpEntity.Streamed(finalStream, None, Some(contentType)))
                                      .withHeaders(headersOut.filterNot(_._1 == "Content-Type"): _*)
                                      .as(contentType)
                                      .withCookies(withTrackingCookies: _*)
                                  )
                                } else {
                                  val response = req.headers.get("Transfer-Encoding").filter(_ == "chunked").map { _ =>
                                    // stream out
                                    Status(resp.headers.status)
                                      .chunked(finalStream)
                                      .withHeaders(headersOut: _*)
                                      .withCookies(withTrackingCookies: _*)
                                    // .as(contentType)
                                  } getOrElse {
                                    // stream out
                                    Status(resp.headers.status)
                                      .sendEntity(HttpEntity.Streamed(finalStream, None, None)) // Some(contentType)))
                                      .withHeaders(headersOut: _*)
                                      .withCookies(withTrackingCookies: _*)
                                    // .as(contentType)
                                  }
                                  FastFuture.successful(response)
                                }
                              }
                            }
                        }

                        def passWithApiKey(config: GlobalConfig): Future[Result] = {
                          val authByJwtToken = req.headers
                            .get("Authorization")
                            .filter(_.startsWith("Bearer "))
                            .map(_.replace("Bearer ", ""))
                            .orElse(req.queryString.get("bearer_auth").flatMap(_.lastOption))
                          val authBasic = req.headers
                            .get("Authorization")
                            .filter(_.startsWith("Basic "))
                            .map(_.replace("Basic ", ""))
                            .flatMap(e => Try(decodeBase64(e)).toOption)
                            .orElse(
                              req.queryString
                                .get("basic_auth")
                                .flatMap(_.lastOption)
                                .flatMap(e => Try(decodeBase64(e)).toOption)
                            )
                          val authByCustomHeaders = req.headers
                            .get(env.Headers.OpunClientId)
                            .flatMap(id => req.headers.get(env.Headers.OpunClientSecret).map(s => (id, s)))
                          if (authByCustomHeaders.isDefined) {
                            val (clientId, clientSecret) = authByCustomHeaders.get
                            env.datastores.apiKeyDataStore.findAuthorizeKeyFor(clientId, descriptor.id).flatMap {
                              case None =>
                                Errors.craftResponseResult("Invalid API key",
                                                           BadGateway,
                                                           req,
                                                           Some(descriptor),
                                                           Some("errors.invalid.api.key"))
                              case Some(key) if key.isInvalid(clientSecret) => {
                                Alerts.send(
                                  RevokedApiKeyUsageAlert(env.snowflakeGenerator.nextId().toString,
                                                          DateTime.now(),
                                                          env.env,
                                                          req,
                                                          key,
                                                          descriptor)
                                )
                                Errors.craftResponseResult("Bad API key",
                                                           BadGateway,
                                                           req,
                                                           Some(descriptor),
                                                           Some("errors.bad.api.key"))
                              }
                              case Some(key) if key.isValid(clientSecret) =>
                                key.withingQuotas().flatMap {
                                  case true => callDownstream(config, Some(key))
                                  case false =>
                                    Errors.craftResponseResult("You performed too much requests",
                                                               TooManyRequests,
                                                               req,
                                                               Some(descriptor),
                                                               Some("errors.too.much.requests"))
                                }
                            }
                          } else if (authByJwtToken.isDefined) {
                            val jwtTokenValue = authByJwtToken.get
                            Try {
                              JWT.decode(jwtTokenValue)
                            } map { jwt =>
                              Option(jwt.getClaim("clientId")).map(_.asString()) match {
                                case Some(clientId) =>
                                  env.datastores.apiKeyDataStore.findAuthorizeKeyFor(clientId, descriptor.id).flatMap {
                                    case Some(apiKey) => {
                                      val algorithm = Option(jwt.getAlgorithm).map {
                                        case "HS256" => Algorithm.HMAC256(apiKey.clientSecret)
                                        case "HS512" => Algorithm.HMAC512(apiKey.clientSecret)
                                      } getOrElse Algorithm.HMAC512(apiKey.clientSecret)
                                      val verifier = JWT.require(algorithm).withIssuer(apiKey.clientName).build
                                      Try(verifier.verify(jwtTokenValue)) match {
                                        case Success(_) =>
                                          apiKey.withingQuotas().flatMap {
                                            case true => callDownstream(config, Some(apiKey))
                                            case false =>
                                              Errors.craftResponseResult("You performed too much requests",
                                                                         TooManyRequests,
                                                                         req,
                                                                         Some(descriptor),
                                                                         Some("errors.too.much.requests"))
                                          }
                                        case Failure(e) => {
                                          Alerts.send(
                                            RevokedApiKeyUsageAlert(env.snowflakeGenerator.nextId().toString,
                                                                    DateTime.now(),
                                                                    env.env,
                                                                    req,
                                                                    apiKey,
                                                                    descriptor)
                                          )
                                          Errors.craftResponseResult("Bad API key",
                                                                     BadGateway,
                                                                     req,
                                                                     Some(descriptor),
                                                                     Some("errors.bad.api.key"))
                                        }
                                      }
                                    }
                                    case None =>
                                      Errors.craftResponseResult("Invalid ApiKey provided",
                                                                 BadRequest,
                                                                 req,
                                                                 Some(descriptor),
                                                                 Some("errors.invalid.api.key"))
                                  }
                                case None =>
                                  Errors.craftResponseResult("Invalid ApiKey provided",
                                                             BadRequest,
                                                             req,
                                                             Some(descriptor),
                                                             Some("errors.invalid.api.key"))
                              }
                            } getOrElse Errors.craftResponseResult("Invalid ApiKey provided",
                                                                   BadRequest,
                                                                   req,
                                                                   Some(descriptor),
                                                                   Some("errors.invalid.api.key"))
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
                                      Errors.craftResponseResult("Invalid API key",
                                                                 BadGateway,
                                                                 req,
                                                                 Some(descriptor),
                                                                 Some("errors.invalid.api.key"))
                                    case Some(key) if key.isInvalid(apiKeySecret) => {
                                      Alerts.send(
                                        RevokedApiKeyUsageAlert(env.snowflakeGenerator.nextId().toString,
                                                                DateTime.now(),
                                                                env.env,
                                                                req,
                                                                key,
                                                                descriptor)
                                      )
                                      Errors.craftResponseResult("Bad API key",
                                                                 BadGateway,
                                                                 req,
                                                                 Some(descriptor),
                                                                 Some("errors.bad.api.key"))
                                    }
                                    case Some(key) if key.isValid(apiKeySecret) =>
                                      key.withingQuotas().flatMap {
                                        case true => callDownstream(config, Some(key))
                                        case false =>
                                          Errors.craftResponseResult("You performed too much requests",
                                                                     TooManyRequests,
                                                                     req,
                                                                     Some(descriptor),
                                                                     Some("errors.too.much.requests"))
                                      }
                                  }
                              }
                              case _ =>
                                Errors.craftResponseResult("No ApiKey provided",
                                                           BadRequest,
                                                           req,
                                                           Some(descriptor),
                                                           Some("errors.no.api.key"))
                            }
                          } else {
                            Errors.craftResponseResult("No ApiKey provided",
                                                       BadRequest,
                                                       req,
                                                       Some(descriptor),
                                                       Some("errors.no.api.key"))
                          }
                        }

                        def passWithAuth0(config: GlobalConfig): Future[Result] =
                          isPrivateAppsSessionValid(req).flatMap {
                            case Some(paUsr) => callDownstream(config, paUsr = Some(paUsr))
                            case None => {
                              val redirectTo = env.rootScheme + env.privateAppsHost + controllers.routes.Auth0Controller
                                .privateAppsLoginPage(Some(s"http://${req.host}${req.uri}"))
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

                        globalConfig.withinThrottlingQuota().fast.map(within => (globalConfig, within)).flatMap {
                          tuple =>
                            val (globalConfig, within) = tuple

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

                            // Very very very consuming but eh !!!
                            env.datastores.globalConfigDataStore.incrementCallsForIpAddressWithTTL(from).fast.flatMap {
                              secCalls =>
                                env.datastores.globalConfigDataStore.quotaForIpAddress(from).fast.map { maybeQuota =>
                                  (secCalls, maybeQuota)
                                }
                            } flatMap { r =>
                              val (secCalls, maybeQuota) = r
                              val quota                  = maybeQuota.getOrElse(globalConfig.perIpThrottlingQuota)
                              if (secCalls > (quota * 10L)) {
                                Errors.craftResponseResult("[IP] You performed too much requests",
                                                           TooManyRequests,
                                                           req,
                                                           Some(descriptor),
                                                           Some("errors.too.much.requests"))
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
                                    s"redirects prod service from ${protocol}://$theDomain${req.uri} to https://$theDomain${req.uri}"
                                  )
                                  FastFuture.successful(Redirect(s"${env.rootScheme}$theDomain${req.uri}"))
                                } else if (!within) {
                                  // TODO : count as served req here !!!
                                  Errors.craftResponseResult("[GLOBAL] You performed too much requests",
                                                             TooManyRequests,
                                                             req,
                                                             Some(descriptor),
                                                             Some("errors.too.much.requests"))
                                } else if (globalConfig.ipFiltering.whitelist.nonEmpty && !globalConfig.ipFiltering.whitelist
                                             .exists(ip => utils.RegexPool(ip).matches(remoteAddress))) {
                                  Errors.craftResponseResult("Your IP address is not allowed",
                                                             Forbidden,
                                                             req,
                                                             Some(descriptor),
                                                             Some("errors.ip.address.not.allowed")) // global whitelist
                                } else if (globalConfig.ipFiltering.blacklist.nonEmpty && globalConfig.ipFiltering.blacklist
                                             .exists(ip => utils.RegexPool(ip).matches(remoteAddress))) {
                                  Errors.craftResponseResult("Your IP address is not allowed",
                                                             Forbidden,
                                                             req,
                                                             Some(descriptor),
                                                             Some("errors.ip.address.not.allowed")) // global blacklist
                                } else if (descriptor.ipFiltering.whitelist.nonEmpty && !descriptor.ipFiltering.whitelist
                                             .exists(ip => utils.RegexPool(ip).matches(remoteAddress))) {
                                  Errors.craftResponseResult("Your IP address is not allowed",
                                                             Forbidden,
                                                             req,
                                                             Some(descriptor),
                                                             Some("errors.ip.address.not.allowed")) // service whitelist
                                } else if (descriptor.ipFiltering.blacklist.nonEmpty && descriptor.ipFiltering.blacklist
                                             .exists(ip => utils.RegexPool(ip).matches(remoteAddress))) {
                                  Errors.craftResponseResult("Your IP address is not allowed",
                                                             Forbidden,
                                                             req,
                                                             Some(descriptor),
                                                             Some("errors.ip.address.not.allowed")) // service blacklist
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
                                            .limit(expected), // 128 Go of zeros or middle fingers
                                          None,
                                          Some("application/octet-stream")
                                        )
                                      )
                                  )
                                } else if (descriptor.maintenanceMode) {
                                  Errors.craftResponseResult("Service in maintenance mode",
                                                             ServiceUnavailable,
                                                             req,
                                                             Some(descriptor),
                                                             Some("errors.service.in.maintenance"))
                                } else if (descriptor.buildMode) {
                                  Errors.craftResponseResult("Service under construction",
                                                             ServiceUnavailable,
                                                             req,
                                                             Some(descriptor),
                                                             Some("errors.service.under.construction"))
                                } else if (isUp) {
                                  if (descriptor.isPrivate) {
                                    if (descriptor.isUriPublic(req.uri)) {
                                      passWithAuth0(globalConfig)
                                    } else {
                                      isPrivateAppsSessionValid(req).fast.flatMap {
                                        case Some(user) => passWithAuth0(globalConfig)
                                        case None       => passWithApiKey(globalConfig)
                                      }
                                    }
                                  } else {
                                    if (descriptor.isUriPublic(req.uri)) {
                                      callDownstream(globalConfig)
                                    } else {
                                      passWithApiKey(globalConfig)
                                    }
                                  }
                                } else {
                                  // fail fast
                                  Errors.craftResponseResult("The service seems to be down :( come back later",
                                                             Forbidden,
                                                             req,
                                                             Some(descriptor),
                                                             Some("errors.service.down"))
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
        env.datastores.globalConfigDataStore
          .singleton()
          .fast
          .map(
            config =>
              env.statsd.meter(s"${env.snowflakeSeed}.concurrent-requests", requests.toDouble)(config.statsdConfig)
          )
    }
  }

  def decodeBase64(encoded: String): String = new String(OpunClaim.decoder.decode(encoded), Charsets.UTF_8)
}
