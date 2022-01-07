package otoroshi.next.proxy

import akka.Done
import akka.stream.scaladsl.Source
import akka.util.ByteString
import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.events.{Alerts, Audit, MaxConcurrentRequestReachedAlert, MaxConcurrentRequestReachedEvent}
import otoroshi.gateway.Errors
import otoroshi.models.{GlobalConfig, RemainingQuotas}
import otoroshi.next.models.{Backend, Route}
import otoroshi.next.proxy.ProxyEngineError._
import otoroshi.next.utils.FEither
import otoroshi.script.{HttpRequest, HttpResponse, RequestHandler}
import otoroshi.security.{IdGenerator, OtoroshiClaim}
import otoroshi.utils.{TypedMap, UrlSanitizer}
import otoroshi.utils.http.Implicits._
import otoroshi.utils.http.RequestImplicits._
import otoroshi.utils.http.WSCookieWithSameSite
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.http.HttpEntity
import play.api.libs.json._
import play.api.libs.ws.WSResponse
import play.api.mvc._

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicLong}
import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success

class ProxyEngine() extends RequestHandler {

  private val logger = Logger("otoroshi-next-gen-proxy-engine")
  private val fakeFailureIndicator = new AtomicBoolean(false)
  private val reqCounter  = new AtomicInteger(0)
  private val routes = TrieMap.newBuilder[String, Route]
    .+=(Route.fake.id -> Route.fake)
    .result()

  override def name: String = "Otoroshi newest proxy engine"

  override def description: Option[String] = "This plugin holds the next generation otoroshi proxy engine implementation".some

  override def configRoot: Option[String] = "NextGenProxyEngine".some

  override def defaultConfig: Option[JsObject] = {
    Json.obj(
      configRoot.get -> Json.obj(
        "enabled" -> true,
        "debug" -> false,
        "debug_headers" -> true,
        "domains" -> Json.arr()
      )
    ).some
  }

  override def handledDomains(implicit ec: ExecutionContext, env: Env): Seq[String] = {
    val config = env.datastores.globalConfigDataStore.latest().plugins.config.select(configRoot.get)
    config.select("domains").asOpt[Seq[String]].getOrElse(Seq("*-next-gen.oto.tools"))
  }

  override def handle(request: Request[Source[ByteString, _]], defaultRouting: Request[Source[ByteString, _]] => Future[Result])(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    implicit val globalConfig = env.datastores.globalConfigDataStore.latest()
    val config = globalConfig.plugins.config.select(configRoot.get).asOpt[JsObject].getOrElse(Json.obj())
    val enabled = config.select("enabled").asOpt[Boolean].getOrElse(true)
    if (enabled) {
      handleRequest(request, config)
    } else {
      defaultRouting(request)
    }
  }

