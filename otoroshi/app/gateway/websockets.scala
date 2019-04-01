package gateway

import java.net.InetSocketAddress
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong, AtomicReference}

import akka.actor.{Actor, ActorRef, PoisonPill, Props}
import akka.http.scaladsl.ClientTransport
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.ws.{Message, WebSocketRequest}
import akka.http.scaladsl.settings.{ClientConnectionSettings, ConnectionPoolSettings}
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{Flow, Keep, Sink, Source, SourceQueueWithComplete}
import akka.stream.{ActorMaterializer, Materializer, OverflowStrategy}
import akka.util.ByteString
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.google.common.base.Charsets
import env.{Env, SidecarConfig}
import events._
import models._
import org.joda.time.DateTime
import play.api.Logger
import play.api.http.HttpEntity
import play.api.http.websocket.{CloseMessage, BinaryMessage => PlayWSBinaryMessage, Message => PlayWSMessage, TextMessage => PlayWSTextMessage}
import play.api.libs.streams.ActorFlow
import play.api.mvc.Results.{BadGateway, MethodNotAllowed, ServiceUnavailable, Status, TooManyRequests}
import play.api.mvc._
import play.api.libs.json.Json
import security.{IdGenerator, OtoroshiClaim}
import utils.{Metrics, UrlSanitizer}
import utils.future.Implicits._

