package gateway

import java.net.{InetAddress, InetSocketAddress}
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong, AtomicReference}

import akka.NotUsed
import akka.actor.{Actor, ActorRef, PoisonPill, Props}
import akka.http.scaladsl.ClientTransport
import akka.http.scaladsl.model.{HttpHeader, Uri}
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.ws.{InvalidUpgradeResponse, ValidUpgrade, WebSocketRequest}
import akka.http.scaladsl.settings.ClientConnectionSettings
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{Flow, Keep, Sink, Source, SourceQueueWithComplete, Tcp}
import akka.stream.{FlowShape, Materializer, OverflowStrategy}
import akka.util.ByteString
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.google.common.base.Charsets
import env.{Env, SidecarConfig}
import events._
import models._
import org.joda.time.DateTime
import otoroshi.el.{HeadersExpressionLanguage, TargetExpressionLanguage}
import otoroshi.script.Implicits._
import otoroshi.script.TransformerRequestContext
import play.api.Logger
import play.api.http.HttpEntity
import play.api.http.websocket.{CloseMessage, PingMessage, PongMessage, BinaryMessage => PlayWSBinaryMessage, Message => PlayWSMessage, TextMessage => PlayWSTextMessage}
import play.api.libs.json.{JsValue, Json}
import play.api.libs.streams.ActorFlow
import play.api.libs.ws.DefaultWSCookie
import play.api.mvc.Results.{BadGateway, GatewayTimeout, MethodNotAllowed, NotFound, ServiceUnavailable, Status, TooManyRequests, Unauthorized}
import play.api.mvc._
import security.{IdGenerator, OtoroshiClaim}
import utils.RequestImplicits._
import utils.future.Implicits._
import utils.http.WSProxyServerUtils
import utils._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

class WebSocketHandler()(implicit env: Env) {

  type WSFlow = Flow[PlayWSMessage, PlayWSMessage, _]

  implicit lazy val currentEc           = env.otoroshiExecutionContext
  implicit lazy val currentScheduler    = env.otoroshiScheduler
  implicit lazy val currentSystem       = env.otoroshiActorSystem
  implicit lazy val currentMaterializer = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-websocket-handler")

  // lazy val http = akka.http.scaladsl.Http.get(env.otoroshiActorSystem)

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

  def decodeBase64(encoded: String): String = new String(OtoroshiClaim.decoder.decode(encoded), Charsets.UTF_8)

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

  def isPrivateAppsSessionValid(req: RequestHeader, desc: ServiceDescriptor): Future[Option[PrivateAppsUser]] = {
    desc.authConfigRef match {
      case Some(ref) => env.datastores.authConfigsDataStore.findById(ref).flatMap {
        case None => FastFuture.successful(None)
        case Some(auth) => {
          val expected = "oto-papps-" + auth.cookieSuffix(desc)
          req.cookies
            .get(expected)
            .flatMap { cookie =>
              env.extractPrivateSessionId(cookie)
            }
            .orElse(
              req.getQueryString("pappsToken").flatMap(value => env.extractPrivateSessionIdFromString(value))
            )
            .orElse(
              req.headers.get("Otoroshi-Token").flatMap(value => env.extractPrivateSessionIdFromString(value))
            )
            .map { id =>
              env.datastores.privateAppsUserDataStore.findById(id)
            } getOrElse {
            FastFuture.successful(None)
          }
        }
      }
      case None => FastFuture.successful(None)
    }
  }

  def splitToCanary(desc: ServiceDescriptor, trackingId: String, reqNumber: Int, config: GlobalConfig)(
      implicit env: Env
  ): Future[ServiceDescriptor] = {
    if (desc.canary.enabled) {
      env.datastores.canaryDataStore.isCanary(desc.id, trackingId, desc.canary.traffic, reqNumber, config).map {
        case false => desc
        case true  => desc.copy(targets = desc.canary.targets, root = desc.canary.root)
      }
    } else {
      FastFuture.successful(desc)
    }
  }

