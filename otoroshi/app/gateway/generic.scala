package gateway

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicLong}

import akka.actor.{ActorRef, Scheduler}
import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.util.FastFuture._
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.auth0.jwt.JWT
import env.{Env, SidecarConfig}
import events._
import models._
import org.joda.time.DateTime
import otoroshi.el.HeadersExpressionLanguage
import otoroshi.script.Implicits._
import otoroshi.script._
import play.api.Logger
import play.api.http.HttpEntity
import play.api.mvc.Results._
import play.api.mvc.{Cookie, RequestHeader, Result, Results}
import security.IdGenerator
import utils.RequestImplicits._
import utils._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object ReverseProxyActionHelper {

  def splitToCanary(desc: ServiceDescriptor, trackingId: String, reqNumber: Int, config: GlobalConfig)(implicit ec: ExecutionContext, env: Env): Future[ServiceDescriptor] = {
    if (desc.canary.enabled) {
      env.datastores.canaryDataStore.isCanary(desc.id, trackingId, desc.canary.traffic, reqNumber, config).fast.map {
        case false => desc
        case true  => desc.copy(targets = desc.canary.targets, root = desc.canary.root)
      }
    } else {
      FastFuture.successful(desc)
    }
  }

  def applyJwtVerifier[A](
                        service: ServiceDescriptor,
                        req: RequestHeader,
                        apiKey: Option[ApiKey],
                        paUsr: Option[PrivateAppsUser],
                        elContext: Map[String, String],
                        attrs: TypedMap,
                        logger: Logger
                      )(f: JwtInjection => Future[Either[Result, A]])(implicit ec: ExecutionContext, env: Env): Future[Either[Result, A]] = {
    if (service.jwtVerifier.enabled) {
      service.jwtVerifier.shouldBeVerified(req.path).flatMap {
        case false => f(JwtInjection())
        case true => {
          logger.debug(s"Applying JWT verification for service ${service.id}:${service.name}")
          service.jwtVerifier.verifyGen[A](req, service, apiKey, paUsr, elContext, attrs)(f)
        }
      }
    } else {
      f(JwtInjection())
    }
  }

  def applySidecar[A](service: ServiceDescriptor, remoteAddress: String, req: RequestHeader, attrs: TypedMap, logger: Logger)(
    f: ServiceDescriptor => Future[Either[Result, A]]
  )(implicit ec: ExecutionContext, env: Env): Future[Either[Result, A]] = {
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
        ).map(Left.apply)
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
            ).map(Left.apply)
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

  def passWithHeadersVerification[A](desc: ServiceDescriptor,
                                  req: RequestHeader,
                                  apiKey: Option[ApiKey],
                                  paUsr: Option[PrivateAppsUser],
                                  ctx: Map[String, String],
                                  attrs: TypedMap)(f: => Future[Either[Result, A]])(implicit ec: ExecutionContext, env: Env): Future[Either[Result, A]] = {
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
          ).map(Left.apply)
        case None => f
      }
    }
  }

  def passWithReadOnly[A](readOnly: Boolean, req: RequestHeader, attrs: TypedMap)(f: => Future[Either[Result, A]])(implicit ec: ExecutionContext, env: Env): Future[Either[Result, A]] = {
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
            ).map(Left.apply)
        }
    }
  }

  def stateRespValid(stateValue: String,
                     stateResp: Option[String],
                     jti: String,
                     descriptor: ServiceDescriptor)(implicit ec: ExecutionContext, env: Env): Boolean = {
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

  def passWithTcpUdpTunneling[A](req: RequestHeader, desc: ServiceDescriptor, attrs: TypedMap)(
    f: => Future[Either[Result, A]]
  )(implicit ec: ExecutionContext, env: Env): Future[Either[Result, A]] = {
    if (desc.isPrivate) {
      PrivateAppsUserHelper.isPrivateAppsSessionValid(req, desc, attrs).flatMap {
        case None => f
        case Some(user) => {
          if (desc.tcpUdpTunneling) {
            req.getQueryString("redirect") match {
              case Some("urn:ietf:wg:oauth:2.0:oob") =>
                FastFuture.successful(Ok(views.html.otoroshi.token(env.signPrivateSessionId(user.randomId), env))).map(Left.apply)
              case _ =>
                Errors
                  .craftResponseResult(s"Resource not found",
                    NotFound,
                    req,
                    None,
                    Some("errors.resource.not.found"),
                    attrs = attrs).map(Left.apply)
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
            attrs = attrs).map(Left.apply)
      } else {
        f
      }
    }
  }
}


case class ReverseProxyActionContext(req: RequestHeader, requestBody: Source[ByteString, _], snowMonkey: SnowMonkey, logger: Logger)

case class ActualCallContext(req: RequestHeader,
                             descriptor: ServiceDescriptor,
                             _target: Target,
                             apiKey: Option[ApiKey] = None,
                             paUsr: Option[PrivateAppsUser] = None,
                             jwtInjection: JwtInjection,
                             snowMonkeyContext: SnowMonkeyContext,
                             snowflake: String,
                             attrs: TypedMap,
                             elCtx: Map[String, String],
                             globalConfig: GlobalConfig,
                             withTrackingCookies: Seq[Cookie],
                             bodyAlreadyConsumed: AtomicBoolean,
                             requestBody: Source[ByteString, _],
                             secondStart: Long,
                             firstOverhead: Long,
                             cbDuration: Long,
                             callAttempts: Int)

class ReverseProxyAction(env: Env) {

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

  val reqCounter = new AtomicInteger(0)

  def async[A](
    ctx: ReverseProxyActionContext,
    _actuallyCallDownstream: ActualCallContext => Future[Either[Result, A]]
  )(implicit ec: ExecutionContext, mat: Materializer, scheduler: Scheduler, env: Env): Future[Either[Result, A]] = {

    val ReverseProxyActionContext(req, requestBody, snowMonkey, logger) = ctx

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
      otoroshi.plugins.Keys.RequestWebsocketKey -> false,
      otoroshi.plugins.Keys.RequestCounterIn -> counterIn,
      otoroshi.plugins.Keys.RequestCounterOut-> counterOut
    )

    val elCtx: Map[String, String] = Map(
      "requestId"        -> snowflake,
      "requestSnowflake" -> snowflake,
      "requestTimestamp" -> requestTimestamp
    )

    attrs.put(otoroshi.plugins.Keys.ElCtxKey -> elCtx)

    val currentHandledRequests = env.datastores.requestsDataStore.incrementHandledRequests()
    val globalConfig = env.datastores.globalConfigDataStore.latest()

    val finalResult: Future[Either[Result, A]] = {
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
          attrs = attrs).map(Left.apply)
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
              err).map(Left.apply)

          case Some(ServiceLocation(domain, serviceEnv, subdomain)) => {
            val uriParts = req.relativeUri.split("/").toSeq

            env.datastores.serviceDescriptorDataStore
              .find(ServiceDescriptorQuery(subdomain, serviceEnv, domain, req.relativeUri, req.headers.toSimpleMap),
                req, attrs)
              .fast
              .flatMap {
                case None =>
                  val err = Errors
                    .craftResponseResult(s"Service not found",
                      NotFound,
                      req,
                      None,
                      Some("errors.service.not.found"),
                      attrs = attrs)
                  RequestSink
                    .maybeSinkRequest(snowflake, req, attrs, RequestOrigin.ReverseProxy, 404, s"Service not found", err).map(Left.apply)
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
                    err).map(Left.apply)
                case Some(rawDesc) if rawDesc.redirection.enabled && rawDesc.redirection.hasValidCode => {
                  // TODO: event here
                  FastFuture.successful(
                    Results
                      .Status(rawDesc.redirection.code)
                      .withHeaders("Location" -> rawDesc.redirection.formattedTo(req, rawDesc, elCtx, attrs, env))
                  ).map(Left.apply)
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
                    ).map(Left.apply)
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
                        rawDesc.preRouteGen[A](snowflake, req, attrs) {
                          ReverseProxyActionHelper.passWithTcpUdpTunneling(req, rawDesc, attrs) {
                            ReverseProxyActionHelper.passWithHeadersVerification(rawDesc, req, None, None, elCtx, attrs) {
                              ReverseProxyActionHelper.passWithReadOnly(rawDesc.readOnly, req, attrs) {
                                ReverseProxyActionHelper.applySidecar(rawDesc, remoteAddress, req, attrs, logger) { desc =>
                                  val firstOverhead = System.currentTimeMillis() - start
                                  snowMonkey.introduceChaosGen[A](reqNumber, globalConfig, desc, req.theHasBody) {
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
                                      attrs.put(otoroshi.plugins.Keys.RequestCanaryId -> canaryId)

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

                                      ReverseProxyActionHelper.splitToCanary(desc, canaryId, reqNumber, globalConfig).fast.flatMap { _desc =>
                                        val isUp = true

                                        val descriptor = _desc

                                        def actuallyCallDownstream(t: Target, apiKey: Option[ApiKey], paUsr: Option[PrivateAppsUser], cbDuration: Long, callAttempts: Int): Future[Either[Result, A]] = {
                                          ReverseProxyActionHelper.applyJwtVerifier(rawDesc, req, apiKey, paUsr, elCtx, attrs, logger) { jwtInjection =>
                                            _actuallyCallDownstream(
                                              ActualCallContext(
                                                req = req,
                                                descriptor = descriptor,
                                                _target = t,
                                                apiKey = apiKey,
                                                paUsr = paUsr,
                                                jwtInjection = jwtInjection,
                                                snowMonkeyContext = snowMonkeyContext,
                                                snowflake = snowflake,
                                                attrs = attrs,
                                                elCtx = elCtx,
                                                globalConfig = globalConfig,
                                                cbDuration = cbDuration,
                                                callAttempts = callAttempts,
                                                withTrackingCookies = withTrackingCookies,
                                                firstOverhead = firstOverhead,
                                                secondStart = secondStart,
                                                bodyAlreadyConsumed = bodyAlreadyConsumed,
                                                requestBody = requestBody
                                              )
                                            )
                                          }
                                        }

                                        def callDownstream(config: GlobalConfig,
                                                           _apiKey: Option[ApiKey] = None,
                                                           _paUsr: Option[PrivateAppsUser] = None): Future[Either[Result, A]] = {

                                          val apiKey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey).orElse(_apiKey)
                                          val paUsr  = attrs.get(otoroshi.plugins.Keys.UserKey).orElse(_paUsr)

                                          apiKey
                                            .foreach(apk => attrs.putIfAbsent(otoroshi.plugins.Keys.ApiKeyKey  -> apk))
                                          paUsr.foreach(usr => attrs.putIfAbsent(otoroshi.plugins.Keys.UserKey -> usr))

                                          desc
                                            .validateClientCertificatesGen[A](snowflake, req, apiKey, paUsr, config, attrs) {
                                              ReverseProxyActionHelper.passWithReadOnly(apiKey.map(_.readOnly).getOrElse(false), req, attrs) {
                                                if (config.useCircuitBreakers && descriptor.clientConfig.useCircuitBreaker) {
                                                  val cbStart = System.currentTimeMillis()
                                                  val counter = new AtomicInteger(0)
                                                  val relUri  = req.relativeUri
                                                  val cachedPath: String =
                                                    descriptor.clientConfig
                                                      .timeouts(relUri)
                                                      .map(_ => relUri)
                                                      .getOrElse("")

                                                  def callF(t: Target, attemps: Int): Future[Either[Result, A]] = {
                                                    actuallyCallDownstream(t,
                                                      apiKey,
                                                      paUsr,
                                                      System.currentTimeMillis - cbStart,
                                                      counter.get())
                                                  }

                                                  env.circuitBeakersHolder
                                                    .get(desc.id + cachedPath, () => new ServiceDescriptorCircuitBreaker())
                                                    .callGen[A](
                                                      descriptor,
                                                      reqNumber.toString,
                                                      trackingId,
                                                      req.relativeUri,
                                                      req,
                                                      bodyAlreadyConsumed,
                                                      s"${req.method} ${req.relativeUri}",
                                                      counter,
                                                      attrs,
                                                      callF
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
                                                      ).map(Left.apply)
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
                                                      ).map(Left.apply)
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
                                                      ).map(Left.apply)
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
                                                      ).map(Left.apply)
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
                                                      ).map(Left.apply)
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
                                                      ).map(Left.apply)
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
                                                      ).map(Left.apply)
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

                                        def errorResult(status: Results.Status, message: String, code: String): Future[Either[Result, A]] = {
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
                                          ).map(Left.apply)
                                        }

                                        val query = ServiceDescriptorQuery(subdomain, serviceEnv, domain, "/")
                                        ReverseProxyHelper.handleRequest[A](
                                          ReverseProxyHelper.HandleRequestContext(req, query, descriptor, isUp, attrs, globalConfig, logger),
                                          callDownstream,
                                          errorResult
                                        )
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
      .withTimerAsync("otoroshi.core.proxy.handle-http-request")(finalResult) // TODO: ws name
      .andThen {
        case _ =>
          val requests = env.datastores.requestsDataStore.decrementHandledRequests()
          env.metrics.markLong(s"${env.snowflakeSeed}.concurrent-requests", requests)
      }(env.otoroshiExecutionContext)
  }
}

object ReverseProxyHelper {

  case class HandleRequestContext(req: RequestHeader, query: ServiceDescriptorQuery, descriptor: ServiceDescriptor, isUp: Boolean, attrs: TypedMap, globalConfig: GlobalConfig, logger: Logger)

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
    val isSecured = req.theSecured
    val remoteAddress = req.theIpAddress

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
            ByteString.fromInts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
              0)
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
          ).map(Left.apply)
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
            FastFuture.successful(
              Results
                .Ok(ByteString.empty)
                .withHeaders(descriptor.cors.asHeaders(req): _*)
            ).map(Left.apply)
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