import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}
import utils.RequestImplicits._
import otoroshi.script.Implicits._
import play.api.libs.ws.DefaultWSCookie
import utils.http.WSProxyServerUtils

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

  def applyJwtVerifier(service: ServiceDescriptor, req: RequestHeader)(
      f: JwtInjection => Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]]
  )(implicit env: Env): Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]] = {
    if (service.jwtVerifier.enabled) {
      service.jwtVerifier.shouldBeVerified(req.path).flatMap {
        case false => f(JwtInjection())
        case true => {
          logger.debug(s"Applying JWT verification for service ${service.id}:${service.name}")
          service.jwtVerifier.verifyWs(req, service)(f)
        }
      }
    } else {
      f(JwtInjection())
    }
  }

  def applySidecar(service: ServiceDescriptor, remoteAddress: String, req: RequestHeader)(
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
            None
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
                None
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

  def passWithHeadersVerification(desc: ServiceDescriptor, req: RequestHeader)(
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
              Some("errors.missing.headers")
            )
            .asLeft[WSFlow]
        case None => f
      }
    }
  }

  def passWithReadOnly(readOnly: Boolean, req: RequestHeader)(
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
                Some("errors.method.not.allowed")
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
    val requestTimestamp = DateTime.now().toString("yyyy-MM-dd'T'HH:mm:ss.SSSZZ")
    val reqNumber        = reqCounter.incrementAndGet()
    val remoteAddress    = req.headers.get("X-Forwarded-For").getOrElse(req.remoteAddress)
    val isSecured        = getSecuredFor(req)
    val protocol         = getWsProtocolFor(req)
    val from             = req.headers.get("X-Forwarded-For").getOrElse(req.remoteAddress)
    val counterIn        = new AtomicLong(0L)
    val counterOut       = new AtomicLong(0L)
    val start            = System.currentTimeMillis()

    env.datastores.globalConfigDataStore.singleton().flatMap { globalConfig =>
      ServiceLocation(req.host, globalConfig) match {
        case None =>
          Errors
            .craftResponseResult(s"Service not found for URL ${req.host}::${req.relativeUri}",
                                 Results.NotFound,
                                 req,
                                 None,
                                 Some("errors.service.not.found"))
            .asLeft[WSFlow]
        case Some(ServiceLocation(domain, serviceEnv, subdomain)) => {
          val uriParts = req.relativeUri.split("/").toSeq

          env.datastores.serviceDescriptorDataStore
            .find(ServiceDescriptorQuery(subdomain, serviceEnv, domain, req.relativeUri, req.headers.toSimpleMap))
            .flatMap {
              case None =>
                Errors
                  .craftResponseResult(s"Downstream service not found",
                                       Results.NotFound,
                                       req,
                                       None,
                                       Some("errors.service.not.found"))
                  .asLeft[WSFlow]
              case Some(desc) if !desc.enabled =>
                Errors
                  .craftResponseResult(s"Service not found",
                                       Results.NotFound,
                                       req,
                                       None,
                                       Some("errors.service.not.found"))
                  .asLeft[WSFlow]
              case Some(rawDesc) if rawDesc.redirection.enabled && rawDesc.redirection.hasValidCode => {
                FastFuture
                  .successful(
                    Results
                      .Status(rawDesc.redirection.code)
                      .withHeaders("Location" -> rawDesc.redirection.formattedTo(req))
                  )
                  .asLeft[WSFlow]
              }
              case Some(rawDesc) =>
                passWithHeadersVerification(rawDesc, req) {
                  passWithReadOnly(rawDesc.readOnly, req) {
                    applyJwtVerifier(rawDesc, req) { jwtInjection =>
                      applySidecar(rawDesc, remoteAddress, req) { desc =>
                        val maybeTrackingId = req.cookies
                          .get("oto-client")
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

                        val withTrackingCookies: Seq[Cookie] =
                          if (!desc.canary.enabled)
                            jwtInjection.additionalCookies
                              .map(t => Cookie(t._1, t._2))
                              .toSeq //Seq.empty[play.api.mvc.Cookie]
                          else if (maybeTrackingId.isDefined)
                            jwtInjection.additionalCookies
                              .map(t => Cookie(t._1, t._2))
                              .toSeq //Seq.empty[play.api.mvc.Cookie]
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
                            ) ++ jwtInjection.additionalCookies.map(t => Cookie(t._1, t._2))

                        desc.isUp
                          .flatMap(iu => splitToCanary(desc, trackingId, reqNumber, globalConfig).map(d => (iu, d)))
                          .flatMap { tuple =>
                            val (isUp, _desc) = tuple
                            val descriptor = _desc

                            def callDownstream(
                                                config: GlobalConfig,
                                                apiKey: Option[ApiKey] = None,
                                                paUsr: Option[PrivateAppsUser] = None
                                              ): Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]] = {
                              desc.wsValidateClientCertificates(req, apiKey, paUsr, config) {
                                passWithReadOnly(apiKey.map(_.readOnly).getOrElse(false), req) {
                                  if (config.useCircuitBreakers && descriptor.clientConfig.useCircuitBreaker) {
                                    val cbStart = System.currentTimeMillis()
                                    val counter = new AtomicInteger(0)
                                    env.circuitBeakersHolder
                                      .get(descriptor.id, () => new ServiceDescriptorCircuitBreaker())
                                      .callWS(
                                        descriptor,
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
                                            BadGateway,
                                            req,
                                            Some(descriptor),
                                            Some("errors.request.timeout")
                                          )
                                          .asLeft[WSFlow]
                                      case RequestTimeoutException =>
                                        Errors
                                          .craftResponseResult(
                                            s"Something went wrong, the downstream service does not respond quickly enough, you should try later. Thanks for your understanding",
                                            BadGateway,
                                            req,
                                            Some(descriptor),
                                            Some("errors.request.timeout")
                                          )
                                          .asLeft[WSFlow]
                                      case AllCircuitBreakersOpenException =>
                                        Errors
                                          .craftResponseResult(
                                            s"Something went wrong, the downstream service seems a little bit overwhelmed, you should try later. Thanks for your understanding",
                                            BadGateway,
                                            req,
                                            Some(descriptor),
                                            Some("errors.circuit.breaker.open")
                                          )
                                          .asLeft[WSFlow]
                                      case error if error.getMessage.toLowerCase().contains("connection refused") =>
                                        Errors
                                          .craftResponseResult(
                                            s"Something went wrong, the connection to downstream service was refused, you should try later. Thanks for your understanding",
                                            BadGateway,
                                            req,
                                            Some(descriptor),
                                            Some("errors.connection.refused")
                                          )
                                          .asLeft[WSFlow]
                                      case error =>
                                        Errors
                                          .craftResponseResult(
                                            s"Something went wrong, you should try later. Thanks for your understanding. ${error.getMessage}",
                                            BadGateway,
                                            req,
                                            Some(descriptor),
                                            Some("errors.proxy.error")
                                          )
                                          .asLeft[WSFlow]
                                    }
                                  } else {
                                    val index = reqCounter.get() % (if (descriptor.targets.nonEmpty)
                                      descriptor.targets.size
                                    else 1)
                                    // Round robin loadbalancing is happening here !!!!!
                                    val target = descriptor.targets.apply(index.toInt)
                                    actuallyCallDownstream(target, apiKey, paUsr, 0, 1)
                                  }
                                }
                              }
                            }

                            def actuallyCallDownstream(
                                                        target: Target,
                                                        apiKey: Option[ApiKey] = None,
                                                        paUsr: Option[PrivateAppsUser] = None,
                                                        cbDuration: Long,
                                                        callAttempts: Int
                                                      ): Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]] = {
                              logger.trace("[WEBSOCKET] Call downstream !!!")
                              val snowflake = env.snowflakeGenerator.nextIdStr()
                              val state = IdGenerator.extendedToken(128)
                              val rawUri = req.relativeUri.substring(1)
                              val uriParts = rawUri.split("/").toSeq
                              val uri: String =
                                descriptor.matchingRoot.map(m => req.relativeUri.replace(m, "")).getOrElse(rawUri)
                              // val index = reqCounter.incrementAndGet() % (if (descriptor.targets.nonEmpty) descriptor.targets.size else 1)
                              // // Round robin loadbalancing is happening here !!!!!
                              // val target = descriptor.targets.apply(index.toInt)
                              val scheme = if (descriptor.redirectToLocal) descriptor.localScheme else target.scheme
                              val host = if (descriptor.redirectToLocal) descriptor.localHost else target.host
                              val root = descriptor.root
                              val url = s"${if (target.scheme == "https") "wss" else "ws"}://$host$root$uri"
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
                                .withClaim("user", paUsr.map(u => Json.stringify(u.toJson)))
                                .withClaim("apikey",
                                  apiKey.map(
                                    ak =>
                                      Json.stringify(
                                        Json.obj(
                                          "clientId" -> ak.clientId,
                                          "clientName" -> ak.clientName,
                                          "metadata" -> ak.metadata
                                        )
                                      )
                                  ))
                                .serialize(desc.secComSettings)(env)
                              logger.trace(s"Claim is : $claim")
                              val headersIn: Seq[(String, String)] =
                                (req.headers.toMap.toSeq
                                  .flatMap(c => c._2.map(v => (c._1, v))) //.map(tuple => (tuple._1, tuple._2.mkString(","))) //.toSimpleMap
                                  .filterNot(t => headersInFiltered.contains(t._1.toLowerCase)) ++ Map(
                                  env.Headers.OtoroshiProxiedHost -> req.headers.get("Host").getOrElse("--"),
                                  // "Host"                               -> host,
                                  "Host" -> (if (desc.overrideHost) host else req.headers.get("Host").getOrElse("--")),
                                  env.Headers.OtoroshiRequestId -> snowflake,
                                  env.Headers.OtoroshiRequestTimestamp -> requestTimestamp
                                ) ++ (if (descriptor.enforceSecureCommunication && descriptor.sendStateChallenge) {
                                  Map(
                                    env.Headers.OtoroshiState -> state,
                                    env.Headers.OtoroshiClaim -> claim
                                  )
                                } else if (descriptor.enforceSecureCommunication && !descriptor.sendStateChallenge) {
                                  Map(
                                    env.Headers.OtoroshiClaim -> claim
                                  )
                                } else {
                                  Map.empty[String, String]
                                }) ++
                                  descriptor.additionalHeaders.filter(t => t._1.trim.nonEmpty) ++ fromOtoroshi
                                  .map(v => Map(env.Headers.OtoroshiGatewayParentRequest -> fromOtoroshi.get))
                                  .getOrElse(Map.empty[String, String]) ++ jwtInjection.additionalHeaders).toSeq
                                  .filterNot(t => jwtInjection.removeHeaders.contains(t._1)) ++ xForwardedHeader(desc,
                                  req)

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
                                      case Failure(e) => logger.error("Error while updating call metrics reporting", e)
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
                                      GatewayEvent(
                                        `@id` = env.snowflakeGenerator.nextIdStr(),
                                        reqId = snowflake,
                                        parentReqId = fromOtoroshi,
                                        `@timestamp` = DateTime.now(),
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
                                        viz = Some(viz)
                                      ).toAnalytics()
                                    }
                                  }
                                }
                              }

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
                              descriptor
                                .transformRequest(
                                  snowflake = snowflake,
                                  rawRequest = rawRequest,
                                  otoroshiRequest = otoroshiRequest,
                                  desc = descriptor,
                                  apiKey = apiKey,
                                  user = paUsr
                                )
                                .flatMap {
                                  case Left(badResult) => FastFuture.successful(badResult).asLeft[WSFlow]
                                  case Right(httpRequest) => {
                                    FastFuture.successful(
                                      Right(
                                        ActorFlow.actorRef(
                                          out =>
                                            WebSocketProxyActor.props(UrlSanitizer.sanitize(httpRequest.url),
                                              out,
                                              httpRequest.headers.toSeq
                                                .filterNot(_._1 == "Cookie"),
                                              descriptor,
                                              env)
                                        )
                                      )
                                    )
                                  }
                                }
                            }

                            def passWithApiKey(
                                                config: GlobalConfig
                                              ): Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]] = {
                              if (descriptor.thirdPartyApiKey.enabled) {
                                descriptor.thirdPartyApiKey.handleWS(req, descriptor, config) { key =>
                                  callDownstream(config, key)
                                }
                              } else {
                                val authByJwtToken = req.headers
                                  .get(descriptor.apiKeyConstraints.jwtAuth.headerName.getOrElse(env.Headers.OtoroshiBearer))
                                  .orElse(
                                    req.headers.get("Authorization").filter(_.startsWith("Bearer "))
                                  )
                                  .map(_.replace("Bearer ", ""))
                                  .orElse(
                                    req.queryString
                                      .get(descriptor.apiKeyConstraints.jwtAuth.queryName.getOrElse(env.Headers.OtoroshiBearerAuthorization))
                                      .flatMap(_.lastOption)
                                  )
                                  .orElse(req.cookies.get(descriptor.apiKeyConstraints.jwtAuth.cookieName.getOrElse(env.Headers.OtoroshiJWTAuthorization)).map(_.value))
                                  .filter(_.split("\\.").length == 3)
                                val authBasic = req.headers
                                  .get(descriptor.apiKeyConstraints.basicAuth.headerName.getOrElse(env.Headers.OtoroshiAuthorization))
                                  .orElse(
                                    req.headers.get("Authorization").filter(_.startsWith("Basic "))
                                  )
                                  .map(_.replace("Basic ", ""))
                                  .flatMap(e => Try(decodeBase64(e)).toOption)
                                  .orElse(
                                    req.queryString
                                      .get(descriptor.apiKeyConstraints.basicAuth.queryName.getOrElse(env.Headers.OtoroshiBasicAuthorization))
                                      .flatMap(_.lastOption)
                                      .flatMap(e => Try(decodeBase64(e)).toOption)
                                  )
                                val authByCustomHeaders = req.headers
                                  .get(descriptor.apiKeyConstraints.customHeadersAuth.clientIdHeaderName.getOrElse(env.Headers.OtoroshiClientId))
                                  .flatMap(
                                    id => req.headers.get(descriptor.apiKeyConstraints.customHeadersAuth.clientSecretHeaderName.getOrElse(env.Headers.OtoroshiClientSecret)).map(s => (id, s))
                                  )
                                val authBySimpleApiKeyClientId = req.headers
                                  .get(descriptor.apiKeyConstraints.clientIdAuth.headerName.getOrElse(env.Headers.OtoroshiSimpleApiKeyClientId))
                                  .orElse(
                                    req.queryString
                                      .get(descriptor.apiKeyConstraints.clientIdAuth.queryName.getOrElse(env.Headers.OtoroshiSimpleApiKeyClientId))
                                      .flatMap(_.lastOption)
                                  )
                                if (authBySimpleApiKeyClientId.isDefined && descriptor.apiKeyConstraints.clientIdAuth.enabled) {
                                  val clientId = authBySimpleApiKeyClientId.get
                                  env.datastores.apiKeyDataStore.findAuthorizeKeyFor(clientId, descriptor.id).flatMap {
                                    case None =>
                                      Errors
                                        .craftResponseResult(
                                          "Invalid API key",
                                          BadGateway,
                                          req,
                                          Some(descriptor),
                                          Some("errors.invalid.api.key")
                                        )
                                        .asLeft[WSFlow]
                                    case Some(key) if !key.allowClientIdOnly => {
                                      Errors
                                        .craftResponseResult(
                                          "Bad API key",
                                          BadGateway,
                                          req,
                                          Some(descriptor),
                                          Some("errors.bad.api.key")
                                        )
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
                                              Some("errors.too.much.requests")
                                            )
                                            .asLeft[WSFlow]
                                      }
                                  }
                                } else if (authByCustomHeaders.isDefined && descriptor.apiKeyConstraints.customHeadersAuth.enabled) {
                                  val (clientId, clientSecret) = authByCustomHeaders.get
                                  env.datastores.apiKeyDataStore.findAuthorizeKeyFor(clientId, descriptor.id).flatMap {
                                    case None =>
                                      Errors
                                        .craftResponseResult("Invalid API key",
                                          Results.BadGateway,
                                          req,
                                          Some(descriptor),
                                          Some("errors.invalid.api.key"))
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
                                          Results.BadGateway,
                                          req,
                                          Some(descriptor),
                                          Some("errors.bad.api.key"))
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
                                              Some("errors.too.much.requests"))
                                            .asLeft[WSFlow]
                                      }
                                  }
                                } else if (authByJwtToken.isDefined && descriptor.apiKeyConstraints.jwtAuth.enabled) {
                                  val jwtTokenValue = authByJwtToken.get
                                  Try {
                                    JWT.decode(jwtTokenValue)
                                  } map { jwt =>
                                    Option(jwt.getClaim("iss")).filterNot(_.isNull).map(_.asString()).orElse(
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
                                              val exp = Option(jwt.getClaim("exp")).filterNot(_.isNull).map(_.asLong())
                                              val iat = Option(jwt.getClaim("iat")).filterNot(_.isNull).map(_.asLong())
                                              val httpPath = Option(jwt.getClaim("httpPath")).filterNot(_.isNull).map(_.asString())
                                              val httpVerb = Option(jwt.getClaim("httpVerb")).filterNot(_.isNull).map(_.asString())
                                              val httpHost = Option(jwt.getClaim("httpHost")).filterNot(_.isNull).map(_.asString())
                                              val verifier = JWT.require(algorithm).withIssuer(clientId).acceptLeeway(10).build
                                              Try(verifier.verify(jwtTokenValue)).filter { token =>
                                                val xsrfToken = token.getClaim("xsrfToken")
                                                val xsrfTokenHeader = req.headers.get("X-XSRF-TOKEN")
                                                if (!xsrfToken.isNull && xsrfTokenHeader.isDefined) {
                                                  xsrfToken.asString() == xsrfTokenHeader.get
                                                } else if (!xsrfToken.isNull && xsrfTokenHeader.isEmpty) {
                                                  false
                                                } else {
                                                  true
                                                }
                                              }.filter { _ =>
                                                if (exp.isEmpty || iat.isEmpty) {
                                                  false
                                                } else {
                                                  if ((exp.get - iat.get) <= desc.apiKeyConstraints.jwtAuth.maxJwtLifespanSecs) {
                                                    true
                                                  } else {
                                                    false
                                                  }
                                                }
                                              }.filter { _ =>
                                                if (descriptor.apiKeyConstraints.jwtAuth.includeRequestAttributes) {
                                                  val matchPath = httpPath.exists(_ == req.relativeUri)
                                                  val matchVerb = httpVerb.exists(_.toLowerCase == req.method.toLowerCase)
                                                  val matchHost = httpHost.exists(_.toLowerCase == req.host)
                                                  matchPath && matchVerb && matchHost
                                                } else {
                                                  true
                                                }
                                              } match {
                                                case Success(_) =>
                                                  apiKey.withingQuotas().flatMap {
                                                    case true => callDownstream(config, Some(apiKey))
                                                    case false =>
                                                      Errors
                                                        .craftResponseResult("You performed too much requests",
                                                          Results.TooManyRequests,
                                                          req,
                                                          Some(descriptor),
                                                          Some("errors.too.much.requests"))
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
                                                      Results.BadGateway,
                                                      req,
                                                      Some(descriptor),
                                                      Some("errors.bad.api.key"))
                                                    .asLeft[WSFlow]
                                                }
                                              }
                                            }
                                            case None =>
                                              Errors
                                                .craftResponseResult("Invalid ApiKey provided",
                                                  Results.BadRequest,
                                                  req,
                                                  Some(descriptor),
                                                  Some("errors.invalid.api.key"))
                                                .asLeft[WSFlow]
                                          }
                                      case None =>
                                        Errors
                                          .craftResponseResult("Invalid ApiKey provided",
                                            Results.BadRequest,
                                            req,
                                            Some(descriptor),
                                            Some("errors.invalid.api.key"))
                                          .asLeft[WSFlow]
                                    }
                                  } getOrElse Errors
                                    .craftResponseResult("Invalid ApiKey provided",
                                      Results.BadRequest,
                                      req,
                                      Some(descriptor),
                                      Some("errors.invalid.api.key"))
                                    .asLeft[WSFlow]
                                } else if (authBasic.isDefined && descriptor.apiKeyConstraints.basicAuth.enabled) {
                                  val auth = authBasic.get
                                  val id = auth.split(":").headOption.map(_.trim)
                                  val secret = auth.split(":").lastOption.map(_.trim)
                                  (id, secret) match {
                                    case (Some(apiKeyClientId), Some(apiKeySecret)) => {
                                      env.datastores.apiKeyDataStore
                                        .findAuthorizeKeyFor(apiKeyClientId, descriptor.id)
                                        .flatMap {
                                          case None =>
                                            Errors
                                              .craftResponseResult("Invalid API key",
                                                Results.BadGateway,
                                                req,
                                                Some(descriptor),
                                                Some("errors.invalid.api.key"))
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
                                                Results.BadGateway,
                                                req,
                                                Some(descriptor),
                                                Some("errors.bad.api.key"))
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
                                                    Some("errors.too.much.requests"))
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
                                          Some("errors.bad.api.key"))
                                        .asLeft[WSFlow]
                                  }
                                } else {
                                  Errors
                                    .craftResponseResult("No ApiKey provided",
                                      Results.BadRequest,
                                      req,
                                      Some(descriptor),
                                      Some("errors.bad.api.key"))
                                    .asLeft[WSFlow]
                                }
                              }
                            }

                            def passWithAuth0(
                                config: GlobalConfig
                            ): Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]] =
                              isPrivateAppsSessionValid(req, descriptor).flatMap {
                                case Some(paUsr) => callDownstream(config, paUsr = Some(paUsr))
                                case None => {
                                  val redirectTo = env.rootScheme + env.privateAppsHost + env.privateAppsPort
                                    .map(a => s":$a")
                                    .getOrElse("") + controllers.routes.AuthController
                                    .confidentialAppLoginPage()
                                    .url + s"?desc=${descriptor.id}&redirect=${protocol}://${req.host}${req.relativeUri}"
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
                                  if (secCalls > (quota * 10L)) {
                                    Errors
                                      .craftResponseResult("[IP] You performed too much requests",
                                                           Results.TooManyRequests,
                                                           req,
                                                           Some(descriptor),
                                                           Some("errors.too.much.requests"))
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
                                                             Some("errors.too.much.requests"))
                                        .asLeft[WSFlow]
                                    } else if (globalConfig.ipFiltering.whitelist.nonEmpty && !globalConfig.ipFiltering.whitelist
                                                 .exists(ip => utils.RegexPool(ip).matches(remoteAddress))) {
                                      Errors
                                        .craftResponseResult("Your IP address is not allowed",
                                                             Results.Forbidden,
                                                             req,
                                                             Some(descriptor),
                                                             Some("errors.ip.address.not.allowed"))
                                        .asLeft[WSFlow] // global whitelist
                                    } else if (globalConfig.ipFiltering.blacklist.nonEmpty && globalConfig.ipFiltering.blacklist
                                                 .exists(ip => utils.RegexPool(ip).matches(remoteAddress))) {
                                      Errors
                                        .craftResponseResult("Your IP address is not allowed",
                                                             Results.Forbidden,
                                                             req,
                                                             Some(descriptor),
                                                             Some("errors.ip.address.not.allowed"))
                                        .asLeft[WSFlow] // global blacklist
                                    } else if (descriptor.ipFiltering.whitelist.nonEmpty && !descriptor.ipFiltering.whitelist
                                                 .exists(ip => utils.RegexPool(ip).matches(remoteAddress))) {
                                      Errors
                                        .craftResponseResult("Your IP address is not allowed",
                                                             Results.Forbidden,
                                                             req,
                                                             Some(descriptor),
                                                             Some("errors.ip.address.not.allowed"))
                                        .asLeft[WSFlow] // service whitelist
                                    } else if (descriptor.ipFiltering.blacklist.nonEmpty && descriptor.ipFiltering.blacklist
                                                 .exists(ip => utils.RegexPool(ip).matches(remoteAddress))) {
                                      Errors
                                        .craftResponseResult("Your IP address is not allowed",
                                                             Results.Forbidden,
                                                             req,
                                                             Some(descriptor),
                                                             Some("errors.ip.address.not.allowed"))
                                        .asLeft[WSFlow] // service blacklist
                                    } else if (globalConfig.endlessIpAddresses.nonEmpty && globalConfig.endlessIpAddresses
                                                 .exists(ip => utils.RegexPool(ip).matches(remoteAddress))) {
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
                                                             Some("errors.service.in.maintenance"))
                                        .asLeft[WSFlow]
                                    } else if (descriptor.buildMode) {
                                      Errors
                                        .craftResponseResult("Service under construction",
                                                             ServiceUnavailable,
                                                             req,
                                                             Some(descriptor),
                                                             Some("errors.service.under.construction"))
                                        .asLeft[WSFlow]
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
                                          callDownstream(globalConfig)
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
                                                             Some("errors.service.down"))
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
}