  def handleRequest(request: Request[Source[ByteString, _]], config: JsObject)(implicit ec: ExecutionContext, env: Env, globalConfig: GlobalConfig): Future[Result] = {
    val requestId = IdGenerator.uuid
    implicit val report = ExecutionReport(requestId)

    val debug = config.select("debug").asOpt[Boolean].getOrElse(false)
    val debugHeaders = config.select("debug_headers").asOpt[Boolean].getOrElse(false)

    val snowflake           = env.snowflakeGenerator.nextIdStr()
    val callDate            = DateTime.now()
    val requestTimestamp    = callDate.toString("yyyy-MM-dd'T'HH:mm:ss.SSSZZ")
    val reqNumber           = reqCounter.incrementAndGet()
    val remoteAddress       = request.theIpAddress
    val isSecured           = request.theSecured
    val from                = request.theIpAddress
    val counterIn           = new AtomicLong(0L)
    val counterOut          = new AtomicLong(0L)
    val start               = System.currentTimeMillis()
    val bodyAlreadyConsumed = new AtomicBoolean(false)
    val protocol            = request.theProtocol
    implicit val attrs      = TypedMap.empty.put(
      otoroshi.plugins.Keys.RequestNumberKey     -> reqNumber,
      otoroshi.plugins.Keys.SnowFlakeKey         -> snowflake,
      otoroshi.plugins.Keys.RequestTimestampKey  -> callDate,
      otoroshi.plugins.Keys.RequestStartKey      -> start,
      otoroshi.plugins.Keys.RequestWebsocketKey  -> false,
      otoroshi.plugins.Keys.RequestCounterInKey  -> counterIn,
      otoroshi.plugins.Keys.RequestCounterOutKey -> counterOut
    )

    val elCtx: Map[String, String] = Map(
      "requestId"        -> snowflake,
      "requestSnowflake" -> snowflake,
      "requestTimestamp" -> requestTimestamp
    )

    attrs.put(otoroshi.plugins.Keys.ElCtxKey -> elCtx)

    report.start("check-concurrent-requests")
    (for {
      _               <- handleConcurrentRequest(request)
      _               =  report.markDoneAndStart("find-route")
      route           <- findRoute(request)
      _               =  report.markDoneAndStart("tenant-check", Json.obj("found_route" -> route.json).some)
      _               <- handleTenantCheck(route)
      _               =  report.markDoneAndStart("check-global-maintenance")
      _               <- checkGlobalMaintenance(route)
      _               =  report.markDoneAndStart("call-before-request-callbacks")
      _               <- callPluginsBeforeRequestCallback(request, route)
      _               =  report.markDoneAndStart("call-pre-route-plugins")
      _               <- callPreRoutePlugins(request, route)
      // TODO: handle tcp/udp tunneling if not possible as plugin
      _               =  report.markDoneAndStart("call-access-validator-plugins")
      _               <- callAccessValidatorPlugins(request, route)
      _               =  report.markDoneAndStart("enforce-global-limits")
      remQuotas       <- checkGlobalLimits(request, route) // generic.scala (1269)
      _               =  report.markDoneAndStart("choose-backend", Json.obj("remaining_quotas" -> remQuotas.toJson).some)
      result          <- callTarget(request, route) { backend =>
        report.markDoneAndStart("check-high-overhead")
        for {
          _             <- handleHighOverhead(request, route)
          _             =  report.markDoneAndStart("transform-requests")
          finalRequest  <- callRequestTransformer(request, route, backend)
          _             =  report.markDoneAndStart("transform-request-body")
          _             =  report.markDoneAndStart("call-backend")
          response      <- callBackend(request, finalRequest, route, backend)
          _             =  report.markDoneAndStart("transform-response")
          finalResp     <- callResponseTransformer(request, response, remQuotas, route, backend)
          _             =  report.markDoneAndStart("transform-response-body")
          _             =  report.markDoneAndStart("stream-response")
          clientResp    <- streamResponse(request, response, finalResp, route, backend)
          _             =  report.markDoneAndStart("call-after-request-callbacks")
          // TODO: call after callback when shortcircuited too
          _             <- callPluginsAfterRequestCallback(request, route)
          _             =  report.markDoneAndStart("trigger-analytics")
          _             <- triggerProxyDone(request, response, route, backend)
        } yield clientResp
      }
    } yield {
      result
    }).value.flatMap {
      case Left(error)   =>
        report.markDoneAndStart("rendering intermediate result").markSuccess()
        error.asResult()
      case Right(result) =>
        report.markSuccess()
        result.future
    }.recover {
      case t: Throwable =>
        report.markFailure("last-recover", t)
        Results.InternalServerError(Json.obj("error" -> "internal_server_error", "error_description" -> t.getMessage, "report" -> report.json))
    }.andThen {
      case _ =>
        report.markOverheadOut()
        if (debug) logger.info(report.json.prettify)
        // TODO: send to analytics if debug activated on route
    }.map { res =>
      val addHeaders = if (debugHeaders) Seq(
        "x-otoroshi-request-overhead-in" -> report.overheadIn.toString,
        "x-otoroshi-request-overhead-out" -> report.overheadOut.toString,
        "x-otoroshi-request-duration" -> report.gduration.toString,
        "x-otoroshi-request-call-duration" -> report.getStep("call-backend").map(_.duration).getOrElse(-1L).toString,
        "x-otoroshi-request-state" -> report.state.name,
        "x-otoroshi-request-creation" -> report.creation.toString,
        "x-otoroshi-request-termination" -> report.termination.toString,
      ).applyOnIf(report.state == ExecutionReportState.Failed) { seq =>
        seq :+ (
          "x-otoroshi-request-failure" ->
            report.getStep("request-failure").flatMap(_.ctx.select("error").select("message").asOpt[String]).getOrElse("--")
        )
      } else Seq.empty
      res.withHeaders(addHeaders: _*)
    }
  }

