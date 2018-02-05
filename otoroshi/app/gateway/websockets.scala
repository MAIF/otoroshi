package gateway

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong, AtomicReference}

import akka.actor.{Actor, ActorRef, PoisonPill, Props}
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.ws.{Message, WebSocketRequest}
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{Flow, Keep, Sink, Source, SourceQueueWithComplete}
import akka.stream.{ActorMaterializer, Materializer, OverflowStrategy}
import akka.util.ByteString
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.google.common.base.Charsets
import env.Env
import events._
import models._
import org.joda.time.DateTime
import play.api.Logger
import play.api.http.HttpEntity
import play.api.http.websocket.{
  CloseMessage,
  BinaryMessage => PlayWSBinaryMessage,
  Message => PlayWSMessage,
  TextMessage => PlayWSTextMessage
}
import play.api.libs.streams.ActorFlow
import play.api.mvc.Results.{BadGateway, ServiceUnavailable, Status}
import play.api.mvc._
import security.{IdGenerator, OtoroshiClaim}
import utils.Metrics
import utils.future.Implicits._

import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}

class WebSocketHandler()(implicit env: Env) {

  type WSFlow = Flow[PlayWSMessage, PlayWSMessage, _]

  implicit lazy val currentEc           = env.gatewayExecutor
  implicit lazy val currentScheduler    = env.gatewayActorSystem.scheduler
  implicit lazy val currentSystem       = env.gatewayActorSystem
  implicit lazy val currentMaterializer = env.gatewayMaterializer

  lazy val logger = Logger("otoroshi-websocket-handler")

  lazy val http = akka.http.scaladsl.Http.get(env.gatewayActorSystem)

  val reqCounter = new AtomicInteger(0)

  val headersInFiltered = Seq(
    env.Headers.OtoroshiState,
    env.Headers.OtoroshiClaim,
    env.Headers.OtoroshiRequestId,
    "Host"
  ).map(_.toLowerCase)

  val headersOutFiltered = Seq(
    env.Headers.OtoroshiStateResp,
    "Transfer-Encoding"
  ).map(_.toLowerCase)

  def decodeBase64(encoded: String): String = new String(OtoroshiClaim.decoder.decode(encoded), Charsets.UTF_8)

  def isPrivateAppsSessionValid(req: RequestHeader): Future[Option[PrivateAppsUser]] =
    req.cookies.get("oto-papps").flatMap(env.extractPrivateSessionId).map { id =>
      env.datastores.privateAppsUserDataStore.findById(id)
    } getOrElse {
      FastFuture.successful(None)
    }

  def splitToCanary(desc: ServiceDescriptor, trackingId: String)(implicit env: Env): Future[ServiceDescriptor] =
    if (desc.canary.enabled) {
      env.datastores.canaryDataStore.isCanary(desc.id, trackingId, desc.canary.traffic).map {
        case false => desc
        case true  => desc.copy(targets = desc.canary.targets, root = desc.canary.root)
      }
    } else {
      FastFuture.successful(desc)
    }