object WebSocketProxyActor {
  def props(url: String,
            out: ActorRef,
            headers: Seq[(String, String)],
            descriptor: ServiceDescriptor,
            env: Env) = Props(new WebSocketProxyActor(url, out, headers, descriptor, env))
}

class WebSocketProxyActor(url: String,
                          out: ActorRef,
                          headers: Seq[(String, String)],
                          descriptor: ServiceDescriptor,
                          env: Env)
    extends Actor {

  lazy val source = Source.queue[Message](50000, OverflowStrategy.dropTail)
  lazy val logger = Logger("otoroshi-websocket-handler-actor")

  val queueRef = new AtomicReference[SourceQueueWithComplete[akka.http.scaladsl.model.ws.Message]]

  val avoid =
    Seq("Upgrade", "Connection", "Sec-WebSocket-Key", "Sec-WebSocket-Version", "Sec-WebSocket-Extensions", "Host")

  override def preStart() =
    try {
      logger.trace("[WEBSOCKET] initializing client call ...")
      val _headers = headers.toList.filterNot(t => avoid.contains(t._1)).map(tuple => RawHeader(tuple._1, tuple._2))
      val request = _headers.foldLeft[WebSocketRequest](WebSocketRequest(url))(
        (r, header) => r.copy(extraHeaders = r.extraHeaders :+ header)
      )
      val (connected, materialized) = env.gatewayClient.ws(
        request,
        Flow
          .fromSinkAndSourceMat(
            Sink.foreach[Message] {
              case msg if msg.isText =>
                logger.debug(s"[WEBSOCKET] text message from target: ${msg.asTextMessage.getStrictText}")
                out ! PlayWSTextMessage(msg.asTextMessage.getStrictText)
              case msg if !msg.isText =>
                logger.debug(s"[WEBSOCKET] binary message from target: ${msg.asBinaryMessage.getStrictData}")
                out ! PlayWSBinaryMessage(msg.asBinaryMessage.getStrictData)
            },
            source
          )(Keep.both)
          .alsoTo(Sink.onComplete { _ =>
            logger.trace(s"[WEBSOCKET] target stopped")
            Option(queueRef.get()).foreach(_.complete())
            out ! PoisonPill
          }),
        descriptor.clientConfig.proxy.orElse(env.datastores.globalConfigDataStore.latestSafe.flatMap(_.proxies.services))
          .filter(p => WSProxyServerUtils.isIgnoredForHost(Uri(url).authority.host.toString(), p.nonProxyHosts.getOrElse(Seq.empty)))
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
          a: ClientConnectionSettings => a//.withTransport(httpsProxyTransport)
        } getOrElse {
          a: ClientConnectionSettings => a
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
    case CloseMessage(status, reason) => {
      logger.debug(s"[WEBSOCKET] close message from client: $status : $reason")
      Option(queueRef.get()).foreach(_.complete())
      out ! PoisonPill
    }
    case e => logger.error(s"[WEBSOCKET] Bad message type: $e")
  }
}