  def applyJwtVerifier(service: ServiceDescriptor,
                       req: RequestHeader,
                       apiKey: Option[ApiKey],
                       paUsr: Option[PrivateAppsUser],
                       elContext: Map[String, String],
                       attrs: TypedMap)(
      f: JwtInjection => Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]]
  )(implicit env: Env): Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]] = {
    if (service.jwtVerifier.enabled) {
      service.jwtVerifier.shouldBeVerified(req.path).flatMap {
        case false => f(JwtInjection())
        case true => {
          logger.debug(s"Applying JWT verification for service ${service.id}:${service.name}")
          service.jwtVerifier.verifyWs(req, service, apiKey, paUsr, elContext, attrs)(f)
        }
      }
    } else {
      f(JwtInjection())
    }
  }

  def applySidecar(service: ServiceDescriptor, remoteAddress: String, req: RequestHeader, attrs: TypedMap)(
      f: ServiceDescriptor => Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]]
  )(implicit env: Env): Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]] = {
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
        Errors
          .craftResponseResult(
            "sidecar.bad.request.origin",
            Results.BadGateway,
            req,
            Some(service),
            None,
            attrs = attrs
          )
          .asLeft[WSFlow]
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
            Errors
              .craftResponseResult(
                "sidecar.bad.apikey.clientid",
                Results.InternalServerError,
                req,
                Some(service),
                None,
                attrs = attrs
              )
              .asLeft[WSFlow]
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

  def passWithTcpUdpTunneling(req: RequestHeader, desc: ServiceDescriptor, attrs: TypedMap)(
      f: => Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]]
  ): Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]] = {
    import utils.RequestImplicits._

    if (desc.tcpUdpTunneling) {
      if (req.relativeUri.startsWith("/.well-known/otoroshi/tunnel")) {
        f
      } else {
        Errors
          .craftResponseResult(s"Resource not found", NotFound, req, None, Some("errors.resource.not.found"),
            attrs = attrs)
          .asLeft[WSFlow]
      }
    } else {
      f
    }
  }

  def passWithHeadersVerification(desc: ServiceDescriptor, req: RequestHeader, attrs: TypedMap)(
      f: => Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]]
  ): Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]] = {
    if (desc.headersVerification.isEmpty) {
      f
    } else {
      desc.headersVerification.map(tuple => req.headers.get(tuple._1).exists(_ == tuple._2)).find(_ == false) match {
        case Some(_) =>
          Errors
            .craftResponseResult(
              "Missing header(s)",
              Results.BadRequest,
              req,
              Some(desc),
              Some("errors.missing.headers"),
              attrs = attrs
            )
            .asLeft[WSFlow]
        case None => f
      }
    }
  }

  def passWithReadOnly(readOnly: Boolean, req: RequestHeader, attrs: TypedMap)(
      f: => Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]]
  ): Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]] = {
    readOnly match {
      case false => f
      case true =>
        req.method.toLowerCase match {
          case "get"     => f
          case "head"    => f
          case "options" => f
          case _ =>
            Errors
              .craftResponseResult(
                s"Method not allowed. Can only handle GET, HEAD, OPTIONS",
                MethodNotAllowed,
                req,
                None,
                Some("errors.method.not.allowed"),
                attrs = attrs
              )
              .asLeft[WSFlow]
        }
    }
  }

  @inline
  def getWsProtocolFor(req: RequestHeader): String = {
    req.headers
      .get("X-Forwarded-Proto")
      .orElse(req.headers.get("X-Forwarded-Protocol"))
      .map(_ == "https")
      .orElse(Some(req.secure))
      .map {
        case true  => "wss"
        case false => "ws"
      }
      .getOrElse("ws")
  }

  @inline
  def getSecuredFor(req: RequestHeader): Boolean = {
    getWsProtocolFor(req) match {
      case "ws"  => false
      case "wss" => true
    }
  }

  def proxyWebSocket() = WebSocket.acceptOrResult[PlayWSMessage, PlayWSMessage] { req =>
    logger.trace("[WEBSOCKET] proxy ws call !!!")

    // val meterIn       = Metrics.metrics.meter("GatewayDataIn")
    val snowflake        = env.snowflakeGenerator.nextIdStr()
    val calledAt         = DateTime.now()
    val requestTimestamp = calledAt.toString("yyyy-MM-dd'T'HH:mm:ss.SSSZZ")
    val reqNumber        = reqCounter.incrementAndGet()
    val remoteAddress    = req.headers.get("X-Forwarded-For").getOrElse(req.remoteAddress)
    val isSecured        = getSecuredFor(req)
    val protocol         = getWsProtocolFor(req)
    val from             = req.headers.get("X-Forwarded-For").getOrElse(req.remoteAddress)
    val counterIn        = new AtomicLong(0L)
    val counterOut       = new AtomicLong(0L)
    val start            = System.currentTimeMillis()
    val attrs            = utils.TypedMap.empty.put(
      otoroshi.plugins.Keys.SnowFlakeKey -> snowflake,
      otoroshi.plugins.Keys.RequestTimestampKey -> calledAt,
      otoroshi.plugins.Keys.RequestStartKey -> start,
      otoroshi.plugins.Keys.RequestWebsocketKey -> true
    )

    val elCtx: Map[String, String] = Map(
      "requestId"        -> snowflake,
      "requestSnowflake" -> snowflake,
      "requestTimestamp" -> requestTimestamp
    )

    val finalResult = env.datastores.globalConfigDataStore.singleton().flatMap { globalConfig =>
      ServiceLocation(req.host, globalConfig) match {
        case None =>
          Errors
            .craftResponseResult(s"Service not found for URL ${req.host}::${req.relativeUri}",
                                 Results.NotFound,
                                 req,
                                 None,
                                 Some("errors.service.not.found"),
              attrs = attrs)
            .asLeft[WSFlow]
        case Some(ServiceLocation(domain, serviceEnv, subdomain)) => {
          val uriParts = req.relativeUri.split("/").toSeq

          env.datastores.serviceDescriptorDataStore
            .find(ServiceDescriptorQuery(subdomain, serviceEnv, domain, req.relativeUri, req.headers.toSimpleMap), req)
            .flatMap {
              case None =>
                Errors
                  .craftResponseResult(s"Service not found",
                                       Results.NotFound,
                                       req,
                                       None,
                                       Some("errors.service.not.found"),
                    attrs = attrs)
                  .asLeft[WSFlow]
              case Some(desc) if !desc.enabled =>
                Errors
                  .craftResponseResult(s"Service not found",
                                       Results.NotFound,
                                       req,
                                       None,
                                       Some("errors.service.not.found"),
                    attrs = attrs)
                  .asLeft[WSFlow]
              case Some(rawDesc) if rawDesc.redirection.enabled && rawDesc.redirection.hasValidCode => {
                FastFuture
                  .successful(
                    Results
                      .Status(rawDesc.redirection.code)
                      .withHeaders("Location" -> rawDesc.redirection.formattedTo(req, rawDesc, elCtx, attrs))
                  )
                  .asLeft[WSFlow]
              }
              case Some(rawDesc) =>
                rawDesc.preRouteWS(snowflake, req, attrs) {
                  passWithTcpUdpTunneling(req, rawDesc, attrs) {
                  passWithHeadersVerification(rawDesc, req, attrs) {
                    passWithReadOnly(rawDesc.readOnly, req, attrs) {
                      applySidecar(rawDesc, remoteAddress, req, attrs) { desc =>
                        val maybeCanaryId = req.cookies
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
                          logger.debug(s"request already has tracking id : $canaryId")
                        } else {
                          logger.debug(s"request has a new tracking id : $canaryId")
                        }

                        // val withTrackingCookies: Seq[Cookie] = {
                        //   if (!desc.canary.enabled)
                        //     jwtInjection.additionalCookies
                        //       .map(t => Cookie(t._1, t._2))
                        //       .toSeq //Seq.empty[play.api.mvc.Cookie]
                        //   else if (maybeCanaryId.isDefined)
                        //     jwtInjection.additionalCookies
                        //       .map(t => Cookie(t._1, t._2))
                        //       .toSeq //Seq.empty[play.api.mvc.Cookie]
                        //   else
                        //     Seq(
                        //       play.api.mvc.Cookie(
                        //         name = "otoroshi-canary",
                        //         value = s"${env.sign(canaryId)}::$canaryId",
                        //         maxAge = Some(2592000),
                        //         path = "/",
                        //         domain = Some(req.host),
                        //         httpOnly = false
                        //       )
                        //     ) ++ jwtInjection.additionalCookies.map(t => Cookie(t._1, t._2))
                        // } ++ (if (desc.targetsLoadBalancing.needTrackingCookie) {
                        //         Seq(
                        //           play.api.mvc.Cookie(
                        //             name = "otoroshi-tracking",
                        //             value = trackingId,
                        //             maxAge = Some(2592000),
                        //             path = "/",
                        //             domain = Some(req.domain),
                        //             httpOnly = false
                        //           )
                        //         )
                        //       } else {
                        //         Seq.empty[Cookie]
                        //       })

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
                                domain = Some(req.domain),
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
                                    domain = Some(req.domain),
                                    httpOnly = false
                                  )
                                )
                              } else {
                                Seq.empty[Cookie]
                              })

                        desc.isUp
                          .flatMap(iu => splitToCanary(desc, canaryId, reqNumber, globalConfig).map(d => (iu, d)))
                          .flatMap { tuple =>
                            val (isUp, _desc) = tuple
                            val descriptor    = _desc

                            def callDownstream(
                                config: GlobalConfig,
                                _apiKey: Option[ApiKey] = None,
                                _paUsr: Option[PrivateAppsUser] = None
                            ): Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]] = {

                              val apiKey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey).orElse(_apiKey)
                              val paUsr = attrs.get(otoroshi.plugins.Keys.UserKey).orElse(_paUsr)
                              apiKey.foreach(apk => attrs.put(otoroshi.plugins.Keys.ApiKeyKey -> apk))
                              paUsr.foreach(usr => attrs.putIfAbsent(otoroshi.plugins.Keys.UserKey -> usr))

                              desc.wsValidateClientCertificates(snowflake, req, apiKey, paUsr, config, attrs) {
                                passWithReadOnly(apiKey.map(_.readOnly).getOrElse(false), req, attrs) {
                                  if (config.useCircuitBreakers && descriptor.clientConfig.useCircuitBreaker) {
                                    val cbStart = System.currentTimeMillis()
                                    val counter = new AtomicInteger(0)
                                    val relUri  = req.relativeUri
                                    val cachedPath: String =
                                      descriptor.clientConfig.timeouts(relUri).map(_ => relUri).getOrElse("")
                                    env.circuitBeakersHolder
                                      .get(descriptor.id + cachedPath, () => new ServiceDescriptorCircuitBreaker())
                                      .callWS(
                                        descriptor,
                                        reqNumber.toString,
                                        trackingId,
                                        req.relativeUri,
                                        req,
                                        s"WS ${req.method} ${req.relativeUri}",
                                        counter,
                                        (t, attempts) =>
                                          actuallyCallDownstream(t,
                                                                 apiKey,
                                                                 paUsr,
                                                                 System.currentTimeMillis - cbStart,
                                                                 attempts)
                                      ) recoverWith {
                                      case _: scala.concurrent.TimeoutException =>
                                        Errors
                                          .craftResponseResult(
                                            s"Something went wrong, the downstream service does not respond quickly enough, you should try later. Thanks for your understanding",
                                            GatewayTimeout,
                                            req,
                                            Some(descriptor),
                                            Some("errors.request.timeout"),
                                            attrs = attrs
                                          )
                                          .asLeft[WSFlow]
                                      case RequestTimeoutException =>
                                        Errors
                                          .craftResponseResult(
                                            s"Something went wrong, the downstream service does not respond quickly enough, you should try later. Thanks for your understanding",
                                            GatewayTimeout,
                                            req,
                                            Some(descriptor),
                                            Some("errors.request.timeout"),
                                            attrs = attrs
                                          )
                                          .asLeft[WSFlow]
                                      case AllCircuitBreakersOpenException =>
                                        Errors
                                          .craftResponseResult(
                                            s"Something went wrong, the downstream service seems a little bit overwhelmed, you should try later. Thanks for your understanding",
                                            ServiceUnavailable,
                                            req,
                                            Some(descriptor),
                                            Some("errors.circuit.breaker.open"),
                                            attrs = attrs
                                          )
                                          .asLeft[WSFlow]
                                      case error if error.getMessage.toLowerCase().contains("connection refused") =>
                                        Errors
                                          .craftResponseResult(
                                            s"Something went wrong, the connection to downstream service was refused, you should try later. Thanks for your understanding",
                                            BadGateway,
                                            req,
                                            Some(descriptor),
                                            Some("errors.connection.refused"),
                                            attrs = attrs
                                          )
                                          .asLeft[WSFlow]
                                      case error =>
                                        Errors
                                          .craftResponseResult(
                                            s"Something went wrong, you should try later. Thanks for your understanding. ${error.getMessage}",
                                            BadGateway,
                                            req,
                                            Some(descriptor),
                                            Some("errors.proxy.error"),
                                            attrs = attrs
                                          )
                                          .asLeft[WSFlow]
                                    }
                                  } else {
                                    val targets: Seq[Target] = descriptor.targets
                                      .filter(_.predicate.matches(reqNumber.toString))
                                      .flatMap(t => Seq.fill(t.weight)(t))
                                    //val index = reqCounter.get() % (if (targets.nonEmpty)
                                    //                                  targets.size
                                    //                                else 1)
                                    // Round robin loadbalancing is happening here !!!!!
                                    //val target = targets.apply(index.toInt)
                                    val target = descriptor.targetsLoadBalancing
                                      .select(reqNumber.toString, trackingId, req, targets, descriptor)
                                    actuallyCallDownstream(target, apiKey, paUsr, 0, 1)
                                  }
                                }
                              }
                            }

                            def actuallyCallDownstream(
                                _target: Target,
                                apiKey: Option[ApiKey] = None,
                                paUsr: Option[PrivateAppsUser] = None,
                                cbDuration: Long,
                                callAttempts: Int
                            ): Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]] =
                              applyJwtVerifier(rawDesc, req, apiKey, paUsr, elCtx, attrs) { jwtInjection =>
                                logger.trace("[WEBSOCKET] Call downstream !!!")
                                val stateValue = IdGenerator.extendedToken(128)
                                val stateToken: String = descriptor.secComVersion match {
                                  case SecComVersion.V1 => stateValue
                                  case SecComVersion.V2 =>
                                    OtoroshiClaim(
                                      iss = env.Headers.OtoroshiIssuer,
                                      sub = env.Headers.OtoroshiIssuer,
                                      aud = descriptor.name,
                                      exp = DateTime.now().plusSeconds(descriptor.secComTtl.toSeconds.toInt).toDate.getTime,
                                      iat = DateTime.now().toDate.getTime,
                                      jti = IdGenerator.uuid
                                    ).withClaim("state", stateValue).serialize(descriptor.secComSettings)
                                }
                                val rawUri   = req.relativeUri.substring(1)
                                val uriParts = rawUri.split("/").toSeq
                                val uri: String =
                                        descriptor.matchingRoot
                                          .filter(_ => descriptor.stripPath)
                                          .map(m => req.relativeUri.replace(m, ""))
                                          .getOrElse(rawUri)
                                // val index = reqCounter.incrementAndGet() % (if (descriptor.targets.nonEmpty) descriptor.targets.size else 1)
                                // // Round robin loadbalancing is happening here !!!!!
                                // val target = descriptor.targets.apply(index.toInt)
                                val scheme = if (descriptor.redirectToLocal) descriptor.localScheme else _target.scheme
                                val host   = if (descriptor.redirectToLocal) descriptor.localHost else _target.host
                                val root   = descriptor.root
                                val url = TargetExpressionLanguage(
                                  s"${if (_target.scheme == "https") "wss" else "ws"}://$host$root$uri",
                                  Some(req),
                                  Some(descriptor),
                                  apiKey,
                                  paUsr,
                                  elCtx,
                                  attrs
                                )
                                // val queryString = req.queryString.toSeq.flatMap { case (key, values) => values.map(v => (key, v)) }
                                val fromOtoroshi = req.headers
                                  .get(env.Headers.OtoroshiRequestId)
                                  .orElse(req.headers.get(env.Headers.OtoroshiGatewayParentRequest))
                                val promise = Promise[ProxyDone]

                                val claim = descriptor.generateInfoToken(apiKey, paUsr, Some(req))
                                logger.trace(s"Claim is : $claim")
                                //val stateRequestHeaderName =
                                //  descriptor.secComHeaders.stateRequestName.getOrElse(env.Headers.OtoroshiState)
                                val stateResponseHeaderName =
                                  descriptor.secComHeaders.stateResponseName.getOrElse(env.Headers.OtoroshiStateResp)

                                val headersIn: Seq[(String, String)] = HeadersHelper.composeHeadersIn(
                                  descriptor = descriptor,
                                  req = req,
                                  apiKey = apiKey,
                                  paUsr = paUsr,
                                  elCtx = elCtx,
                                  currentReqHasBody = false,
                                  headersInFiltered = headersInFiltered,
                                  snowflake = snowflake,
                                  requestTimestamp = requestTimestamp,
                                  host = host,
                                  claim = claim,
                                  stateToken = stateToken,
                                  fromOtoroshi = fromOtoroshi,
                                  snowMonkeyContext = SnowMonkeyContext(
                                    Source.empty[ByteString],
                                    Source.empty[ByteString]
                                  ),
                                  jwtInjection = jwtInjection,
                                  attrs = attrs
                                )
                                //val claimRequestHeaderName =
                                //  descriptor.secComHeaders.claimRequestName.getOrElse(env.Headers.OtoroshiClaim)
                                // val headersIn: Seq[(String, String)] =
                                // (req.headers.toMap.toSeq
                                //   .flatMap(c => c._2.map(v => (c._1, v))) //.map(tuple => (tuple._1, tuple._2.mkString(","))) //.toSimpleMap
                                //   .filterNot(t => descriptor.removeHeadersIn.contains(t._1))
                                //   .filterNot(
                                //     t =>
                                //       (headersInFiltered ++ Seq(claimRequestHeaderName, stateRequestHeaderName))
                                //         .contains(t._1.toLowerCase)
                                //   ) ++ Map(
                                //   env.Headers.OtoroshiProxiedHost -> req.headers.get("Host").getOrElse("--"),
                                //   // "Host"                               -> host,
                                //   "Host"                               -> (if (desc.overrideHost) host else req.headers.get("Host").getOrElse("--")),
                                //   env.Headers.OtoroshiRequestId        -> snowflake,
                                //   env.Headers.OtoroshiRequestTimestamp -> requestTimestamp
                                // ) ++ (if (descriptor.enforceSecureCommunication && descriptor.sendInfoToken) {
                                //         Map(
                                //           claimRequestHeaderName -> claim
                                //         )
                                //       } else {
                                //         Map.empty[String, String]
                                //       }) ++ (if (descriptor.enforceSecureCommunication && descriptor.sendStateChallenge) {
                                //                Map(
                                //                  stateRequestHeaderName -> stateToken
                                //                )
                                //              } else {
                                //                Map.empty[String, String]
                                //              }) ++ (req.clientCertificateChain match {
                                //   case Some(chain) =>
                                //     Map(env.Headers.OtoroshiClientCertChain -> req.clientCertChainPemString)
                                //   case None => Map.empty[String, String]
                                // }) ++ descriptor.additionalHeaders
                                //   .filter(t => t._1.trim.nonEmpty)
                                //   .mapValues(v => HeadersExpressionLanguage.apply(v, Some(req), Some(descriptor), apiKey, paUsr, elCtx)).filterNot(h => h._2 == "null") ++ fromOtoroshi
                                //   .map(v => Map(env.Headers.OtoroshiGatewayParentRequest -> fromOtoroshi.get))
                                //   .getOrElse(Map.empty[String, String]) ++ jwtInjection.additionalHeaders).toSeq
                                //   .filterNot(t => jwtInjection.removeHeaders.contains(t._1)) ++ xForwardedHeader(desc,
                                //                                                                                  req)

                                // val requestHeader = ByteString(
                                //   req.method + " " + req.relativeUri + " HTTP/1.1\n" + headersIn
                                //     .map(h => s"${h._1}: ${h._2}")
                                //     .mkString("\n") + "\n"
                                // )
                                // meterIn.mark(requestHeader.length)
                                // counterIn.addAndGet(requestHeader.length)
                                // logger.trace(s"curl -X ${req.method.toUpperCase()} ${headersIn.map(h => s"-H '${h._1}: ${h._2}'").mkString(" ")} '$url?${queryString.map(h => s"${h._1}=${h._2}").mkString("&")}' --include")
                                logger.trace(
                                  s"[WEBSOCKET] calling '$url' with headers \n ${headersIn.map(_.toString()) mkString ("\n")}"
                                )
                                val overhead = System.currentTimeMillis() - start
                                val quotas: Future[RemainingQuotas] =
                                  apiKey.map(_.updateQuotas()).getOrElse(FastFuture.successful(RemainingQuotas()))
                                promise.future.andThen {
                                  case Success(resp) => {
                                    val duration = System.currentTimeMillis() - start
                                    // logger.trace(s"[$snowflake] Call forwarded in $duration ms. with $overhead ms overhead for (${req.version}, http://${req.host}${req.relativeUri} => $url, $from)")
                                    descriptor
                                      .updateMetrics(duration,
                                                     overhead,
                                                     counterIn.get(),
                                                     counterOut.get(),
                                                     0,
                                                     globalConfig)
                                      .andThen {
                                        case Failure(e) =>
                                          logger.error("Error while updating call metrics reporting", e)
                                      }
                                    env.datastores.globalConfigDataStore.updateQuotas(globalConfig)
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
                                          `@calledAt` = calledAt,
                                          protocol = req.version,
                                          to = Location(
                                            scheme = getWsProtocolFor(req),
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
                                          responseChunked = false,
                                          `@serviceId` = descriptor.id,
                                          `@service` = descriptor.name,
                                          descriptor = Some(descriptor),
                                          `@product` = descriptor.metadata.getOrElse("product", "--"),
                                          remainingQuotas = q,
                                          viz = Some(viz),
                                          clientCertChain = req.clientCertChainPem,
                                          err = false,
                                          userAgentInfo = attrs.get[JsValue](otoroshi.plugins.Keys.UserAgentInfoKey),
                                          geolocationInfo = attrs.get[JsValue](otoroshi.plugins.Keys.GeolocationInfoKey)

                                        )
                                        evt.toAnalytics()
                                        if (descriptor.logAnalyticsOnServer) {
                                          evt.log()(env, env.analyticsExecutionContext) // pressure EC
                                        }
                                      }
                                    }(env.analyticsExecutionContext) // pressure EC
                                  }
                                }(env.analyticsExecutionContext) // pressure EC

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
                                val upstreamStart = System.currentTimeMillis()
                                descriptor
                                  .transformRequest(
                                    TransformerRequestContext(
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
                                  )
                                  .flatMap {
                                    case Left(badResult) => {
                                      quotas
                                        .map { remainingQuotas =>
                                          val _headersOut: Seq[(String, String)] = badResult.header.headers.toSeq
                                            .filterNot(t => descriptor.removeHeadersOut.contains(t._1))
                                            .filterNot(
                                              t =>
                                                (headersOutFiltered :+ stateResponseHeaderName)
                                                  .contains(t._1.toLowerCase)
                                            ) ++ (
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
                                                  }) ++ descriptor.cors.asHeaders(req) ++ desc.additionalHeadersOut
                                            .mapValues(
                                              v =>
                                                HeadersExpressionLanguage
                                                  .apply(v, Some(req), Some(descriptor), apiKey, paUsr, elCtx, attrs)
                                            )
                                            .filterNot(h => h._2 == "null")
                                            .toSeq
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
                                        .asLeft[WSFlow]
                                    }
                                    case Right(_)
                                        if descriptor.tcpUdpTunneling && !req.relativeUri
                                          .startsWith("/.well-known/otoroshi/tunnel") => {
                                      Errors
                                        .craftResponseResult(s"Resource not found",
                                                             NotFound,
                                                             req,
                                                             None,
                                                             Some("errors.resource.not.found"),
                                          attrs = attrs)
                                        .asLeft[WSFlow]
                                    }
                                    case Right(_httpReq)
                                        if descriptor.tcpUdpTunneling && req.relativeUri
                                          .startsWith("/.well-known/otoroshi/tunnel") => {
                                      val target = _httpReq.target.getOrElse(_target)
                                      val (theHost: String, thePort: Int) = (target.scheme,
                                                                             TargetExpressionLanguage(target.host,
                                                                                                      Some(req),
                                                                                                      Some(descriptor),
                                                                                                      apiKey,
                                                                                                      paUsr,
                                                                                                      elCtx, attrs)) match {
                                        case (_, host) if host.contains(":") =>
                                          (host.split(":").apply(0), host.split(":").apply(1).toInt)
                                        case (scheme, host) if scheme.contains("https") => (host, 443)
                                        case (_, host)                                  => (host, 80)
                                      }
                                      val remoteAddress = target.ipAddress match {
                                        case Some(ip) =>
                                          new InetSocketAddress(
                                            InetAddress.getByAddress(theHost, InetAddress.getByName(ip).getAddress),
                                            thePort
                                          )
                                        case None => new InetSocketAddress(theHost, thePort)
                                      }
                                      req.getQueryString("transport").map(_.toLowerCase()).getOrElse("tcp") match {
                                        case "tcp" => {
                                          val flow: Flow[PlayWSMessage, PlayWSMessage, _] =
                                            Flow[PlayWSMessage]
                                              .collect {
                                                case PlayWSBinaryMessage(data) =>
                                                  data
                                                case _ =>
                                                  ByteString.empty
                                              }
                                              .via(
                                                Tcp()
                                                  .outgoingConnection(
                                                    remoteAddress = remoteAddress,
                                                    connectTimeout = descriptor.clientConfig.connectionTimeout.millis,
                                                    idleTimeout = descriptor.clientConfig.idleTimeout.millis
                                                  )
                                                  .map(bs => PlayWSBinaryMessage(bs))
                                              )
                                              .alsoTo(Sink.onComplete {
                                                case _ =>
                                                  promise.trySuccess(
                                                    ProxyDone(
                                                      200,
                                                      false,
                                                      0,
                                                      Seq.empty[Header]
                                                    )
                                                  )
                                              })
                                          FastFuture.successful(Right(flow))
                                        }
                                        case "udp-old" => {
                                          val flow: Flow[PlayWSMessage, PlayWSMessage, _] =
                                            Flow[PlayWSMessage]
                                              .collect {
                                                case PlayWSBinaryMessage(data) =>
                                                  utils.Datagram(data, remoteAddress)
                                                case _ =>
                                                  utils.Datagram(ByteString.empty, remoteAddress)
                                              }
                                              .via(
                                                UdpClient
                                                  .flow(new InetSocketAddress("0.0.0.0", 0))
                                                  .map(dg => PlayWSBinaryMessage(dg.data))
                                              )
                                              .alsoTo(Sink.onComplete {
                                                case _ =>
                                                  promise.trySuccess(
                                                    ProxyDone(
                                                      200,
                                                      false,
                                                      0,
                                                      Seq.empty[Header]
                                                    )
                                                  )
                                              })
                                          FastFuture.successful(Right(flow))
                                        }
                                        case "udp" => {

                                          import akka.stream.scaladsl.{ UnzipWith, ZipWith, Balance, Flow, GraphDSL, Merge, Source }
                                          import GraphDSL.Implicits._

                                          val base64decoder = java.util.Base64.getDecoder
                                          val base64encoder = java.util.Base64.getEncoder

                                          val fromJson: Flow[PlayWSMessage, (Int, String, Datagram), NotUsed] = Flow[PlayWSMessage].collect {
                                            case PlayWSBinaryMessage(data) =>
                                              val json = Json.parse(data.utf8String)
                                              val port: Int = (json \ "port").as[Int]
                                              val address: String = (json \ "address").as[String]
                                              val _data: ByteString = (json \ "data").asOpt[String].map(str => ByteString(base64decoder.decode(str))).getOrElse(ByteString.empty)
                                              (port, address, utils.Datagram(_data, remoteAddress))
                                            case _ =>
                                              (0, "localhost", utils.Datagram(ByteString.empty, remoteAddress))
                                          }

                                          val updFlow: Flow[Datagram, Datagram, Future[InetSocketAddress]] = UdpClient
                                            .flow(new InetSocketAddress("0.0.0.0", 0))

                                          def nothing[T]: Flow[T, T, NotUsed] = Flow[T].map(identity)

                                          val flow: Flow[PlayWSMessage, PlayWSBinaryMessage, NotUsed] = fromJson via Flow.fromGraph(GraphDSL.create() { implicit builder =>
                                            val dispatch = builder.add(UnzipWith[(Int, String, utils.Datagram), Int, String, utils.Datagram](a => a))
                                            val merge = builder.add(ZipWith[Int, String, utils.Datagram, (Int, String, utils.Datagram)]((a, b, c) => (a, b, c)))
                                            dispatch.out2 ~> updFlow.async ~> merge.in2
                                            dispatch.out1 ~> nothing[String].async ~> merge.in1
                                            dispatch.out0 ~> nothing[Int].async ~> merge.in0
                                            FlowShape(dispatch.in, merge.out)
                                          }).map {
                                            case (port, address, dg) => PlayWSBinaryMessage(ByteString(Json.stringify(Json.obj(
                                              "port" -> port,
                                              "address" -> address,
                                              "data" -> base64encoder.encodeToString(dg.data.toArray)
                                            ))))
                                          }.alsoTo(Sink.onComplete {
                                            case _ =>
                                              promise.trySuccess(
                                                ProxyDone(
                                                  200,
                                                  false,
                                                  0,
                                                  Seq.empty[Header]
                                                )
                                              )
                                          })
                                          FastFuture.successful(Right(flow))
                                        }
                                      }
                                    }
                                    case Right(httpRequest) if !descriptor.tcpUdpTunneling => {
                                      if (descriptor.useNewWSClient) {
                                        FastFuture.successful(Right(WebSocketProxyActor.wsCall(
                                          UrlSanitizer.sanitize(httpRequest.url),
                                          // httpRequest.headers.toSeq, //.filterNot(_._1 == "Cookie"),
                                          HeadersHelper.addClaims(httpRequest.headers, httpRequest.claims, descriptor),
                                          descriptor,
                                          httpRequest.target.getOrElse(_target)
                                        )))
                                      } else {
                                        attrs.put(otoroshi.plugins.Keys.RequestTargetKey -> httpRequest.target.getOrElse(_target))
                                        FastFuture.successful(
                                          Right(
                                            ActorFlow
                                              .actorRef(
                                                out =>
                                                  WebSocketProxyActor.props(UrlSanitizer.sanitize(httpRequest.url),
                                                                            out,
                                                                            httpRequest.headers.toSeq, //.filterNot(_._1 == "Cookie"),
                                                                            descriptor,
                                                                            httpRequest.target.getOrElse(_target),
                                                                            env)
                                              )
                                              .alsoTo(Sink.onComplete {
                                                case _ =>
                                                  promise.trySuccess(
                                                    ProxyDone(
                                                      200,
                                                      false,
                                                      0,
                                                      Seq.empty[Header]
                                                    )
                                                  )
                                              })
                                          )
                                        )
                                      }
                                    }
                                  }
                              }

                            def passWithApiKey(
                                config: GlobalConfig
                            ): Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]] = {
                              if (descriptor.thirdPartyApiKey.enabled) {
                                descriptor.thirdPartyApiKey.handleWS(req, descriptor, config, attrs) { key =>
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
                                        Errors
                                          .craftResponseResult(
                                            "Invalid API key",
                                            Results.Unauthorized,
                                            req,
                                            Some(descriptor),
                                            Some("errors.invalid.api.key"),
                                            attrs = attrs
                                          )
                                          .asLeft[WSFlow]
                                      case Some(key) if !key.allowClientIdOnly => {
                                        Errors
                                          .craftResponseResult(
                                            "Bad API key",
                                            Results.BadRequest,
                                            req,
                                            Some(descriptor),
                                            Some("errors.bad.api.key"),
                                            attrs = attrs
                                          )
                                          .asLeft[WSFlow]
                                      }
                                      case Some(key) if !key.matchRouting(descriptor) => {
                                        Errors
                                          .craftResponseResult(
                                            "Invalid API key",
                                            Unauthorized,
                                            req,
                                            Some(descriptor),
                                            Some("errors.bad.api.key"),
                                            attrs = attrs
                                          )
                                          .asLeft[WSFlow]
                                      }
                                      case Some(key)
                                          if key.restrictions.handleRestrictions(descriptor, Some(key), req, attrs)._1 => {
                                        key.restrictions
                                          .handleRestrictions(descriptor, Some(key), req, attrs)
                                          ._2
                                          .asLeft[WSFlow]
                                      }
                                      case Some(key) if key.allowClientIdOnly =>
                                        key.withingQuotas().flatMap {
                                          case true => callDownstream(config, Some(key))
                                          case false =>
                                            Errors
                                              .craftResponseResult(
                                                "You performed too much requests",
                                                TooManyRequests,
                                                req,
                                                Some(descriptor),
                                                Some("errors.too.much.requests"),
                                                attrs = attrs
                                              )
                                              .asLeft[WSFlow]
                                        }
                                    }
                                } else if (authByCustomHeaders.isDefined && descriptor.apiKeyConstraints.customHeadersAuth.enabled) {
                                  val (clientId, clientSecret) = authByCustomHeaders.get
                                  env.datastores.apiKeyDataStore
                                    .findAuthorizeKeyFor(clientId, descriptor.id)
                                    .flatMap {
                                      case None =>
                                        Errors
                                          .craftResponseResult("Invalid API key",
                                                               Results.Unauthorized,
                                                               req,
                                                               Some(descriptor),
                                                               Some("errors.invalid.api.key"),
                                            attrs = attrs)
                                          .asLeft[WSFlow]
                                      case Some(key) if key.isInvalid(clientSecret) => {
                                        Alerts.send(
                                          RevokedApiKeyUsageAlert(env.snowflakeGenerator.nextIdStr(),
                                                                  DateTime.now(),
                                                                  env.env,
                                                                  req,
                                                                  key,
                                                                  descriptor)
                                        )
                                        Errors
                                          .craftResponseResult("Bad API key",
                                                               Results.BadRequest,
                                                               req,
                                                               Some(descriptor),
                                                               Some("errors.bad.api.key"),
                                            attrs = attrs)
                                          .asLeft[WSFlow]
                                      }
                                      case Some(key) if !key.matchRouting(descriptor) => {
                                        Errors
                                          .craftResponseResult(
                                            "Invalid API key",
                                            Unauthorized,
                                            req,
                                            Some(descriptor),
                                            Some("errors.bad.api.key"),
                                            attrs = attrs
                                          )
                                          .asLeft[WSFlow]
                                      }
                                      case Some(key)
                                          if key.restrictions.handleRestrictions(descriptor, Some(key), req, attrs)._1 => {
                                        key.restrictions
                                          .handleRestrictions(descriptor, Some(key), req, attrs)
                                          ._2
                                          .asLeft[WSFlow]
                                      }
                                      case Some(key) if key.isValid(clientSecret) =>
                                        key.withingQuotas().flatMap {
                                          case true => callDownstream(config, Some(key))
                                          case false =>
                                            Errors
                                              .craftResponseResult("You performed too much requests",
                                                                   Results.TooManyRequests,
                                                                   req,
                                                                   Some(descriptor),
                                                                   Some("errors.too.much.requests"),
                                                attrs = attrs)
                                              .asLeft[WSFlow]
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
                                              val httpPath =
                                                Option(jwt.getClaim("httpPath")).filterNot(_.isNull).map(_.asString())
                                              val httpVerb =
                                                Option(jwt.getClaim("httpVerb")).filterNot(_.isNull).map(_.asString())
                                              val httpHost =
                                                Option(jwt.getClaim("httpHost")).filterNot(_.isNull).map(_.asString())
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
                                                  Errors
                                                    .craftResponseResult(
                                                      "Invalid API key",
                                                      Unauthorized,
                                                      req,
                                                      Some(descriptor),
                                                      Some("errors.bad.api.key"),
                                                      attrs = attrs
                                                    )
                                                    .asLeft[WSFlow]
                                                }
                                                case Success(_)
                                                    if apiKey.restrictions
                                                      .handleRestrictions(descriptor, Some(apiKey), req, attrs)
                                                      ._1 => {
                                                  apiKey.restrictions
                                                    .handleRestrictions(descriptor, Some(apiKey), req, attrs)
                                                    ._2
                                                    .asLeft[WSFlow]
                                                }
                                                case Success(_) =>
                                                  apiKey.withingQuotas().flatMap {
                                                    case true => callDownstream(config, Some(apiKey))
                                                    case false =>
                                                      Errors
                                                        .craftResponseResult("You performed too much requests",
                                                                             Results.TooManyRequests,
                                                                             req,
                                                                             Some(descriptor),
                                                                             Some("errors.too.much.requests"),
                                                          attrs = attrs)
                                                        .asLeft[WSFlow]
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
                                                  Errors
                                                    .craftResponseResult("Bad API key",
                                                                         Results.BadRequest,
                                                                         req,
                                                                         Some(descriptor),
                                                                         Some("errors.bad.api.key"),
                                                      attrs = attrs)
                                                    .asLeft[WSFlow]
                                                }
                                              }
                                            }
                                            case None =>
                                              Errors
                                                .craftResponseResult("Invalid ApiKey provided",
                                                                     Results.Unauthorized,
                                                                     req,
                                                                     Some(descriptor),
                                                                     Some("errors.invalid.api.key"),
                                                  attrs = attrs)
                                                .asLeft[WSFlow]
                                          }
                                      case None =>
                                        Errors
                                          .craftResponseResult("Invalid ApiKey provided",
                                                               Results.Unauthorized,
                                                               req,
                                                               Some(descriptor),
                                                               Some("errors.invalid.api.key"),
                                            attrs = attrs)
                                          .asLeft[WSFlow]
                                    }
                                  } getOrElse Errors
                                    .craftResponseResult("Invalid ApiKey provided",
                                                         Results.Unauthorized,
                                                         req,
                                                         Some(descriptor),
                                                         Some("errors.invalid.api.key"),
                                      attrs = attrs)
                                    .asLeft[WSFlow]
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
                                            Errors
                                              .craftResponseResult("Invalid API key",
                                                                   Results.Unauthorized,
                                                                   req,
                                                                   Some(descriptor),
                                                                   Some("errors.invalid.api.key"),
                                                attrs = attrs)
                                              .asLeft[WSFlow]
                                          case Some(key) if key.isInvalid(apiKeySecret) => {
                                            Alerts.send(
                                              RevokedApiKeyUsageAlert(env.snowflakeGenerator.nextIdStr(),
                                                                      DateTime.now(),
                                                                      env.env,
                                                                      req,
                                                                      key,
                                                                      descriptor)
                                            )
                                            Errors
                                              .craftResponseResult("Bad API key",
                                                                   Results.BadRequest,
                                                                   req,
                                                                   Some(descriptor),
                                                                   Some("errors.bad.api.key"),
                                                attrs = attrs)
                                              .asLeft[WSFlow]
                                          }
                                          case Some(key) if !key.matchRouting(descriptor) => {
                                            Errors
                                              .craftResponseResult(
                                                "Invalid API key",
                                                Unauthorized,
                                                req,
                                                Some(descriptor),
                                                Some("errors.bad.api.key"),
                                                attrs = attrs
                                              )
                                              .asLeft[WSFlow]
                                          }
                                          case Some(key)
                                              if key.restrictions
                                                .handleRestrictions(descriptor, Some(key), req, attrs)
                                                ._1 => {
                                            key.restrictions
                                              .handleRestrictions(descriptor, Some(key), req, attrs)
                                              ._2
                                              .asLeft[WSFlow]
                                          }
                                          case Some(key) if key.isValid(apiKeySecret) =>
                                            key.withingQuotas().flatMap {
                                              case true => callDownstream(config, Some(key))
                                              case false =>
                                                Errors
                                                  .craftResponseResult("You performed too much requests",
                                                                       Results.TooManyRequests,
                                                                       req,
                                                                       Some(descriptor),
                                                                       Some("errors.too.much.requests"),
                                                    attrs = attrs)
                                                  .asLeft[WSFlow]
                                            }
                                        }
                                    }
                                    case _ =>
                                      Errors
                                        .craftResponseResult("No ApiKey provided",
                                                             Results.BadRequest,
                                                             req,
                                                             Some(descriptor),
                                                             Some("errors.bad.api.key"),
                                          attrs = attrs)
                                        .asLeft[WSFlow]
                                  }
                                } else {
                                  Errors
                                    .craftResponseResult("No ApiKey provided",
                                                         Results.BadRequest,
                                                         req,
                                                         Some(descriptor),
                                                         Some("errors.bad.api.key"),
                                      attrs = attrs)
                                    .asLeft[WSFlow]
                                }
                              }
                            }

                            def passWithAuth0(
                                config: GlobalConfig
                            ): Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]] =
                              isPrivateAppsSessionValid(req, descriptor).flatMap {
                                case Some(paUsr) => callDownstream(config, None, Some(paUsr))
                                case None => {
                                  val redirect = req
                                    .getQueryString("redirect")
                                    .getOrElse(s"${protocol}://${req.host}${req.relativeUri}")
                                  val redirectTo = env.rootScheme + env.privateAppsHost + env.privateAppsPort
                                    .map(a => s":$a")
                                    .getOrElse("") + controllers.routes.AuthController
                                    .confidentialAppLoginPage()
                                    .url + s"?desc=${descriptor.id}&redirect=${redirect}"
                                  logger.trace("should redirect to " + redirectTo)
                                  FastFuture.successful(Left(Results.Redirect(redirectTo)))
                                }
                              }

                            globalConfig.withinThrottlingQuota().map(within => (globalConfig, within)) flatMap {
                              tuple =>
                                val (globalConfig, within) = tuple
                                env.datastores.globalConfigDataStore.incrementCallsForIpAddressWithTTL(from).flatMap {
                                  secCalls =>
                                    env.datastores.globalConfigDataStore.quotaForIpAddress(from).map { maybeQuota =>
                                      (secCalls, maybeQuota)
                                    }
                                } flatMap { r =>
                                  val (secCalls, maybeQuota) = r
                                  val quota                  = maybeQuota.getOrElse(globalConfig.perIpThrottlingQuota)
                                  val (restrictionsNotPassing, restrictionsResponse) =
                                    descriptor.restrictions.handleRestrictions(descriptor, None, req, attrs)
                                  if (secCalls > (quota * 10L)) {
                                    Errors
                                      .craftResponseResult("[IP] You performed too much requests",
                                                           Results.TooManyRequests,
                                                           req,
                                                           Some(descriptor),
                                                           Some("errors.too.much.requests"),
                                        attrs = attrs)
                                      .asLeft[WSFlow]
                                  } else {
                                    if (!isSecured && desc.forceHttps) {
                                      val theDomain = req.domain
                                      val protocol  = getWsProtocolFor(req)
                                      logger.trace(
                                        s"redirects prod service from ${protocol}://$theDomain${req.relativeUri} to wss://$theDomain${req.relativeUri}"
                                      )
                                      FastFuture.successful(
                                        Left(
                                          Results
                                            .Redirect(s"${env.rootScheme}$theDomain${req.relativeUri}")
                                            .withHeaders("foo2" -> "bar2")
                                        )
                                      )
                                    } else if (!within) {
                                      // TODO : count as served req here !!!
                                      Errors
                                        .craftResponseResult("[GLOBAL] You performed too much requests",
                                                             Results.TooManyRequests,
                                                             req,
                                                             Some(descriptor),
                                                             Some("errors.too.much.requests"),
                                          attrs = attrs)
                                        .asLeft[WSFlow]
                                    } else if (globalConfig.ipFiltering.notMatchesWhitelist(remoteAddress)) {
                                      /*else if (globalConfig.ipFiltering.whitelist.nonEmpty && !globalConfig.ipFiltering.whitelist
                                                 .exists(ip => utils.RegexPool(ip).matches(remoteAddress))) {*/
                                      Errors
                                        .craftResponseResult("Your IP address is not allowed",
                                                             Results.Forbidden,
                                                             req,
                                                             Some(descriptor),
                                                             Some("errors.ip.address.not.allowed"),
                                          attrs = attrs)
                                        .asLeft[WSFlow] // global whitelist
                                    } else if (globalConfig.ipFiltering.matchesBlacklist(remoteAddress)) {
                                      /*else if (globalConfig.ipFiltering.blacklist.nonEmpty && globalConfig.ipFiltering.blacklist
                                                   .exists(ip => utils.RegexPool(ip).matches(remoteAddress))) {*/
                                      Errors
                                        .craftResponseResult("Your IP address is not allowed",
                                                             Results.Forbidden,
                                                             req,
                                                             Some(descriptor),
                                                             Some("errors.ip.address.not.allowed"),
                                          attrs = attrs)
                                        .asLeft[WSFlow] // global blacklist
                                    } else if (descriptor.ipFiltering.notMatchesWhitelist(remoteAddress)) {
                                      /*else if (descriptor.ipFiltering.whitelist.nonEmpty && !descriptor.ipFiltering.whitelist
                                                   .exists(ip => utils.RegexPool(ip).matches(remoteAddress))) {*/
                                      Errors
                                        .craftResponseResult("Your IP address is not allowed",
                                                             Results.Forbidden,
                                                             req,
                                                             Some(descriptor),
                                                             Some("errors.ip.address.not.allowed"),
                                          attrs = attrs)
                                        .asLeft[WSFlow] // service whitelist
                                    } else if (globalConfig.ipFiltering.matchesBlacklist(remoteAddress)) {
                                      /*else if (descriptor.ipFiltering.blacklist.nonEmpty && descriptor.ipFiltering.blacklist
                                                   .exists(ip => utils.RegexPool(ip).matches(remoteAddress))) {*/
                                      Errors
                                        .craftResponseResult("Your IP address is not allowed",
                                                             Results.Forbidden,
                                                             req,
                                                             Some(descriptor),
                                                             Some("errors.ip.address.not.allowed"),
                                          attrs = attrs)
                                        .asLeft[WSFlow] // service blacklist
                                    } else if (globalConfig.matchesEndlessIpAddresses(remoteAddress)) {
                                      /*else if (globalConfig.endlessIpAddresses.nonEmpty && globalConfig.endlessIpAddresses
                                                 .exists(ip => utils.RegexPool(ip).matches(remoteAddress))) {*/
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
                                        Left(
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
                                      )
                                    } else if (descriptor.maintenanceMode) {
                                      Errors
                                        .craftResponseResult("Service in maintenance mode",
                                                             ServiceUnavailable,
                                                             req,
                                                             Some(descriptor),
                                                             Some("errors.service.in.maintenance"),
                                          attrs = attrs)
                                        .asLeft[WSFlow]
                                    } else if (descriptor.buildMode) {
                                      Errors
                                        .craftResponseResult("Service under construction",
                                                             ServiceUnavailable,
                                                             req,
                                                             Some(descriptor),
                                                             Some("errors.service.under.construction"),
                                          attrs = attrs)
                                        .asLeft[WSFlow]
                                    } else if (restrictionsNotPassing) {
                                      restrictionsResponse.asLeft[WSFlow]
                                    } else if (isUp) {
                                      if (descriptor.isPrivate && descriptor.authConfigRef.isDefined && !descriptor
                                            .isExcludedFromSecurity(req.path)) {
                                        if (descriptor.isUriPublic(req.path)) {
                                          passWithAuth0(globalConfig)
                                        } else {
                                          isPrivateAppsSessionValid(req, descriptor).flatMap {
                                            case Some(_) if descriptor.strictlyPrivate => passWithApiKey(globalConfig)
                                            case Some(user)                            => passWithAuth0(globalConfig)
                                            case None                                  => passWithApiKey(globalConfig)
                                          }
                                        }
                                      } else {
                                        if (descriptor.isUriPublic(req.path)) {
                                          if (env.detectApiKeySooner && descriptor.detectApiKeySooner && ApiKeyHelper
                                                .detectApiKey(req, descriptor)) {
                                            passWithApiKey(globalConfig)
                                          } else {
                                            callDownstream(globalConfig)
                                          }
                                        } else {
                                          passWithApiKey(globalConfig)
                                        }
                                      }
                                    } else {
                                      // fail fast
                                      Errors
                                        .craftResponseResult("The service seems to be down :( come back later",
                                                             Results.Forbidden,
                                                             req,
                                                             Some(descriptor),
                                                             Some("errors.service.down"),
                                          attrs = attrs)
                                        .asLeft[WSFlow]
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
    env.metrics.withTimer("otoroshi.core.proxy.handle-ws-request")(finalResult)
  }
}

object WebSocketProxyActor {

  lazy val logger = Logger("otoroshi-websocket")

  def props(url: String,
            out: ActorRef,
            headers: Seq[(String, String)],
            descriptor: ServiceDescriptor,
            target: Target,
            env: Env) =
    Props(new WebSocketProxyActor(url, out, headers, descriptor, target, env))

  def wsCall(url: String,
             headers: Seq[(String, String)],
             descriptor: ServiceDescriptor,
             target: Target)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Flow[PlayWSMessage, PlayWSMessage, Future[Option[NotUsed]]] = {
      val avoid = Seq("Upgrade", "Connection", "Sec-WebSocket-Version", "Sec-WebSocket-Extensions", "Sec-WebSocket-Key")
      val _headers = headers.toList.filterNot(t => avoid.contains(t._1)).flatMap {
        case (key, value) if key.toLowerCase == "cookie" =>
          Try(value.split(";").toSeq.map(_.trim).filterNot(_.isEmpty).map { cookie =>
            val parts = cookie.split("=")
            val name = parts(0)
            val cookieValue = parts.tail.mkString("=")
            akka.http.scaladsl.model.headers.Cookie(name, cookieValue)
          }) match {
            case Success(seq) => seq
            case Failure(e) => List.empty
          }
        case (key, value) if key.toLowerCase == "host" =>
          Seq(akka.http.scaladsl.model.headers.Host(Uri(value).authority.host))
        case (key, value) if key.toLowerCase == "user-agent" =>
          Seq(akka.http.scaladsl.model.headers.`User-Agent`(value))
        case (key, value)                                    =>
          Seq(RawHeader(key, value))
      }
      val request = _headers.foldLeft[WebSocketRequest](WebSocketRequest(url))(
        (r, header) => r.copy(extraHeaders = r.extraHeaders :+ header)
      )
      val flow = Flow.fromSinkAndSourceMat(
        Sink.asPublisher[akka.http.scaladsl.model.ws.Message](fanout = false),
        Source.asSubscriber[akka.http.scaladsl.model.ws.Message]
      )(Keep.both)
      val (connected, (publisher, subscriber)) = env.gatewayClient.ws(
        request,
        target.loose,
        flow,
        descriptor.clientConfig.proxy
          .orElse(env.datastores.globalConfigDataStore.latestSafe.flatMap(_.proxies.services))
          .filter(
            p =>
              WSProxyServerUtils.isIgnoredForHost(Uri(url).authority.host.toString(),
                p.nonProxyHosts.getOrElse(Seq.empty))
          )
          .map { proxySettings =>
            val proxyAddress = InetSocketAddress.createUnresolved(proxySettings.host, proxySettings.port)
            val httpsProxyTransport = (proxySettings.principal, proxySettings.password) match {
              case (Some(principal), Some(password)) => {
                val auth = akka.http.scaladsl.model.headers.BasicHttpCredentials(principal, password)
                ClientTransport.httpsProxy(proxyAddress, auth)
              }
              case _ => ClientTransport.httpsProxy(proxyAddress)
            }
            // TODO: use proxy transport when akka http will be updated
            a: ClientConnectionSettings =>
              // a.withTransport(httpsProxyTransport)
              a.withIdleTimeout(descriptor.clientConfig.idleTimeout.millis)
                .withConnectingTimeout(descriptor.clientConfig.connectionTimeout.millis)
          } getOrElse { a: ClientConnectionSettings =>
          a.withIdleTimeout(descriptor.clientConfig.idleTimeout.millis)
            .withConnectingTimeout(descriptor.clientConfig.connectionTimeout.millis)
        }
      )
    Flow.lazyInitAsync[PlayWSMessage, PlayWSMessage, NotUsed] { () =>
      connected.flatMap { r =>
        logger.trace(
          s"[WEBSOCKET] connected to target ${r.response.status} :: ${r.response.headers.map(h => h.toString()).mkString(", ")}"
        )
        r match {
          case ValidUpgrade(response, chosenSubprotocol) =>
            val f: Flow[PlayWSMessage, PlayWSMessage, NotUsed] = Flow.fromSinkAndSource(
              Sink.fromSubscriber(subscriber).contramap {
                case PlayWSTextMessage(text)      => akka.http.scaladsl.model.ws.TextMessage(text)
                case PlayWSBinaryMessage(data)    => akka.http.scaladsl.model.ws.BinaryMessage(data)
                case PingMessage(data)            => akka.http.scaladsl.model.ws.BinaryMessage(data)
                case PongMessage(data)            => akka.http.scaladsl.model.ws.BinaryMessage(data)
                case CloseMessage(status, reason) =>
                  logger.error(s"close message $status: $reason")
                  akka.http.scaladsl.model.ws.BinaryMessage(ByteString.empty)
                  // throw new RuntimeException(reason)
                case m =>
                  logger.error(s"Unknown message $m")
                  throw new RuntimeException(s"Unknown message $m")
              },
              Source.fromPublisher(publisher).mapAsync(1) {
                case akka.http.scaladsl.model.ws.TextMessage.Strict(text)       => FastFuture.successful(PlayWSTextMessage(text))
                case akka.http.scaladsl.model.ws.TextMessage.Streamed(source)   => source.runFold("")((concat, str) => concat + str).map(str => PlayWSTextMessage(str))
                case akka.http.scaladsl.model.ws.BinaryMessage.Strict(data)     => FastFuture.successful(PlayWSBinaryMessage(data))
                case akka.http.scaladsl.model.ws.BinaryMessage.Streamed(source) => source.runFold(ByteString.empty)((concat, str) => concat ++ str).map(data => PlayWSBinaryMessage(data))
                case other                                                      => FastFuture.failed(new RuntimeException(s"Unkown message type ${other}"))
              }
            )
            FastFuture.successful(f)
          case InvalidUpgradeResponse(response, cause) =>
            FastFuture.failed(new RuntimeException(cause))
        }
      }
    }
  }
}

class WebSocketProxyActor(url: String,
                          out: ActorRef,
                          headers: Seq[(String, String)],
                          descriptor: ServiceDescriptor,
                          target: Target,
                          env: Env)
    extends Actor {

  import scala.concurrent.duration._

  implicit val ec = env.otoroshiExecutionContext
  implicit val mat = env.otoroshiMaterializer

  lazy val source = Source.queue[akka.http.scaladsl.model.ws.Message](50000, OverflowStrategy.dropTail)
  lazy val logger = Logger("otoroshi-websocket-handler-actor")

  val queueRef = new AtomicReference[SourceQueueWithComplete[akka.http.scaladsl.model.ws.Message]]

  val avoid = Seq("Upgrade", "Connection", "Sec-WebSocket-Version", "Sec-WebSocket-Extensions", "Sec-WebSocket-Key")
  // Seq("Upgrade", "Connection", "Sec-WebSocket-Key", "Sec-WebSocket-Version", "Sec-WebSocket-Extensions", "Host")

  override def preStart() =
    try {
      logger.trace("[WEBSOCKET] initializing client call ...")
      val _headers = headers.toList.filterNot(t => avoid.contains(t._1)).flatMap {
        case (key, value) if key.toLowerCase == "cookie" =>
          Try(value.split(";").toSeq.map(_.trim).filterNot(_.isEmpty).map { cookie =>
            val parts = cookie.split("=")
            val name = parts(0)
            val cookieValue = parts.tail.mkString("=")
            akka.http.scaladsl.model.headers.Cookie(name, cookieValue)
          }) match {
            case Success(seq) => seq
            case Failure(e) => List.empty
          }
        case (key, value) if key.toLowerCase == "host" =>
          Seq(akka.http.scaladsl.model.headers.Host(Uri(value).authority.host))
        case (key, value) if key.toLowerCase == "user-agent" =>
          Seq(akka.http.scaladsl.model.headers.`User-Agent`(value))
        case (key, value)                                    =>
          Seq(RawHeader(key, value))
      }
      val request = _headers.foldLeft[WebSocketRequest](WebSocketRequest(url))(
        (r, header) => r.copy(extraHeaders = r.extraHeaders :+ header)
      )
      val (connected, materialized) = env.gatewayClient.ws(
        request,
        target.loose,
        Flow
          .fromSinkAndSourceMat(
            Sink.foreach[akka.http.scaladsl.model.ws.Message] {
              case akka.http.scaladsl.model.ws.TextMessage.Strict(text)       =>
                logger.debug(s"[WEBSOCKET] text message from target")
                out ! PlayWSTextMessage(text)
              case akka.http.scaladsl.model.ws.TextMessage.Streamed(source)   =>
                logger.debug(s"[WEBSOCKET] streamed text message from target")
                source.runFold("")((concat, str) => concat + str).map(text => out ! PlayWSTextMessage(text))
              case akka.http.scaladsl.model.ws.BinaryMessage.Strict(data)     =>
                logger.debug(s"[WEBSOCKET] binary message from target")
                out ! PlayWSBinaryMessage(data)
              case akka.http.scaladsl.model.ws.BinaryMessage.Streamed(source) =>
                logger.debug(s"[WEBSOCKET] binary message from target")
                source.runFold(ByteString.empty)((concat, str) => concat ++ str).map(data => out ! PlayWSBinaryMessage(data))
              case other => logger.error(s"Unkown message type ${other}")
            },
            source
          )(Keep.both)
          .alsoTo(Sink.onComplete { _ =>
            logger.trace(s"[WEBSOCKET] target stopped")
            Option(queueRef.get()).foreach(_.complete())
            out ! PoisonPill
          }),
        descriptor.clientConfig.proxy
          .orElse(env.datastores.globalConfigDataStore.latestSafe.flatMap(_.proxies.services))
          .filter(
            p =>
              WSProxyServerUtils.isIgnoredForHost(Uri(url).authority.host.toString(),
                                                  p.nonProxyHosts.getOrElse(Seq.empty))
          )
          .map { proxySettings =>
            val proxyAddress = InetSocketAddress.createUnresolved(proxySettings.host, proxySettings.port)
            val httpsProxyTransport = (proxySettings.principal, proxySettings.password) match {
              case (Some(principal), Some(password)) => {
                val auth = akka.http.scaladsl.model.headers.BasicHttpCredentials(principal, password)
                ClientTransport.httpsProxy(proxyAddress, auth)
              }
              case _ => ClientTransport.httpsProxy(proxyAddress)
            }
            // TODO: use proxy transport when akka http will be updated
            a: ClientConnectionSettings =>
              //a //.withTransport(httpsProxyTransport)
              a.withIdleTimeout(descriptor.clientConfig.idleTimeout.millis)
                .withConnectingTimeout(descriptor.clientConfig.connectionTimeout.millis)
          } getOrElse { a: ClientConnectionSettings =>
          a.withIdleTimeout(descriptor.clientConfig.idleTimeout.millis)
            .withConnectingTimeout(descriptor.clientConfig.connectionTimeout.millis)
        }
      )
      queueRef.set(materialized._2)
      connected.andThen {
        case Success(r) => {
          implicit val ec  = env.otoroshiExecutionContext
          implicit val mat = env.otoroshiMaterializer
          logger.trace(
            s"[WEBSOCKET] connected to target ${r.response.status} :: ${r.response.headers.map(h => h.toString()).mkString(", ")}"
          )
          r.response.entity.dataBytes.runFold(ByteString.empty)(_ ++ _).map { bs =>
            logger.trace(s"[WEBSOCKET] connected to target with response '${bs.utf8String}'")
          }
        }
        case Failure(e) => logger.error(s"[WEBSOCKET] error", e)
      }(context.dispatcher)
    } catch {
      case e: Exception => logger.error("[WEBSOCKET] error during call", e)
    }

  override def postStop() = {
    logger.trace(s"[WEBSOCKET] client stopped")
    Option(queueRef.get()).foreach(_.complete())
    out ! PoisonPill
  }

  def receive = {
    case msg: PlayWSBinaryMessage => {
      logger.debug(s"[WEBSOCKET] binary message from client: ${msg.data.utf8String}")
      Option(queueRef.get()).foreach(_.offer(akka.http.scaladsl.model.ws.BinaryMessage(msg.data)))
    }
    case msg: PlayWSTextMessage => {
      logger.debug(s"[WEBSOCKET] text message from client: ${msg.data}")
      Option(queueRef.get()).foreach(_.offer(akka.http.scaladsl.model.ws.TextMessage(msg.data)))
    }
    case msg: PingMessage => {
      logger.debug(s"[WEBSOCKET] Ping message from client: ${msg.data}")
      Option(queueRef.get()).foreach(_.offer(akka.http.scaladsl.model.ws.BinaryMessage(msg.data)))
    }
    case msg: PongMessage => {
      logger.debug(s"[WEBSOCKET] Pong message from client: ${msg.data}")
      Option(queueRef.get()).foreach(_.offer(akka.http.scaladsl.model.ws.BinaryMessage(msg.data)))
    }
    case CloseMessage(status, reason) => {
      logger.debug(s"[WEBSOCKET] close message from client: $status : $reason")
      Option(queueRef.get()).foreach(_.complete())
      out ! PoisonPill
    }
    case e => logger.error(s"[WEBSOCKET] Bad message type: $e")
  }
}




object Tests {



  val source: Source[(Int, String, utils.Datagram), NotUsed] = ???

  val flow: Flow[utils.Datagram, utils.Datagram, NotUsed] = ???







}