  def proxyWebSocket() = WebSocket.acceptOrResult[PlayWSMessage, PlayWSMessage] { req =>
    logger.info("[WEBSOCKET] proxy ws call !!!")

    // val meterIn       = Metrics.metrics.meter("GatewayDataIn")
    val remoteAddress = req.headers.get("X-Forwarded-For").getOrElse(req.remoteAddress)
    val isSecured     = req.headers.get("X-Forwarded-Protocol").map(_ == "https").orElse(Some(req.secure)).getOrElse(false)
    val protocol = req.headers
      .get("X-Forwarded-Protocol")
      .map(_ == "https")
      .orElse(Some(req.secure))
      .map {
        case true  => "wss"
        case false => "ws"
      }
      .getOrElse("ws")
    val from       = req.headers.get("X-Forwarded-For").getOrElse(req.remoteAddress)
    val counterIn  = new AtomicLong(0L)
    val counterOut = new AtomicLong(0L)
    val start      = System.currentTimeMillis()

    env.datastores.globalConfigDataStore.singleton().flatMap { globalConfig =>
      ServiceLocation(req.host, globalConfig) match {
        case None =>
          Errors
            .craftResponseResult(s"Service not found for URL ${req.host}::${req.uri}",
                                 Results.NotFound,
                                 req,
                                 None,
                                 Some("errors.service.not.found"))
            .asLeft[WSFlow]
        case Some(ServiceLocation(domain, serviceEnv, subdomain)) => {
          val uriParts = req.uri.split("/").toSeq
          val root     = if (uriParts.isEmpty) "/" else "/" + uriParts.tail.head

          env.datastores.serviceDescriptorDataStore
            .find(ServiceDescriptorQuery(subdomain, serviceEnv, domain, root, req.headers.toSimpleMap))
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
              case Some(desc) => {

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
                val trackingId: String = maybeTrackingId.getOrElse(IdGenerator.uuid)

                if (maybeTrackingId.isDefined) {
                  logger.debug(s"request already has tracking id : $trackingId")
                } else {
                  logger.debug(s"request has a new tracking id : $trackingId")
                }

                val withTrackingCookies =
                  if (maybeTrackingId.isDefined) Seq.empty[play.api.mvc.Cookie]
                  else
                    Seq(
                      play.api.mvc.Cookie(
                        name = "oto-client",
                        value = env.sign(trackingId) + "::" + trackingId,
                        maxAge = Some(2592000),
                        path = "/",
                        domain = Some(req.host),
                        httpOnly = false
                      )
                    )

                desc.isUp.flatMap(iu => splitToCanary(desc, trackingId).map(d => (iu, d))).flatMap { tuple =>
                  val (isUp, _desc) = tuple
                  val descriptor    = if (env.redirectToDev) _desc.copy(env = "dev") else _desc

                  def callDownstream(
                      config: GlobalConfig,
                      apiKey: Option[ApiKey] = None,
                      paUsr: Option[PrivateAppsUser] = None
                  ): Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]] =
                    if (config.useCircuitBreakers && descriptor.clientConfig.useCircuitBreaker) {
                      env.circuitBeakersHolder
                        .get(desc.id, () => new ServiceDescriptorCircuitBreaker())
                        .callWS(desc, (t) => actuallyCallDownstream(t, apiKey, paUsr)) recoverWith {
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
                      val index = reqCounter.incrementAndGet() % (if (descriptor.targets.nonEmpty)
                                                                    descriptor.targets.size
                                                                  else 1)
                      // Round robin loadbalancing is happening here !!!!!
                      val target = descriptor.targets.apply(index.toInt)
                      actuallyCallDownstream(target, apiKey, paUsr)
                    }

                  def actuallyCallDownstream(
                      target: Target,
                      apiKey: Option[ApiKey] = None,
                      paUsr: Option[PrivateAppsUser] = None
                  ): Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]] = {
                    logger.info("[WEBSOCKET] Call downstream !!!")
                    val snowflake   = env.snowflakeGenerator.nextIdStr()
                    val state       = IdGenerator.extendedToken(128)
                    val rawUri      = req.uri.substring(1)
                    val uriParts    = rawUri.split("/").toSeq
                    val uri: String = if (descriptor.matchingRoot.isDefined) uriParts.tail.mkString("/") else rawUri
                    // val index = reqCounter.incrementAndGet() % (if (descriptor.targets.nonEmpty) descriptor.targets.size else 1)
                    // // Round robin loadbalancing is happening here !!!!!
                    // val target = descriptor.targets.apply(index.toInt)
                    val scheme = if (descriptor.redirectToLocal) descriptor.localScheme else target.scheme
                    val host   = if (descriptor.redirectToLocal) descriptor.localHost else target.host
                    val root   = descriptor.root
                    val url    = s"${if (target.scheme == "https") "wss" else "ws"}://$host$root$uri"
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
                    val headersIn: Seq[(String, String)] = (req.headers.toSimpleMap.toSeq
                      .filterNot(t => headersInFiltered.contains(t._1.toLowerCase)) ++ Map(
                      env.Headers.OtoroshiProxiedHost -> req.headers.get("Host").getOrElse("--"),
                      "Host"                          -> host,
                      env.Headers.OtoroshiRequestId   -> snowflake,
                      env.Headers.OtoroshiState       -> state,
                      env.Headers.OtoroshiClaim       -> claim
                    ) ++ descriptor.additionalHeaders ++ fromOtoroshi
                      .map(v => Map(env.Headers.OtoroshiGatewayParentRequest -> fromOtoroshi.get))
                      .getOrElse(Map.empty[String, String])).toSeq

                    // val requestHeader = ByteString(
                    //   req.method + " " + req.uri + " HTTP/1.1\n" + headersIn
                    //     .map(h => s"${h._1}: ${h._2}")
                    //     .mkString("\n") + "\n"
                    // )
                    // meterIn.mark(requestHeader.length)
                    // counterIn.addAndGet(requestHeader.length)
                    // logger.trace(s"curl -X ${req.method.toUpperCase()} ${headersIn.map(h => s"-H '${h._1}: ${h._2}'").mkString(" ")} '$url?${queryString.map(h => s"${h._1}=${h._2}").mkString("&")}' --include")
                    logger.info(
                      s"[WEBSOCKET] calling '$url' with headers \n ${headersIn.map(_.toString()) mkString ("\n")}"
                    )
                    val overhead = System.currentTimeMillis() - start
                    val quotas: Future[RemainingQuotas] =
                      apiKey.map(_.updateQuotas()).getOrElse(FastFuture.successful(RemainingQuotas()))
                    promise.future.andThen {
                      case Success(resp) => {
                        val duration = System.currentTimeMillis() - start
                        // logger.trace(s"[$snowflake] Call forwarded in $duration ms. with $overhead ms overhead for (${req.version}, http://${req.host}${req.uri} => $url, $from)")
                        descriptor
                          .updateMetrics(duration, overhead, counterIn.get(), counterOut.get(), 0, globalConfig)
                          .andThen {
                            case Failure(e) => logger.error("Error while updating call metrics reporting", e)
                          }
                        env.datastores.globalConfigDataStore.updateQuotas(globalConfig)
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
                    FastFuture.successful(
                      Right(
                        ActorFlow.actorRef(
                          out => WebSocketProxyActor.props(url, env.gatewayMaterializer, out, env, http, headersIn)
                        )
                      )
                    )
                  }

                  def passWithApiKey(
                      config: GlobalConfig
                  ): Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]] = {
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
                      .get(env.Headers.OtoroshiClientId)
                      .flatMap(id => req.headers.get(env.Headers.OtoroshiClientSecret).map(s => (id, s)))
                    if (authByCustomHeaders.isDefined) {
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
                    } else if (authBasic.isDefined) {
                      val auth   = authBasic.get
                      val id     = auth.split(":").headOption.map(_.trim)
                      val secret = auth.split(":").lastOption.map(_.trim)
                      (id, secret) match {
                        case (Some(apiKeyClientId), Some(apiKeySecret)) => {
                          env.datastores.apiKeyDataStore.findAuthorizeKeyFor(apiKeyClientId, descriptor.id).flatMap {
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

                  def passWithAuth0(
                      config: GlobalConfig
                  ): Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]] =
                    isPrivateAppsSessionValid(req).flatMap {
                      case Some(paUsr) => callDownstream(config, paUsr = Some(paUsr))
                      case None => {
                        val redirectTo = env.rootScheme + env.privateAppsHost + controllers.routes.Auth0Controller
                          .privateAppsLoginPage(Some(s"http://${req.host}${req.uri}"))
                          .url
                        logger.trace("should redirect to " + redirectTo)
                        FastFuture.successful(Left(Results.Redirect(redirectTo)))
                      }
                    }

                  globalConfig.withinThrottlingQuota().map(within => (globalConfig, within)) flatMap { tuple =>
                    val (globalConfig, within) = tuple
                    env.datastores.globalConfigDataStore.incrementCallsForIpAddressWithTTL(from).flatMap { secCalls =>
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
                        if (env.isProd && req.domain.endsWith(env.domain) && !isSecured) {
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
                          FastFuture.successful(Left(Results.Redirect(s"${env.rootScheme}$theDomain${req.uri}")))
                        } else if (env.isProd && !isSecured && desc.forceHttps) {
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
                          FastFuture.successful(Left(Results.Redirect(s"${env.rootScheme}$theDomain${req.uri}")))
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
                          val zeros                  = ByteString.fromInts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
                          val characters: ByteString = if (!globalConfig.middleFingers) middleFingers else zeros
                          val expected: Long         = (gigas / characters.size) + 1L
                          FastFuture.successful(
                            Left(
                              Status(200)
                                .sendEntity(
                                  HttpEntity.Streamed(
                                    Source.repeat(characters).limit(expected), // 128 Go of zeros or middle fingers
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
                          if (descriptor.isPrivate) {
                            if (descriptor.isUriPublic(req.uri)) {
                              passWithAuth0(globalConfig)
                            } else {
                              passWithApiKey(globalConfig)
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

object WebSocketProxyActor {
  def props(url: String,
            mat: Materializer,
            out: ActorRef,
            env: Env,
            http: akka.http.scaladsl.HttpExt,
            headers: Seq[(String, String)]) =
    Props(new WebSocketProxyActor(url, mat, out, env, http, headers))
}

class WebSocketProxyActor(url: String,
                          mat: Materializer,
                          out: ActorRef,
                          env: Env,
                          http: akka.http.scaladsl.HttpExt,
                          headers: Seq[(String, String)])
    extends Actor {

  lazy val source = Source.queue[Message](50000, OverflowStrategy.dropTail)
  lazy val logger = Logger("otoroshi-websocket-handler-actor")

  val queueRef = new AtomicReference[SourceQueueWithComplete[akka.http.scaladsl.model.ws.Message]]

  val avoid =
    Seq("Upgrade", "Connection", "Sec-WebSocket-Key", "Sec-WebSocket-Version", "Sec-WebSocket-Extensions", "Host")

  override def preStart() =
    try {
      logger.info("[WEBSOCKET] initializing client call ...")
      val _headers = headers.toList.filterNot(t => avoid.contains(t._1)).map(tuple => RawHeader(tuple._1, tuple._2))
      val request = _headers.foldLeft[WebSocketRequest](WebSocketRequest(url))(
        (r, header) => r.copy(extraHeaders = r.extraHeaders :+ header)
      )
      val (connected, materialized) = http.singleWebSocketRequest(
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
            logger.info(s"[WEBSOCKET] target stopped")
            Option(queueRef.get()).foreach(_.complete())
            out ! PoisonPill
          })
      )(mat)
      queueRef.set(materialized._2)
      connected.andThen {
        case Success(r) => {
          implicit val ec  = context.dispatcher
          implicit val mat = ActorMaterializer.create(context)
          logger.info(
            s"[WEBSOCKET] connected to target ${r.response.status} :: ${r.response.headers.map(h => h.toString()).mkString(", ")}"
          )
          r.response.entity.dataBytes.runFold(ByteString.empty)(_ ++ _).map { bs =>
            logger.info(s"[WEBSOCKET] connected to target with response '${bs.utf8String}'")
          }
        }
        case Failure(e) => logger.error(s"[WEBSOCKET] error", e)
      }(context.dispatcher)
    } catch {
      case e: Exception => logger.error("[WEBSOCKET] error during call", e)
    }

  override def postStop() = {
    logger.info(s"[WEBSOCKET] client stopped")
    Option(queueRef.get()).foreach(_.complete())
    out ! PoisonPill
  }

  def receive = {
    case msg: PlayWSBinaryMessage => {
      logger.debug(s"[WEBSOCKET] binary message from client: ${msg.data.utf8String}")
      Option(queueRef.get()).foreach(_.offer(akka.http.scaladsl.model.ws.BinaryMessage(msg.data)))
    }
    case msg: PlayWSTextMessage => {
      logger.debug(s"[WEBSOCKET] text message from client: $msg")
      Option(queueRef.get()).foreach(_.offer(akka.http.scaladsl.model.ws.TextMessage(msg.data)))
    }
    case CloseMessage(status, reason) => {
      Option(queueRef.get()).foreach(_.complete())
      out ! PoisonPill
    }
    case e => logger.error(s"[WEBSOCKET] Bad message type: $e")
  }
}