  def handleHighOverhead(value: Request[Source[ByteString, _]], route: Route)(implicit ec: ExecutionContext, env: Env): FEither[ProxyEngineError, Done] = {
    // TODO: handle high overhead alerting
    FEither.right(Done)
  }

  def handleConcurrentRequest(request: RequestHeader)(implicit ec: ExecutionContext, env: Env, globalConfig: GlobalConfig, attrs: TypedMap): FEither[ProxyEngineError, Done] = {
    val currentHandledRequests = env.datastores.requestsDataStore.incrementHandledRequests()
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
      FEither.apply(Errors
        .craftResponseResult(
          s"Cannot process more request",
          Results.TooManyRequests,
          request,
          None,
          Some("errors.cant.process.more.request"),
          attrs = attrs
        )
        .map(r => Left(ResultProxyEngineError(r))))
    } else {
      FEither.right(Done)
    }
  }

  def handleTenantCheck(route: Route)(implicit ec: ExecutionContext, env: Env, report: ExecutionReport): FEither[ProxyEngineError, Done] = {
    if (env.clusterConfig.mode.isWorker
        && env.clusterConfig.worker.tenants.nonEmpty
        && !env.clusterConfig.worker.tenants.contains(route.location.tenant)) {
      report.markFailure(s"this worker cannot serve tenant '${route.location.tenant.value}'")
      FEither.left(ResultProxyEngineError(Results.NotFound(Json.obj("error" -> "not_found", "error_description" -> "no route found !"))))
    } else {
      FEither.right(Done)
    }
  }

  def findRoute(request: Request[Source[ByteString, _]])(implicit ec: ExecutionContext, env: Env, report: ExecutionReport): FEither[ProxyEngineError, Route] = {
    // TODO: we need something smarter, sort paths by length when there is a wildcard, then same for domains. We need to aggregate on domains
    routes.values.find(r => r.matches(request)) match {
      case Some(route) => FEither.right(route)
      case None =>
        report.markFailure(s"route not found for domain: '${request.theDomain}${request.thePath}'")
        FEither.left(ResultProxyEngineError(Results.NotFound(Json.obj("error" -> "not_found", "error_description" -> "no route found !"))))
    }
  }

  def checkGlobalMaintenance(route: Route)(implicit ec: ExecutionContext, env: Env, report: ExecutionReport, globalConfig: GlobalConfig): FEither[ProxyEngineError, Done] = {
    if (route.id != env.backOfficeServiceId && globalConfig.maintenanceMode) {
      report.markFailure(s"global maintenance activated")
      FEither.left(ResultProxyEngineError(Results.ServiceUnavailable(Json.obj("error" -> "service_unavailable", "error_description" -> "Service in maintenance mode"))))
    } else {
      FEither.right(Done)
    }
  }

  def callPluginsBeforeRequestCallback(request: Request[Source[ByteString, _]], route: Route)(implicit ec: ExecutionContext, env: Env): FEither[ProxyEngineError, Done] = {
    // TODO: implements
    FEither.right(Done)
  }
  def callPluginsAfterRequestCallback(request: Request[Source[ByteString, _]], route: Route)(implicit ec: ExecutionContext, env: Env): FEither[ProxyEngineError, Done] =  {
    // TODO: implements
    FEither.right(Done)
  }
  def callPreRoutePlugins(request: Request[Source[ByteString, _]], route: Route)(implicit ec: ExecutionContext, env: Env): FEither[ProxyEngineError, Done] = {
    // TODO: implements
    FEither.right(Done)
  }
  def callAccessValidatorPlugins(request: Request[Source[ByteString, _]], route: Route)(implicit ec: ExecutionContext, env: Env): FEither[ProxyEngineError, Done] = {
    // TODO: implements
    FEither.right(Done)
  }
  def checkGlobalLimits(request: Request[Source[ByteString, _]], route: Route) (implicit ec: ExecutionContext, env: Env): FEither[ProxyEngineError, RemainingQuotas] = {
    // TODO: implements
    FEither.right(RemainingQuotas())
  }
  def callTarget(request: Request[Source[ByteString, _]], route: Route)(f: Backend => FEither[ProxyEngineError, Result])(implicit ec: ExecutionContext, env: Env): FEither[ProxyEngineError, Result] = {
    // TODO: implements
    // TODO: handle circuit breaker and target stuff
    val backend = route.backend.backends.head
    f(backend)
  }
  def maybeStrippedUri(req: RequestHeader, rawUri: String, route: Route): String = {
    val allPaths = route.frontend.domains.map(_.path)
    val root        = req.relativeUri
    val rootMatched = allPaths match { //rootMatched was this.matchingRoot
      case ps if ps.isEmpty => None
      case ps               => ps.find(p => root.startsWith(p))
    }
    rootMatched
      .filter(m => route.frontend.stripPath && root.startsWith(m))
      .map(m => root.replaceFirst(m.replace(".", "\\."), ""))
      .getOrElse(rawUri)
  }
  def callRequestTransformer(request: Request[Source[ByteString, _]], route: Route, backend: Backend)(implicit ec: ExecutionContext, env: Env): FEither[ProxyEngineError, HttpRequest] = {
    // TODO: implements
    val wsCookiesIn = request.cookies.toSeq.map(c =>
      WSCookieWithSameSite(
        name = c.name,
        value = c.value,
        domain = c.domain,
        path = Option(c.path),
        maxAge = c.maxAge.map(_.toLong),
        secure = c.secure,
        httpOnly = c.httpOnly,
        sameSite = c.sameSite
      )
    )
    val target = backend.toTarget
    val root   = route.backend.root
    val rawUri = request.relativeUri.substring(1)
    val uri    = maybeStrippedUri(request, rawUri, route)
    FEither.right(HttpRequest(
      // url = s"${request.theProtocol}://${request.theHost}${request.relativeUri}",
      url = s"${target.scheme}://${target.host}$root$uri",
      method = request.method,
      headers = request.headers.toSimpleMap - "Host" - "host" + ("Host" -> backend.hostname), // TODO: this will be a plugin
      cookies = wsCookiesIn,
      version = request.version,
      clientCertificateChain = request.clientCertificateChain,
      target = backend.toTarget.some,
      claims = OtoroshiClaim( // TODO: change
        iss = "otoroshi",
        sub = "route",
        aud = "client",
        exp = DateTime.now().plusSeconds(30).getMillis,
        iat = DateTime.now().getMillis,
        jti = IdGenerator.uuid,
      ),
      body = () => request.body
    ))
  }
  def callBackend(rawRequest: Request[Source[ByteString, _]], request : HttpRequest, route: Route, backend: Backend)(implicit ec: ExecutionContext, env: Env, report: ExecutionReport, globalConfig: GlobalConfig): FEither[ProxyEngineError, WSResponse] = {
    // TODO: implements
    val currentReqHasBody = rawRequest.theHasBody
    val wsCookiesIn = request.cookies
    val clientReq = env.gatewayClient.urlWithTarget(
      UrlSanitizer.sanitize(request.url),
      backend.toTarget,
      route.client
    )
    val extractedTimeout = route.client.extractTimeout(rawRequest.relativeUri, _.callAndStreamTimeout, _.callAndStreamTimeout)
    val builder          = clientReq
      .withRequestTimeout(extractedTimeout)
      .withFailureIndicator(fakeFailureIndicator)
      .withMethod(request.method)
      .withHttpHeaders(request.headers.filterNot(_._1 == "Cookie").toSeq: _*)
      .withCookies(wsCookiesIn: _*)
      .withFollowRedirects(false)
      .withMaybeProxyServer(
        route.client.proxy.orElse(globalConfig.proxies.services)
      )

    // because writeableOf_WsBody always add a 'Content-Type: application/octet-stream' header
    val builderWithBody = if (currentReqHasBody) {
      builder.withBody(rawRequest.body)
    } else {
      builder
    }

    report.markOverheadIn()
    FEither.fright(builderWithBody.stream().andThen {
      case Success(_) => report.startOverheadOut()
    })
  }
  def callResponseTransformer(rawRequest: Request[Source[ByteString, _]], response: WSResponse, quotas: RemainingQuotas, route: Route, backend: Backend)(implicit ec: ExecutionContext, env: Env): FEither[ProxyEngineError, HttpResponse] = {
    // TODO: implements
    FEither.right(HttpResponse(
      status = response.status,
      headers = response.headers.mapValues(_.head),
      cookies = response.cookies,
      body = () => response.bodyAsSource
    ))
  }
  def streamResponse(rawRequest: Request[Source[ByteString, _]], rawResponse: WSResponse, response: HttpResponse, route: Route, backend: Backend)(implicit ec: ExecutionContext, env: Env, report: ExecutionReport): FEither[ProxyEngineError, Result] = {
    // TODO: missing http/1.0 handling, chunked handling
    val contentType: Option[String] = response.headers
      .get("Content-Type")
      .orElse(response.headers.get("content-type"))
    val contentLength: Option[Long] = response.headers
      .get("Content-Length")
      .orElse(response.headers.get("content-length"))
      .orElse(rawResponse.contentLengthStr)
      .map(_.toLong)
    val cookies = response.cookies.map {
      case c: WSCookieWithSameSite =>
        Cookie(
          name = c.name,
          value = c.value,
          maxAge = c.maxAge.map(_.toInt),
          path = c.path.getOrElse("/"),
          domain = c.domain,
          secure = c.secure,
          httpOnly = c.httpOnly,
          sameSite = c.sameSite
        )
      case c                       => {
        val sameSite: Option[Cookie.SameSite] = rawResponse.headers.get("Set-Cookie").flatMap { values =>
          values
            .find { sc =>
              sc.startsWith(s"${c.name}=${c.value}")
            }
            .flatMap { sc =>
              sc.split(";")
                .map(_.trim)
                .find(p => p.toLowerCase.startsWith("samesite="))
                .map(_.replace("samesite=", "").replace("SameSite=", ""))
                .flatMap(Cookie.SameSite.parse)
            }
        }
        Cookie(
          name = c.name,
          value = c.value,
          maxAge = c.maxAge.map(_.toInt),
          path = c.path.getOrElse("/"),
          domain = c.domain,
          secure = c.secure,
          httpOnly = c.httpOnly,
          sameSite = sameSite
        )
      }
    }
    val res = Results.Status(response.status)
      .sendEntity(
        HttpEntity.Streamed(
          response.body(),
          contentLength,
          contentType
        )
      )
      .withHeaders(
        response.headers.filterNot { h =>
          val lower = h._1.toLowerCase()
          lower == "content-type" || lower == "set-cookie" || lower == "transfer-encoding" || lower == "content-length"
        }.toSeq: _*
      )
      .withCookies(cookies: _*)
    contentType match {
      case None      => FEither.right(res)
      case Some(ctp) => FEither.right(res.as(ctp))
    }
  }
  def triggerProxyDone(rawRequest: Request[Source[ByteString, _]], rawResponse: WSResponse, route: Route, backend: Backend)(implicit ec: ExecutionContext, env: Env): FEither[ProxyEngineError, Done] = {
    // TODO: implements
    FEither.right(Done)
  }
}