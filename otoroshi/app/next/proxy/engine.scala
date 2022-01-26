package otoroshi.next.proxy

import akka.Done
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.ByteString
import com.github.blemale.scaffeine.{Cache, Scaffeine}
import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.events._
import otoroshi.gateway._
import otoroshi.models._
import otoroshi.next.models.{NgContextualPlugins, NgPlugins, NgTarget, NgRoute, NgSelectedBackendTarget}
import otoroshi.next.plugins.Keys
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError._
import otoroshi.next.utils.{FEither, JsonHelpers}
import otoroshi.script.RequestHandler
import otoroshi.security.IdGenerator
import otoroshi.utils.http.Implicits._
import otoroshi.utils.http.RequestImplicits._
import otoroshi.utils.http.WSCookieWithSameSite
import otoroshi.utils.streams.MaxLengthLimiter
import otoroshi.utils.syntax.implicits._
import otoroshi.utils.{TypedMap, UrlSanitizer}
import play.api.Logger
import play.api.http.HttpEntity
import play.api.http.websocket.{Message => PlayWSMessage}
import play.api.libs.json._
import play.api.libs.streams.ActorFlow
import play.api.libs.ws.WSResponse
import play.api.mvc.Results.{NotFound, Status}
import play.api.mvc._

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicLong, AtomicReference}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

class ProxyEngine() extends RequestHandler {

  private val logger = Logger("otoroshi-next-gen-proxy-engine")
  private val fakeFailureIndicator = new AtomicBoolean(false)
  private val reqCounter  = new AtomicInteger(0)

  private val enabledRef = new AtomicBoolean(true)
  private val enabledDomains = new AtomicReference(Seq.empty[String])

  private val configCache: Cache[String, JsObject] = Scaffeine()
    .expireAfterWrite(10.seconds)
    .maximumSize(2)
    .build()

  private val headersOutStatic = Seq(
    "Keep-Alive",
    "Transfer-Encoding",
    "Content-Length",
    "Content-Type",
    "Raw-Request-Uri",
    "Remote-Address",
    "Timeout-Access",
    "Tls-Session-Info",
    "Set-Cookie"
  )

  private val headersInStatic = Seq(
    "X-Forwarded-For",
    "X-Forwarded-Proto",
    "X-Forwarded-Protocol",
    "Raw-Request-Uri",
    "Remote-Address",
    "Timeout-Access",
    "Tls-Session-Info"
  )

  override def name: String = "Otoroshi newest proxy engine"

  override def description: Option[String] = "This plugin holds the next generation otoroshi proxy engine implementation".some

  override def configRoot: Option[String] = "NextGenProxyEngine".some

  override def defaultConfig: Option[JsObject] = {
    Json.obj(
      configRoot.get -> Json.obj(
        "enabled" -> true,
        "debug" -> false,
        "debug_headers" -> true,
        "routing_strategy" -> "tree",
        "domains" -> Json.arr()
      )
    ).some
  }

  @inline
  private def getConfig()(implicit ec: ExecutionContext, env: Env): JsObject = {
    configCache.get("config", key => {
      val config = env.datastores.globalConfigDataStore.latest().plugins.config.select(configRoot.get).asObject
      val enabled = config.select("enabled").asOpt[Boolean].getOrElse(true)
      val domains = if (enabled) config.select("domains").asOpt[Seq[String]].getOrElse(Seq("*-next-gen.oto.tools")) else Seq.empty[String]
      enabledRef.set(enabled)
      enabledDomains.set(domains)
      config
    })
  }

  override def handledDomains(implicit ec: ExecutionContext, env: Env): Seq[String] = {
    val config = getConfig()
    enabledDomains.get()
  }

  override def handle(request: Request[Source[ByteString, _]], defaultRouting: Request[Source[ByteString, _]] => Future[Result])(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    implicit val globalConfig = env.datastores.globalConfigDataStore.latest()
    val config = getConfig()
    if (enabledRef.get()) {
      handleRequest(request, config)
    } else {
      defaultRouting(request)
    }
  }

  override def handleWs(request: RequestHeader, defaultRouting: RequestHeader => Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]])(implicit ec: ExecutionContext, env: Env): Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]] = {
    implicit val globalConfig = env.datastores.globalConfigDataStore.latest()
    val config = getConfig()
    if (enabledRef.get()) {
      handleWsRequest(request, config)
    } else {
      defaultRouting(request)
    }
  }

  @inline
  def handleRequest(request: Request[Source[ByteString, _]], config: JsObject)(implicit ec: ExecutionContext, env: Env, globalConfig: GlobalConfig): Future[Result] = {
    val requestId = IdGenerator.uuid
    val reporting = config.select("reporting").asOpt[Boolean].getOrElse(true)
    val useTree = config.select("routing_strategy").asOpt[String].contains("tree")
    implicit val report = NgExecutionReport(requestId, reporting)
    report.start("start-handling")
    val debug = config.select("debug").asOpt[Boolean].getOrElse(false)
    val debugHeaders = config.select("debug_headers").asOpt[Boolean].getOrElse(false)
    implicit val mat = env.otoroshiMaterializer

    val snowflake           = env.snowflakeGenerator.nextIdStr()
    val callDate            = DateTime.now()
    val requestTimestamp    = callDate.toString("yyyy-MM-dd'T'HH:mm:ss.SSSZZ")
    val reqNumber           = reqCounter.incrementAndGet()
    val counterIn           = new AtomicLong(0L)
    val counterOut          = new AtomicLong(0L)
    val start               = System.currentTimeMillis()
    implicit val attrs      = TypedMap.empty.put(
      otoroshi.next.plugins.Keys.ReportKey       -> report,
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
    val global_plugins__ = NgPlugins.readFrom(globalConfig.plugins.config.select("ng"))

    report.markDoneAndStart("check-concurrent-requests")
    (for {
      _               <- handleConcurrentRequest(request)
      _               =  report.markDoneAndStart("find-route")
      route           <- findRoute(useTree, request, request.body, global_plugins__)
      ctxPlugins      = route.contextualPlugins(global_plugins__, request)
      _               = attrs.put(Keys.ContextualPluginsKey -> ctxPlugins)
      _               =  report.markDoneAndStart("tenant-check", Json.obj("found_route" -> route.json).some)
      _               <- handleTenantCheck(route)
      _               =  report.markDoneAndStart("check-global-maintenance")
      _               <- checkGlobalMaintenance(route)
      _               =  report.markDoneAndStart("call-before-request-callbacks")
      _               <- callPluginsBeforeRequestCallback(snowflake, request, route, ctxPlugins)
      _               =  report.markDoneAndStart("extract-tracking-id")
      _               =  extractTrackingId(snowflake, request, reqNumber, route)
      _               =  report.markDoneAndStart("call-pre-route-plugins")
      _               <- callPreRoutePlugins(snowflake, request, route, ctxPlugins)
      _               =  report.markDoneAndStart("call-access-validator-plugins")
      _               <- callAccessValidatorPlugins(snowflake, request, route, ctxPlugins)
      _               =  report.markDoneAndStart("enforce-global-limits")
      remQuotas       <- checkGlobalLimits(request, route) // generic.scala (1269)
      _               =  report.markDoneAndStart("choose-backend", Json.obj("remaining_quotas" -> remQuotas.toJson).some)
      result          <- callTarget(snowflake, reqNumber, request, route) {
        case sb @ NgSelectedBackendTarget(backend, attempts, alreadyFailed, cbStart) =>
          report.markDoneAndStart("transform-requests", Json.obj("backend" -> backend.json).some)
          for {
            finalRequest  <- callRequestTransformer(snowflake, request, request.body, route, backend, ctxPlugins)
            _             =  report.markDoneAndStart("call-backend")
            response      <- callBackend(snowflake, request, finalRequest, route, backend)
            _             =  report.markDoneAndStart("transform-response")
            finalResp     <- callResponseTransformer(snowflake, request, response, remQuotas, route, backend, ctxPlugins)
            _             =  report.markDoneAndStart("stream-response")
            clientResp    <- streamResponse(snowflake, request, response, finalResp, route, backend)
            _             =  report.markDoneAndStart("trigger-analytics")
            _             <- triggerProxyDone(snowflake, request, response, finalRequest, finalResp, route, backend, sb)
          } yield clientResp
      }
    } yield {
      result
    }).value.flatMap {
      case Left(error)   =>
        report.markDoneAndStart("rendering-intermediate-result").markSuccess()
        error.asResult()
      case Right(result) =>
        report.markSuccess()
        result.vfuture
    }.recover {
      case t: Throwable =>
        report.markFailure("last-recover", t)
        Results.InternalServerError(Json.obj("error" -> "internal_server_error", "error_description" -> t.getMessage, "report" -> report.json))
    }.applyOnWithOpt(attrs.get(Keys.ResultTransformerKey)) {
      case (future, transformer) => future.flatMap(transformer)
    }.andThen {
      case _ =>
        report.markOverheadOut()
        report.markDurations()
        attrs.get(Keys.RouteKey).foreach { route =>
          callPluginsAfterRequestCallback(snowflake, request, route, attrs.get(Keys.ContextualPluginsKey).get)
          handleHighOverhead(request, route.some)
          RequestFlowReport(report, route).toAnalytics()
        }
    }.applyOnIf(/*env.env == "dev" && */(debug || debugHeaders))(_.map { res =>
      val addHeaders = if (reporting && debugHeaders) Seq(
        "x-otoroshi-request-overhead" -> (report.overheadIn + report.overheadOut).toString,
        "x-otoroshi-request-overhead-in" -> report.overheadIn.toString,
        "x-otoroshi-request-overhead-out" -> report.overheadOut.toString,
        "x-otoroshi-request-duration" -> report.gduration.toString,
        "x-otoroshi-request-call-duration" -> report.getStep("call-backend").map(_.duration).getOrElse(-1L).toString,
        "x-otoroshi-request-find-route-duration" -> {
          val route = report.getStep("find-route")
          route
            .map(_.duration).filter(_ > 0).map(v => s"$v millis")
            .orElse(route.map(_.duration_ns).map(v => s"$v nanos"))
            .getOrElse("--")
        },
        "x-otoroshi-request-state" -> report.state.name,
        "x-otoroshi-request-creation" -> report.creation.toString,
        "x-otoroshi-request-termination" -> report.termination.toString,
      ).applyOnIf(report.state == NgExecutionReportState.Failed) { seq =>
        seq :+ (
          "x-otoroshi-request-failure" ->
            report.getStep("request-failure").flatMap(_.ctx.select("error").select("message").asOpt[String]).getOrElse("--")
        )
      } else Seq.empty
      if (debug) logger.info(report.json.prettify)
      // if (reporting && report.getStep("find-route").flatMap(_.ctx.select("found_route").select("debug_flow").asOpt[Boolean]).getOrElse(false)) {
      //   java.nio.file.Files.writeString(new java.io.File("./request-debug.json").toPath, report.json.prettify)
      // }
      res.withHeaders(addHeaders: _*)
    })
  }

  @inline
  def handleWsRequest(request: RequestHeader, config: JsObject)(implicit ec: ExecutionContext, env: Env, globalConfig: GlobalConfig): Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]] = {
    val requestId = IdGenerator.uuid
    val reporting = config.select("reporting").asOpt[Boolean].getOrElse(true)
    val useTree = config.select("routing_strategy").asOpt[String].contains("tree")
    implicit val report = NgExecutionReport(requestId, reporting)
    report.start("start-handling")
    implicit val mat = env.otoroshiMaterializer

    val snowflake           = env.snowflakeGenerator.nextIdStr()
    val callDate            = DateTime.now()
    val requestTimestamp    = callDate.toString("yyyy-MM-dd'T'HH:mm:ss.SSSZZ")
    val reqNumber           = reqCounter.incrementAndGet()
    val counterIn           = new AtomicLong(0L)
    val counterOut          = new AtomicLong(0L)
    val start               = System.currentTimeMillis()
    implicit val attrs      = TypedMap.empty.put(
      otoroshi.next.plugins.Keys.ReportKey       -> report,
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

    val fakeBody = Source.empty[ByteString]
    val global_plugins__ = NgPlugins.readFrom(globalConfig.plugins.config.select("ng"))

    report.markDoneAndStart("check-concurrent-requests")
    (for {
      _               <- handleConcurrentRequest(request)
      _               =  report.markDoneAndStart("find-route")
      route           <- findRoute(useTree, request, fakeBody, global_plugins__)
      ctxPlugins      = route.contextualPlugins(global_plugins__, request)
      _               = attrs.put(Keys.ContextualPluginsKey -> ctxPlugins)
      _               =  report.markDoneAndStart("tenant-check", Json.obj("found_route" -> route.json).some)
      _               <- handleTenantCheck(route)
      _               =  report.markDoneAndStart("check-global-maintenance")
      _               <- checkGlobalMaintenance(route)
      _               =  report.markDoneAndStart("call-before-request-callbacks")
      _               <- callPluginsBeforeRequestCallback(snowflake, request, route, ctxPlugins)
      _               =  report.markDoneAndStart("extract-tracking-id")
      _               =  extractTrackingId(snowflake, request, reqNumber, route)
      _               =  report.markDoneAndStart("call-pre-route-plugins")
      _               <- callPreRoutePlugins(snowflake, request, route, ctxPlugins)
      _               =  report.markDoneAndStart("call-access-validator-plugins")
      _               <- callAccessValidatorPlugins(snowflake, request, route, ctxPlugins)
      _               =  report.markDoneAndStart("enforce-global-limits")
      remQuotas       <- checkGlobalLimits(request, route) // generic.scala (1269)
      _               =  report.markDoneAndStart("choose-backend", Json.obj("remaining_quotas" -> remQuotas.toJson).some)
      result          <- callWsTarget(snowflake, reqNumber, request, route) {
        case sb @ NgSelectedBackendTarget(backend, attempts, alreadyFailed, cbStart) =>
          report.markDoneAndStart("transform-requests", Json.obj("backend" -> backend.json).some)
          for {
            finalRequest  <- callRequestTransformer(snowflake, request, fakeBody, route, backend, ctxPlugins)
            _             =  report.markDoneAndStart("call-backend")
            flow          <- callWsBackend(snowflake, request, finalRequest, route, backend)
            _             =  report.markDoneAndStart("trigger-analytics")
            _             <- triggerWsProxyDone(snowflake, request, finalRequest, route, backend, sb)
          } yield flow
      }
    } yield {
      result
    }).value.flatMap {
      case Left(error)   =>
        report.markDoneAndStart("rendering-intermediate-result").markSuccess()
        error.asResult().map(r => Left(r))
      case Right(flow) =>
        report.markSuccess()
        Right(flow).vfuture
    }.recover {
      case t: Throwable =>
        report.markFailure("last-recover", t)
        Results.InternalServerError(Json.obj("error" -> "internal_server_error", "error_description" -> t.getMessage, "report" -> report.json)).left
    }.applyOnWithOpt(attrs.get(Keys.ResultTransformerKey)) {
      case (future, transformer) => future.flatMap {
        case Left(r) => transformer(r).map(Left.apply)
        case r@Right(_) => r.vfuture
      }
    }.andThen {
      case _ =>
        report.markOverheadOut()
        report.markDurations()
        attrs.get(Keys.RouteKey).foreach { route =>
          callPluginsAfterRequestCallback(snowflake, request, route, attrs.get(Keys.ContextualPluginsKey).get)
          handleHighOverhead(request, route.some)
          RequestFlowReport(report, route).toAnalytics()
        }
    }
  }

  def extractTrackingId(snowflake: String, req: RequestHeader, reqNumber: Int, route: NgRoute)(implicit attrs: TypedMap): Unit = {
    if (route.backend.loadBalancing.needTrackingCookie) {
      val trackingId: String = req.cookies
        .get("otoroshi-tracking")
        .map(_.value)
        .getOrElse(IdGenerator.uuid + "-" + reqNumber)
      attrs.put(otoroshi.plugins.Keys.RequestTrackingIdKey -> trackingId)
    }
  }

  def handleHighOverhead(req: RequestHeader, route: Option[NgRoute])(implicit ec: ExecutionContext, env: Env, report: NgExecutionReport, globalConfig: GlobalConfig, attrs: TypedMap, mat: Materializer): FEither[NgProxyEngineError, Done] = {
    val overhead = report.getOverheadNow()
    if (overhead > env.overheadThreshold) {
      HighOverheadAlert(
        `@id` = env.snowflakeGenerator.nextIdStr(),
        limitOverhead = env.overheadThreshold,
        currentOverhead = overhead,
        serviceDescriptor = route.map(_.serviceDescriptor),
        target = Location(
          scheme = req.theProtocol,
          host = req.theHost,
          uri = req.relativeUri
        )
      ).toAnalytics()
    }
    FEither.right(Done)
  }

  def handleConcurrentRequest(request: RequestHeader)(implicit ec: ExecutionContext, env: Env, report: NgExecutionReport, globalConfig: GlobalConfig, attrs: TypedMap, mat: Materializer): FEither[NgProxyEngineError, Done] = {
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
        .map(r => Left(NgResultProxyEngineError(r))))
    } else {
      FEither.right(Done)
    }
  }

  def handleTenantCheck(route: NgRoute)(implicit ec: ExecutionContext, env: Env, report: NgExecutionReport, globalConfig: GlobalConfig, attrs: TypedMap, mat: Materializer): FEither[NgProxyEngineError, Done] = {
    if (env.clusterConfig.mode.isWorker
        && env.clusterConfig.worker.tenants.nonEmpty
        && !env.clusterConfig.worker.tenants.contains(route.location.tenant)) {
      report.markFailure(s"this worker cannot serve tenant '${route.location.tenant.value}'")
      FEither.left(NgResultProxyEngineError(Results.NotFound(Json.obj("error" -> "not_found", "error_description" -> "no route found !"))))
    } else {
      FEither.right(Done)
    }
  }

  def findRoute(useTree: Boolean, request: RequestHeader, body: Source[ByteString, _], global_plugins: NgPlugins)(implicit ec: ExecutionContext, env: Env, report: NgExecutionReport, globalConfig: GlobalConfig, attrs: TypedMap, mat: Materializer): FEither[NgProxyEngineError, NgRoute] = {
    val maybeRoute = if (useTree) {
      env.proxyState.findRoute(request, attrs)
    } else {
      env.proxyState.getDomainRoutes(request.theDomain).flatMap(_.find(_.matches(request, attrs, skipDomainVerif = true)))
    }
    maybeRoute match {
      case Some(route) =>
        attrs.put(Keys.RouteKey -> route)
        FEither.right(route)
      case None => callRequestSinkPlugins(request, body, global_plugins)
    }
  }

  def callRequestSinkPlugins(request: RequestHeader, body: Source[ByteString, _], global_plugins: NgPlugins)(implicit ec: ExecutionContext, env: Env, report: NgExecutionReport, globalConfig: GlobalConfig, attrs: TypedMap, mat: Materializer):  FEither[NgProxyEngineError, NgRoute] = {
    def failure(): FEither[NgProxyEngineError, NgRoute] = {
      report.markFailure(s"route not found for domain: '${request.theDomain}${request.thePath}'")
      FEither.left(NgResultProxyEngineError(Results.NotFound(Json.obj("error" -> "not_found", "error_description" -> "no route found !"))))
    }
    val all_plugins = global_plugins.requestSinkPlugins(request)
    if (all_plugins.nonEmpty) {
      all_plugins
        .map { wrapper =>
          val ctx = NgRequestSinkContext(
            snowflake = attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).get,
            request = request,
            config = wrapper.instance.config.raw,
            attrs = attrs,
            origin = NgRequestOrigin.NgReverseProxy,
            status = 404,
            message = s"route not found",
            body = body
          )
          (wrapper, ctx)
        }
        .find {
          case (wrapper, ctx) => wrapper.plugin.matches(ctx)
        }
        .map {
          case (wrapper, ctx) => FEither.apply[NgProxyEngineError, NgRoute](wrapper.plugin.handle(ctx).map(r => Left(NgResultProxyEngineError(r))))
        }
        .getOrElse {
          failure()
        }
    } else {
      failure()
    }
  }

  def checkGlobalMaintenance(route: NgRoute)(implicit ec: ExecutionContext, env: Env, report: NgExecutionReport, globalConfig: GlobalConfig, attrs: TypedMap, mat: Materializer): FEither[NgProxyEngineError, Done] = {
    if (route.id != env.backOfficeServiceId && globalConfig.maintenanceMode) {
      report.markFailure(s"global maintenance activated")
      FEither.left(NgResultProxyEngineError(Results.ServiceUnavailable(Json.obj("error" -> "service_unavailable", "error_description" -> "Service in maintenance mode"))))
    } else {
      FEither.right(Done)
    }
  }

  def callPluginsBeforeRequestCallback(snowflake: String, request: RequestHeader, route: NgRoute, plugins: NgContextualPlugins)(implicit ec: ExecutionContext, env: Env, report: NgExecutionReport, globalConfig: GlobalConfig, attrs: TypedMap, mat: Materializer): FEither[NgProxyEngineError, Done] = {
    val all_plugins = plugins.transformerPluginsWithCallbacks
    if (all_plugins.nonEmpty) {
      var sequence = NgReportPluginSequence(
        size = all_plugins.size,
        kind = "before-request-plugins",
        start = System.currentTimeMillis(),
        stop = 0L,
        start_ns = System.nanoTime(),
        stop_ns = 0L,
        plugins = Seq.empty,
      )
      val _ctx = NgBeforeRequestContext(
        snowflake = snowflake,
        request = request,
        route = route,
        config = Json.obj(),
        globalConfig = globalConfig.plugins.config,
        attrs = attrs,
      )
      def markPluginItem(item: NgReportPluginSequenceItem, ctx: NgBeforeRequestContext, debug: Boolean, result: JsValue): Unit = {
        sequence = sequence.copy(
          plugins = sequence.plugins :+ item.copy(
            stop = System.currentTimeMillis(),
            stop_ns = System.nanoTime(),
            out = Json.obj(
              "result" -> result,
            ).applyOnIf(debug)(_ ++ Json.obj("ctx" -> ctx.json))
          )
        )
      }
      if (all_plugins.size == 1) {
        val wrapper = all_plugins.head
        val pluginConfig: JsValue = wrapper.plugin.defaultConfig.map(dc => dc ++ wrapper.instance.config.raw).getOrElse(wrapper.instance.config.raw)
        val ctx = _ctx.copy(config = pluginConfig)
        val debug = route.debugFlow || wrapper.instance.debug
        val in: JsValue = if (debug) Json.obj("ctx" -> ctx.json) else JsNull
        val item = NgReportPluginSequenceItem(wrapper.instance.plugin, wrapper.plugin.name, System.currentTimeMillis(), System.nanoTime(), -1L, -1L, in, JsNull)
        FEither(wrapper.plugin.beforeRequest(ctx)
          .map { _ =>
            markPluginItem(item, ctx, debug, Json.obj("kind" -> "successful"))
            report.setContext(sequence.stopSequence().json)
            Right(Done)
          }
          .recover {
            case exception: Throwable =>
              markPluginItem(item, ctx, debug, Json.obj("kind" -> "failure", "error" -> JsonHelpers.errToJson(exception)))
              report.setContext(sequence.stopSequence().json)
              Left(NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_server_error", "error_description" -> "an error happened during before-request plugins phase", "error" -> JsonHelpers.errToJson(exception)))))
          })
      } else {
        val promise = Promise[Either[NgProxyEngineError, Done]]()
        def next(plugins: Seq[NgPluginWrapper[NgRequestTransformer]]): Unit = {
          plugins.headOption match {
            case None => promise.trySuccess(Right(Done))
            case Some(wrapper) => {
              val pluginConfig: JsValue = wrapper.plugin.defaultConfig.map(dc => dc ++ wrapper.instance.config.raw).getOrElse(wrapper.instance.config.raw)
              val ctx = _ctx.copy(config = pluginConfig)
              val debug = route.debugFlow || wrapper.instance.debug
              val in: JsValue = if (debug) Json.obj("ctx" -> ctx.json) else JsNull
              val item = NgReportPluginSequenceItem(wrapper.instance.plugin, wrapper.plugin.name, System.currentTimeMillis(), System.nanoTime(), -1L, -1L, in, JsNull)
              wrapper.plugin.beforeRequest(ctx).andThen {
                case Failure(exception) =>
                  markPluginItem(item, ctx, debug, Json.obj("kind" -> "failure", "error" -> JsonHelpers.errToJson(exception)))
                  report.setContext(sequence.stopSequence().json)
                  promise.trySuccess(Left(NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_server_error", "error_description" -> "an error happened during before-request plugins phase", "error" -> JsonHelpers.errToJson(exception))))))
                case Success(_) if plugins.size == 1 =>
                  markPluginItem(item, ctx, debug, Json.obj("kind" -> "successful"))
                  report.setContext(sequence.stopSequence().json)
                  promise.trySuccess(Right(Done))
                case Success(_) =>
                  markPluginItem(item, ctx, debug, Json.obj("kind" -> "successful"))
                  next(plugins.tail)
              }
            }
          }
        }
        next(all_plugins)
        FEither.apply(promise.future)
      }
    } else {
      FEither.right(Done)
    }
  }

  def callPluginsAfterRequestCallback(snowflake: String, request: RequestHeader, route: NgRoute, plugins: NgContextualPlugins)(implicit ec: ExecutionContext, env: Env, report: NgExecutionReport, globalConfig: GlobalConfig, attrs: TypedMap, mat: Materializer): FEither[NgProxyEngineError, Done] =  {
    val all_plugins = plugins.transformerPluginsWithCallbacks
    if (all_plugins.nonEmpty) {
      var sequence = NgReportPluginSequence(
        size = all_plugins.size,
        kind = "after-request-plugins",
        start = System.currentTimeMillis(),
        stop = 0L,
        start_ns = System.nanoTime(),
        stop_ns = 0L,
        plugins = Seq.empty,
      )
      val _ctx = NgAfterRequestContext(
        snowflake = snowflake,
        request = request,
        route = route,
        config = Json.obj(),
        globalConfig = globalConfig.plugins.config,
        attrs = attrs,
      )
      def markPluginItem(item: NgReportPluginSequenceItem, ctx: NgAfterRequestContext, debug: Boolean, result: JsValue): Unit = {
        sequence = sequence.copy(
          plugins = sequence.plugins :+ item.copy(
            stop = System.currentTimeMillis(),
            stop_ns = System.nanoTime(),
            out = Json.obj(
              "result" -> result,
            ).applyOnIf(debug)(_ ++ Json.obj("ctx" -> ctx.json))
          )
        )
      }
      if (all_plugins.size == 1) {
        val wrapper = all_plugins.head
        val pluginConfig: JsValue = wrapper.plugin.defaultConfig.map(dc => dc ++ wrapper.instance.config.raw).getOrElse(wrapper.instance.config.raw)
        val ctx = _ctx.copy(config = pluginConfig)
        val debug = route.debugFlow || wrapper.instance.debug
        val in: JsValue = if (debug) Json.obj("ctx" -> ctx.json) else JsNull
        val item = NgReportPluginSequenceItem(wrapper.instance.plugin, wrapper.plugin.name, System.currentTimeMillis(), System.nanoTime(), -1L, -1L, in, JsNull)
        FEither(wrapper.plugin.afterRequest(ctx)
          .map { _ =>
            markPluginItem(item, ctx, debug, Json.obj("kind" -> "successful"))
            report.setContext(sequence.stopSequence().json)
            Right(Done)
          }
          .recover {
            case exception: Throwable =>
              markPluginItem(item, ctx, debug, Json.obj("kind" -> "failure", "error" -> JsonHelpers.errToJson(exception)))
              report.setContext(sequence.stopSequence().json)
              Left(NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_server_error", "error_description" -> "an error happened during after-request plugins phase", "error" -> JsonHelpers.errToJson(exception)))))
          })
      } else {
        val promise = Promise[Either[NgProxyEngineError, Done]]()
        def next(plugins: Seq[NgPluginWrapper[NgRequestTransformer]]): Unit = {
          plugins.headOption match {
            case None => promise.trySuccess(Right(Done))
            case Some(wrapper) => {
              val pluginConfig: JsValue = wrapper.plugin.defaultConfig.map(dc => dc ++ wrapper.instance.config.raw).getOrElse(wrapper.instance.config.raw)
              val ctx = _ctx.copy(config = pluginConfig)
              val debug = route.debugFlow || wrapper.instance.debug
              val in: JsValue = if (debug) Json.obj("ctx" -> ctx.json) else JsNull
              val item = NgReportPluginSequenceItem(wrapper.instance.plugin, wrapper.plugin.name, System.currentTimeMillis(), System.nanoTime(), -1L, -1L, in, JsNull)
              wrapper.plugin.afterRequest(ctx).andThen {
                case Failure(exception) =>
                  markPluginItem(item, ctx, debug, Json.obj("kind" -> "failure", "error" -> JsonHelpers.errToJson(exception)))
                  report.setContext(sequence.stopSequence().json)
                  promise.trySuccess(Left(NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_server_error", "error_description" -> "an error happened during before-request plugins phase", "error" -> JsonHelpers.errToJson(exception))))))
                case Success(_) if plugins.size == 1 =>
                  markPluginItem(item, ctx, debug, Json.obj("kind" -> "successful"))
                  report.setContext(sequence.stopSequence().json)
                  promise.trySuccess(Right(Done))
                case Success(_) =>
                  markPluginItem(item, ctx, debug, Json.obj("kind" -> "successful"))
                  next(plugins.tail)
              }
            }
          }
        }
        next(all_plugins)
        FEither.apply(promise.future)
      }
    } else {
      FEither.right(Done)
    }
  }

  def callPreRoutePlugins(snowflake: String, request: RequestHeader, route: NgRoute, plugins: NgContextualPlugins)(implicit ec: ExecutionContext, env: Env, report: NgExecutionReport, globalConfig: GlobalConfig, attrs: TypedMap, mat: Materializer): FEither[NgProxyEngineError, Done] = {
    val all_plugins = plugins.preRoutePlugins
    if (all_plugins.nonEmpty) {
      var sequence = NgReportPluginSequence(
        size = all_plugins.size,
        kind = "pre-route-plugins",
        start = System.currentTimeMillis(),
        stop = 0L,
        start_ns = System.nanoTime(),
        stop_ns = 0L,
        plugins = Seq.empty,
      )
      val _ctx = NgPreRoutingContext(
        snowflake = snowflake,
        request = request,
        route = route,
        config = Json.obj(),
        globalConfig = globalConfig.plugins.config,
        attrs = attrs,
        report = report,
      )
      def markPluginItem(item: NgReportPluginSequenceItem, ctx: NgPreRoutingContext, debug: Boolean, result: JsValue): Unit = {
        sequence = sequence.copy(
          plugins = sequence.plugins :+ item.copy(
            stop = System.currentTimeMillis(),
            stop_ns = System.nanoTime(),
            out = Json.obj(
              "result" -> result,
            ).applyOnIf(debug)(_ ++ Json.obj("ctx" -> ctx.json))
          )
        )
      }
      if (all_plugins.size == 1) {
        val wrapper = all_plugins.head
        val pluginConfig: JsValue = wrapper.plugin.defaultConfig.map(dc => dc ++ wrapper.instance.config.raw).getOrElse(wrapper.instance.config.raw)
        val ctx = _ctx.copy(config = pluginConfig)
        val debug = route.debugFlow || wrapper.instance.debug
        val in: JsValue = if (debug) Json.obj("ctx" -> ctx.json) else JsNull
        val item = NgReportPluginSequenceItem(wrapper.instance.plugin, wrapper.plugin.name, System.currentTimeMillis(), System.nanoTime(), -1L, -1L, in, JsNull)
        FEither(wrapper.plugin.preRoute(ctx)
          .transform {
            case Failure(exception) =>
              markPluginItem(item, ctx, debug, Json.obj("kind" -> "failure", "error" -> JsonHelpers.errToJson(exception)))
              report.setContext(sequence.stopSequence().json)
              Success(Left(NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_server_error", "error_description" -> "an error happened during pre-routing plugins phase", "error" -> JsonHelpers.errToJson(exception))))))
            case Success(Left(err)) =>
              val result = err.result
              markPluginItem(item, ctx, debug, Json.obj("kind" -> "short-circuit", "status" -> result.header.status, "headers" -> result.header.headers))
              report.setContext(sequence.stopSequence().json)
              Success(Left(NgResultProxyEngineError(result)))
            case Success(Right(_)) =>
              markPluginItem(item, ctx, debug, Json.obj("kind" -> "successful"))
              report.setContext(sequence.stopSequence().json)
              Success(Right(Done))
          })
      } else {
        val promise = Promise[Either[NgProxyEngineError, Done]]()
        def next(plugins: Seq[NgPluginWrapper[NgPreRouting]]): Unit = {
          plugins.headOption match {
            case None => promise.trySuccess(Right(Done))
            case Some(wrapper) => {
              val pluginConfig: JsValue = wrapper.plugin.defaultConfig.map(dc => dc ++ wrapper.instance.config.raw).getOrElse(wrapper.instance.config.raw)
              val ctx = _ctx.copy(config = pluginConfig)
              val debug = route.debugFlow || wrapper.instance.debug
              val in: JsValue = if (debug) Json.obj("ctx" -> ctx.json) else JsNull
              val item = NgReportPluginSequenceItem(wrapper.instance.plugin, wrapper.plugin.name, System.currentTimeMillis(), System.nanoTime(), -1L, -1L, in, JsNull)
              wrapper.plugin.preRoute(ctx).andThen {
                case Failure(exception) =>
                  markPluginItem(item, ctx, debug, Json.obj("kind" -> "failure", "error" -> JsonHelpers.errToJson(exception)))
                  report.setContext(sequence.stopSequence().json)
                  promise.trySuccess(Left(NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_server_error", "error_description" -> "an error happened during pre-routing plugins phase", "error" -> JsonHelpers.errToJson(exception))))))
                case Success(Left(err)) =>
                  val result = err.result
                  markPluginItem(item, ctx, debug, Json.obj("kind" -> "short-circuit", "status" -> result.header.status, "headers" -> result.header.headers))
                  report.setContext(sequence.stopSequence().json)
                  promise.trySuccess(Left(NgResultProxyEngineError(result)))
                case Success(Right(_)) if plugins.size == 1 =>
                  markPluginItem(item, ctx, debug, Json.obj("kind" -> "successful"))
                  report.setContext(sequence.stopSequence().json)
                  promise.trySuccess(Right(Done))
                case Success(Right(_)) =>
                  markPluginItem(item, ctx, debug, Json.obj("kind" -> "successful"))
                  next(plugins.tail)
              }
            }
          }
        }
        next(all_plugins)
        FEither.apply(promise.future)
      }
    } else {
      FEither.right(Done)
    }
  }

  def callAccessValidatorPlugins(snowflake: String, request: RequestHeader, route: NgRoute, plugins: NgContextualPlugins)(implicit ec: ExecutionContext, env: Env, report: NgExecutionReport, globalConfig: GlobalConfig, attrs: TypedMap, mat: Materializer): FEither[NgProxyEngineError, Done] = {
    val all_plugins = plugins.accessValidatorPlugins
    if (all_plugins.nonEmpty) {
      var sequence = NgReportPluginSequence(
        size = all_plugins.size,
        kind = "access-validator-plugins",
        start = System.currentTimeMillis(),
        stop = 0L,
        start_ns = System.nanoTime(),
        stop_ns = 0L,
        plugins = Seq.empty,
      )
      val _ctx = NgAccessContext(
        snowflake = snowflake,
        request = request,
        route = route,
        config = Json.obj(),
        globalConfig = globalConfig.plugins.config,
        attrs = attrs,
        apikey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey),
        user = attrs.get(otoroshi.plugins.Keys.UserKey),
        report = report,
      )
      def markPluginItem(item: NgReportPluginSequenceItem, ctx: NgAccessContext, debug: Boolean, result: JsValue): Unit = {
        sequence = sequence.copy(
          plugins = sequence.plugins :+ item.copy(
            stop = System.currentTimeMillis(),
            stop_ns = System.nanoTime(),
            out = Json.obj(
              "result" -> result,
            ).applyOnIf(debug)(_ ++ Json.obj("ctx" -> ctx.json))
          )
        )
      }
      if (all_plugins.size == 1) {
        val wrapper = all_plugins.head
        val pluginConfig: JsValue = wrapper.plugin.defaultConfig.map(dc => dc ++ wrapper.instance.config.raw).getOrElse(wrapper.instance.config.raw)
        val ctx = _ctx.copy(config = pluginConfig)
        val debug = route.debugFlow || wrapper.instance.debug
        val in: JsValue = if (debug) Json.obj("ctx" -> ctx.json) else JsNull
        val item = NgReportPluginSequenceItem(wrapper.instance.plugin, wrapper.plugin.name, System.currentTimeMillis(), System.nanoTime(), -1L, -1L, in, JsNull)
        FEither(wrapper.plugin.access(ctx).transform {
          case Failure(exception) =>
            markPluginItem(item, ctx, debug, Json.obj("kind" -> "failure", "error" -> JsonHelpers.errToJson(exception)))
            report.setContext(sequence.stopSequence().json)
            Success(Left(NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_server_error", "error_description" -> "an error happened during pre-routing plugins phase", "error" -> JsonHelpers.errToJson(exception))))))
          case Success(NgAccess.NgDenied(result)) =>
            markPluginItem(item, ctx, debug, Json.obj("kind" -> "denied", "status" -> result.header.status))
            report.setContext(sequence.stopSequence().json)
            Success(Left(NgResultProxyEngineError(result)))
          case Success(NgAccess.NgAllowed) =>
            markPluginItem(item, ctx, debug, Json.obj("kind" -> "allowed"))
            report.setContext(sequence.stopSequence().json)
           Success(Right(Done))
        })
      } else {
        val promise = Promise[Either[NgProxyEngineError, Done]]()
        def next(plugins: Seq[NgPluginWrapper[NgAccessValidator]]): Unit = {
          plugins.headOption match {
            case None => promise.trySuccess(Right(Done))
            case Some(wrapper) => {
              val pluginConfig: JsValue = wrapper.plugin.defaultConfig.map(dc => dc ++ wrapper.instance.config.raw).getOrElse(wrapper.instance.config.raw)
              val ctx = _ctx.copy(config = pluginConfig)
              val debug = route.debugFlow || wrapper.instance.debug
              val in: JsValue = if (debug) Json.obj("ctx" -> ctx.json) else JsNull
              val item = NgReportPluginSequenceItem(wrapper.instance.plugin, wrapper.plugin.name, System.currentTimeMillis(), System.nanoTime(), -1L, -1L, in, JsNull)
              wrapper.plugin.access(ctx).andThen {
                case Failure(exception) =>
                  markPluginItem(item, ctx, debug, Json.obj("kind" -> "failure", "error" -> JsonHelpers.errToJson(exception)))
                  report.setContext(sequence.stopSequence().json)
                  promise.trySuccess(Left(NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_server_error", "error_description" -> "an error happened during pre-routing plugins phase", "error" -> JsonHelpers.errToJson(exception))))))
                case Success(NgAccess.NgDenied(result)) =>
                  markPluginItem(item, ctx, debug, Json.obj("kind" -> "denied", "status" -> result.header.status))
                  report.setContext(sequence.stopSequence().json)
                  promise.trySuccess(Left(NgResultProxyEngineError(result)))
                case Success(NgAccess.NgAllowed) if plugins.size == 1 =>
                  markPluginItem(item, ctx, debug, Json.obj("kind" -> "allowed"))
                  report.setContext(sequence.stopSequence().json)
                  promise.trySuccess(Right(Done))
                case Success(NgAccess.NgAllowed) =>
                  markPluginItem(item, ctx, debug, Json.obj("kind" -> "allowed"))
                  next(plugins.tail)
              }
            }
          }
        }
        next(all_plugins)
        FEither.apply(promise.future)
      }
    } else {
      FEither.right(Done)
    }
  }

  def checkGlobalLimits(request: RequestHeader, route: NgRoute)(implicit ec: ExecutionContext, env: Env, report: NgExecutionReport, globalConfig: GlobalConfig, attrs: TypedMap, mat: Materializer): FEither[NgProxyEngineError, RemainingQuotas] = {
    val remoteAddress = request.theIpAddress
    val isUp = true
    def errorResult(status: Results.Status, message: String, code: String): Future[Either[NgProxyEngineError, RemainingQuotas]] = {
      Errors
        .craftResponseResult(
          message,
          status,
          request,
          None,
          Some(code),
          duration = report.getDurationNow(),
          overhead = report.getOverheadInNow(),
          attrs = attrs,
          maybeRoute = route.some,
        )
        .map(e => Left(NgResultProxyEngineError(e)))
    }
    FEither(env.datastores.globalConfigDataStore.quotasValidationFor(remoteAddress).flatMap { r =>
      val (within, secCalls, maybeQuota) = r
      val quota = maybeQuota.getOrElse(globalConfig.perIpThrottlingQuota)
      if (secCalls > (quota * 10L)) {
        errorResult(Results.TooManyRequests, "[IP] You performed too much requests", "errors.too.much.requests")
      } else {
        if (!within) {
          errorResult(Results.TooManyRequests, "[GLOBAL] You performed too much requests", "errors.too.much.requests")
        } else if (globalConfig.ipFiltering.notMatchesWhitelist(remoteAddress)) {
          errorResult(Results.Forbidden, "Your IP address is not allowed", "errors.ip.address.not.allowed") // global whitelist
        } else if (globalConfig.ipFiltering.matchesBlacklist(remoteAddress)) {
          errorResult(Results.Forbidden, "Your IP address is not allowed", "errors.ip.address.not.allowed") // global blacklist
        } else if (globalConfig.matchesEndlessIpAddresses(remoteAddress)) {
          val gigas: Long = 128L * 1024L * 1024L * 1024L
          val middleFingers = ByteString.fromString(
            "\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95"
          )
          val zeros =
            ByteString.fromInts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
          val characters: ByteString =
            if (!globalConfig.middleFingers) middleFingers else zeros
          val expected: Long = (gigas / characters.size) + 1L
          Left(NgResultProxyEngineError(Results.Status(200)
            .sendEntity(
              HttpEntity.Streamed(
                Source
                  .repeat(characters)
                  .take(expected), // 128 Go of zeros or middle fingers
                None,
                Some("application/octet-stream")
              )
            )
          )).vfuture
        } else if (isUp) {
          attrs.get(otoroshi.plugins.Keys.ApiKeyKey)
            .map(_.updateQuotas())
            .getOrElse(RemainingQuotas().vfuture)
            .map(rq => Right.apply[NgProxyEngineError, RemainingQuotas](rq))
        } else {
          // fail fast
          errorResult(Results.Forbidden, "The service seems to be down :( come back later", "errors.service.down")
        }
      }
    })
  }

  def getBackend(target: Target, route: NgRoute)(implicit env: Env): NgTarget = {
    route.backend.allTargets.find(b => b.id == target.tags.head).get
  }

  def callTarget(snowflake: String, reqNumber: Long, request: Request[Source[ByteString, _]], _route: NgRoute)(f: NgSelectedBackendTarget => FEither[NgProxyEngineError, Result])(implicit ec: ExecutionContext, env: Env, report: NgExecutionReport, globalConfig: GlobalConfig, attrs: TypedMap, mat: Materializer): FEither[NgProxyEngineError, Result] = {
    val cbStart            = System.currentTimeMillis()
    val route =  attrs.get(otoroshi.next.plugins.Keys.PossibleBackendsKey).map(b => _route.copy(backend = b)).getOrElse(_route)
    val trackingId = attrs.get(otoroshi.plugins.Keys.RequestTrackingIdKey).getOrElse(IdGenerator.uuid)
    val bodyAlreadyConsumed = new AtomicBoolean(false)
    attrs.put(Keys.BodyAlreadyConsumedKey -> bodyAlreadyConsumed)
    if (
      globalConfig.useCircuitBreakers && route.client.useCircuitBreaker
    ) {
      val counter            = new AtomicInteger(0)
      val relUri             = request.relativeUri
      val cachedPath: String =
        route.client
          .timeouts(relUri)
          .map(_ => relUri)
          .getOrElse("")

      def callF(target: Target, attempts: Int, alreadyFailed: AtomicBoolean): Future[Either[Result, Result]] = {
        val backend = getBackend(target, route)
        attrs.put(Keys.BackendKey -> backend)
        f(NgSelectedBackendTarget(backend, attempts, alreadyFailed, cbStart)).value.flatMap {
          case Left(err) => err.asResult().map(Left.apply)
          case r @ Right(value) => Right(value).vfuture
        }
      }

      def handleError(t: Throwable): Future[Either[Result, Result]] = {
        t match {
          case BodyAlreadyConsumedException                       =>
            Errors
              .craftResponseResult(
                s"Something went wrong, the backend service does not respond quickly enough but consumed all the request body, you should try later. Thanks for your understanding",
                Results.GatewayTimeout,
                request,
                None,
                Some("errors.request.timeout"),
                duration = report.getDurationNow(),
                overhead = report.getOverheadInNow(),
                cbDuration = System.currentTimeMillis - cbStart,
                callAttempts = counter.get(),
                attrs = attrs,
                maybeRoute = route.some,
              )
              .map(Left.apply)
          case RequestTimeoutException                            =>
            Errors
              .craftResponseResult(
                s"Something went wrong, the backend service does not respond quickly enough, you should try later. Thanks for your understanding",
                Results.GatewayTimeout,
                request,
                None,
                Some("errors.request.timeout"),
                duration = report.getDurationNow(),
                overhead = report.getOverheadInNow(),
                cbDuration = System.currentTimeMillis - cbStart,
                callAttempts = counter.get(),
                attrs = attrs,
                maybeRoute = route.some,
              )
              .map(Left.apply)
          case _: scala.concurrent.TimeoutException               =>
            Errors
              .craftResponseResult(
                s"Something went wrong, the backend service does not respond quickly enough, you should try later. Thanks for your understanding",
                Results.GatewayTimeout,
                request,
                None,
                Some("errors.request.timeout"),
                duration = report.getDurationNow(),
                overhead = report.getOverheadInNow(),
                cbDuration = System.currentTimeMillis - cbStart,
                callAttempts = counter.get(),
                attrs = attrs,
                maybeRoute = route.some,
              )
              .map(Left.apply)
          case AllCircuitBreakersOpenException                    =>
            Errors
              .craftResponseResult(
                s"Something went wrong, the backend service seems a little bit overwhelmed, you should try later. Thanks for your understanding",
                Results.ServiceUnavailable,
                request,
                None,
                Some("errors.circuit.breaker.open"),
                duration = report.getDurationNow(),
                overhead = report.getOverheadInNow(),
                cbDuration = System.currentTimeMillis - cbStart,
                callAttempts = counter.get(),
                attrs = attrs,
                maybeRoute = route.some,
              )
              .map(Left.apply)
          case error
            if error != null && error.getMessage != null && error.getMessage
              .toLowerCase()
              .contains("connection refused") =>
            Errors
              .craftResponseResult(
                s"Something went wrong, the connection to backend service was refused, you should try later. Thanks for your understanding",
                Results.BadGateway,
                request,
                None,
                Some("errors.connection.refused"),
                duration = report.getDurationNow(),
                overhead = report.getOverheadInNow(),
                cbDuration = System.currentTimeMillis - cbStart,
                callAttempts = counter.get(),
                attrs = attrs,
                maybeRoute = route.some,
              )
              .map(Left.apply)
          case error if error != null && error.getMessage != null =>
            logger.error(
              s"Something went wrong, you should try later",
              error
            )
            Errors
              .craftResponseResult(
                s"Something went wrong, you should try later. Thanks for your understanding.",
                Results.BadGateway,
                request,
                None,
                Some("errors.proxy.error"),
                duration = report.getDurationNow(),
                overhead = report.getOverheadInNow(),
                cbDuration = System.currentTimeMillis - cbStart,
                callAttempts = counter.get(),
                attrs = attrs,
                maybeRoute = route.some,
              )
              .map(Left.apply)
          case error                                              =>
            logger.error(
              s"Something went wrong, you should try later",
              error
            )
            Errors
              .craftResponseResult(
                s"Something went wrong, you should try later. Thanks for your understanding",
                Results.BadGateway,
                request,
                None,
                Some("errors.proxy.error"),
                duration = report.getDurationNow(),
                overhead = report.getOverheadInNow(),
                cbDuration = System.currentTimeMillis - cbStart,
                callAttempts = counter.get(),
                attrs = attrs,
                maybeRoute = route.some,
              )
              .map(Left.apply)
        }
      }

      implicit val scheduler = env.otoroshiScheduler
      FEither(env.circuitBeakersHolder
        .get(
          route.id + cachedPath,
          () => new ServiceDescriptorCircuitBreaker()
        )
        .callGenNg[Result](
          route.id,
          route.name,
          route.backend.allTargets.map(_.toTarget),
          route.backend.loadBalancing,
          route.client,
          reqNumber.toString,
          trackingId,
          request.relativeUri,
          request,
          bodyAlreadyConsumed,
          s"${request.method} ${request.relativeUri}",
          counter,
          attrs,
          callF
        ) recoverWith {
        case t: Throwable => handleError(t)
      } map {
        case Left(res) => Left(NgResultProxyEngineError(res))
        case Right(value) => Right(value)
      })
    } else {

      val target = attrs
        .get(otoroshi.plugins.Keys.PreExtractedRequestTargetKey)
        .getOrElse {

          val targets: Seq[Target] = route.backend.allTargets.map(_.toTarget)
            .filter(_.predicate.matches(reqNumber.toString, request, attrs))
            .flatMap(t => Seq.fill(t.weight)(t))
          route.backend.loadBalancing
            .select(
              reqNumber.toString,
              trackingId,
              request,
              targets,
              route.id
            )
      }
      //val index = reqCounter.get() % (if (targets.nonEmpty) targets.size else 1)
      // Round robin loadbalancing is happening here !!!!!
      //val target = targets.apply(index.toInt)
      val backend = getBackend(target, route)
      attrs.put(Keys.BackendKey -> backend)
      f(NgSelectedBackendTarget(backend, 1, new AtomicBoolean(false), cbStart))
    }
  }

  def callWsTarget(snowflake: String, reqNumber: Long, request: RequestHeader, _route: NgRoute)(f: NgSelectedBackendTarget => FEither[NgProxyEngineError, Flow[PlayWSMessage, PlayWSMessage, _]])(implicit ec: ExecutionContext, env: Env, report: NgExecutionReport, globalConfig: GlobalConfig, attrs: TypedMap, mat: Materializer): FEither[NgProxyEngineError, Flow[PlayWSMessage, PlayWSMessage, _]] = {
    val cbStart            = System.currentTimeMillis()
    val route =  attrs.get(otoroshi.next.plugins.Keys.PossibleBackendsKey).map(b => _route.copy(backend = b)).getOrElse(_route)
    val trackingId = attrs.get(otoroshi.plugins.Keys.RequestTrackingIdKey).getOrElse(IdGenerator.uuid)
    val bodyAlreadyConsumed = new AtomicBoolean(false)
    attrs.put(Keys.BodyAlreadyConsumedKey -> bodyAlreadyConsumed)
    if (
      globalConfig.useCircuitBreakers && route.client.useCircuitBreaker
    ) {
      val counter            = new AtomicInteger(0)
      val relUri             = request.relativeUri
      val cachedPath: String =
        route.client
          .timeouts(relUri)
          .map(_ => relUri)
          .getOrElse("")

      def callF(target: Target, attempts: Int, alreadyFailed: AtomicBoolean): Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]] = {
        val backend = getBackend(target, route)
        attrs.put(Keys.BackendKey -> backend)
        f(NgSelectedBackendTarget(backend, attempts, alreadyFailed, cbStart)).value.flatMap {
          case Left(err) => err.asResult().map(Left.apply)
          case r @ Right(value) => Right(value).vfuture
        }
      }

      def handleError(t: Throwable): Future[Either[Result,  Flow[PlayWSMessage, PlayWSMessage, _]]] = {
        t match {
          case BodyAlreadyConsumedException                       =>
            Errors
              .craftResponseResult(
                s"Something went wrong, the backend service does not respond quickly enough but consumed all the request body, you should try later. Thanks for your understanding",
                Results.GatewayTimeout,
                request,
                None,
                Some("errors.request.timeout"),
                duration = report.getDurationNow(),
                overhead = report.getOverheadInNow(),
                cbDuration = System.currentTimeMillis - cbStart,
                callAttempts = counter.get(),
                attrs = attrs,
                maybeRoute = route.some,
              )
              .map(Left.apply)
          case RequestTimeoutException                            =>
            Errors
              .craftResponseResult(
                s"Something went wrong, the backend service does not respond quickly enough, you should try later. Thanks for your understanding",
                Results.GatewayTimeout,
                request,
                None,
                Some("errors.request.timeout"),
                duration = report.getDurationNow(),
                overhead = report.getOverheadInNow(),
                cbDuration = System.currentTimeMillis - cbStart,
                callAttempts = counter.get(),
                attrs = attrs,
                maybeRoute = route.some,
              )
              .map(Left.apply)
          case _: scala.concurrent.TimeoutException               =>
            Errors
              .craftResponseResult(
                s"Something went wrong, the backend service does not respond quickly enough, you should try later. Thanks for your understanding",
                Results.GatewayTimeout,
                request,
                None,
                Some("errors.request.timeout"),
                duration = report.getDurationNow(),
                overhead = report.getOverheadInNow(),
                cbDuration = System.currentTimeMillis - cbStart,
                callAttempts = counter.get(),
                attrs = attrs,
                maybeRoute = route.some,
              )
              .map(Left.apply)
          case AllCircuitBreakersOpenException                    =>
            Errors
              .craftResponseResult(
                s"Something went wrong, the backend service seems a little bit overwhelmed, you should try later. Thanks for your understanding",
                Results.ServiceUnavailable,
                request,
                None,
                Some("errors.circuit.breaker.open"),
                duration = report.getDurationNow(),
                overhead = report.getOverheadInNow(),
                cbDuration = System.currentTimeMillis - cbStart,
                callAttempts = counter.get(),
                attrs = attrs,
                maybeRoute = route.some,
              )
              .map(Left.apply)
          case error
            if error != null && error.getMessage != null && error.getMessage
              .toLowerCase()
              .contains("connection refused") =>
            Errors
              .craftResponseResult(
                s"Something went wrong, the connection to backend service was refused, you should try later. Thanks for your understanding",
                Results.BadGateway,
                request,
                None,
                Some("errors.connection.refused"),
                duration = report.getDurationNow(),
                overhead = report.getOverheadInNow(),
                cbDuration = System.currentTimeMillis - cbStart,
                callAttempts = counter.get(),
                attrs = attrs,
                maybeRoute = route.some,
              )
              .map(Left.apply)
          case error if error != null && error.getMessage != null =>
            logger.error(
              s"Something went wrong, you should try later",
              error
            )
            Errors
              .craftResponseResult(
                s"Something went wrong, you should try later. Thanks for your understanding.",
                Results.BadGateway,
                request,
                None,
                Some("errors.proxy.error"),
                duration = report.getDurationNow(),
                overhead = report.getOverheadInNow(),
                cbDuration = System.currentTimeMillis - cbStart,
                callAttempts = counter.get(),
                attrs = attrs,
                maybeRoute = route.some,
              )
              .map(Left.apply)
          case error                                              =>
            logger.error(
              s"Something went wrong, you should try later",
              error
            )
            Errors
              .craftResponseResult(
                s"Something went wrong, you should try later. Thanks for your understanding",
                Results.BadGateway,
                request,
                None,
                Some("errors.proxy.error"),
                duration = report.getDurationNow(),
                overhead = report.getOverheadInNow(),
                cbDuration = System.currentTimeMillis - cbStart,
                callAttempts = counter.get(),
                attrs = attrs,
                maybeRoute = route.some,
              )
              .map(Left.apply)
        }
      }

      implicit val scheduler = env.otoroshiScheduler
      FEither(env.circuitBeakersHolder
        .get(
          route.id + cachedPath,
          () => new ServiceDescriptorCircuitBreaker()
        )
        .callGenNg[Flow[PlayWSMessage, PlayWSMessage, _]](
          route.id,
          route.name,
          route.backend.allTargets.map(_.toTarget),
          route.backend.loadBalancing,
          route.client,
          reqNumber.toString,
          trackingId,
          request.relativeUri,
          request,
          bodyAlreadyConsumed,
          s"${request.method} ${request.relativeUri}",
          counter,
          attrs,
          callF
        ) recoverWith {
        case t: Throwable => handleError(t)
      } map {
        case Left(res) => Left(NgResultProxyEngineError(res))
        case Right(value) => Right(value)
      })
    } else {

      val target = attrs
        .get(otoroshi.plugins.Keys.PreExtractedRequestTargetKey)
        .getOrElse {

          val targets: Seq[Target] = route.backend.allTargets.map(_.toTarget)
            .filter(_.predicate.matches(reqNumber.toString, request, attrs))
            .flatMap(t => Seq.fill(t.weight)(t))
          route.backend.loadBalancing
            .select(
              reqNumber.toString,
              trackingId,
              request,
              targets,
              route.id
            )
        }
      //val index = reqCounter.get() % (if (targets.nonEmpty) targets.size else 1)
      // Round robin loadbalancing is happening here !!!!!
      //val target = targets.apply(index.toInt)
      val backend = getBackend(target, route)
      attrs.put(Keys.BackendKey -> backend)
      f(NgSelectedBackendTarget(backend, 1, new AtomicBoolean(false), cbStart))
    }
  }

  def maybeStrippedUri(req: RequestHeader, rawUri: String, route: NgRoute): String = {
    val allPaths = route.frontend.domains.map(_.path)
    val root        = req.relativeUri
    val rootMatched = allPaths match { //rootMatched was this.matchingRoot
      case ps if ps.isEmpty => None
      case ps               => ps.find(p => root.startsWith(p))
    }
    // TODO: try to work with wildcard path ?
    rootMatched
      .filter(m => route.frontend.stripPath && root.startsWith(m))
      .map(m => root.replaceFirst(m.replace(".", "\\."), ""))
      .getOrElse(rawUri)
  }

  def callRequestTransformer(snowflake: String, request: RequestHeader, body: Source[ByteString, _], route: NgRoute, backend: NgTarget, plugins: NgContextualPlugins)(implicit ec: ExecutionContext, env: Env, report: NgExecutionReport, globalConfig: GlobalConfig, attrs: TypedMap, mat: Materializer): FEither[NgProxyEngineError, NgPluginHttpRequest] = {
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

    val lazySource = Source.single(ByteString.empty).flatMapConcat { _ =>
      attrs.get(Keys.BodyAlreadyConsumedKey).foreach(_.compareAndSet(false, true))
      body
    }

    val headersInFiltered = Seq(
      env.Headers.OtoroshiState,
      env.Headers.OtoroshiClaim,
      env.Headers.OtoroshiRequestId,
      env.Headers.OtoroshiClientId,
      env.Headers.OtoroshiClientSecret,
      env.Headers.OtoroshiAuthorization,
    ).++(headersInStatic).map(_.toLowerCase)

    val headers = request.headers.toSimpleMap
      .filterNot {
        case (key, _) => headersInFiltered.contains(key.toLowerCase())
      }

    val rawRequest = NgPluginHttpRequest(
      url = s"${request.theProtocol}://${request.theHost}${request.relativeUri}",
      method = request.method,
      headers = request.headers.toSimpleMap,
      cookies = wsCookiesIn,
      version = request.version,
      clientCertificateChain = request.clientCertificateChain,
      body = lazySource,
      backend = None
    )
    val otoroshiRequest = NgPluginHttpRequest(
      url = s"${target.scheme}://${target.host}$root$uri",
      method = request.method,
      headers = request.headers.toSimpleMap,
      cookies = wsCookiesIn,
      version = request.version,
      clientCertificateChain = request.clientCertificateChain,
      body = lazySource,
      backend = backend.some
    )

    val all_plugins = plugins.transformerPluginsThatTransformsRequest
    if (all_plugins.nonEmpty) {
      var sequence = NgReportPluginSequence(
        size = all_plugins.size,
        kind = "request-transformer-plugins",
        start = System.currentTimeMillis(),
        stop = 0L,
        start_ns = System.nanoTime(),
        stop_ns = 0L,
        plugins = Seq.empty,
      )
      val __ctx = NgTransformerRequestContext(
        snowflake = snowflake,
        request = request,
        rawRequest = rawRequest,
        otoroshiRequest = otoroshiRequest,
        apikey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey),
        user = attrs.get(otoroshi.plugins.Keys.UserKey),
        route = route,
        config = Json.obj(),
        globalConfig = globalConfig.plugins.config,
        attrs = attrs,
        report = report
      )
      def markPluginItem(item: NgReportPluginSequenceItem, ctx: NgTransformerRequestContext, debug: Boolean, result: JsValue): Unit = {
        sequence = sequence.copy(
          plugins = sequence.plugins :+ item.copy(
            stop = System.currentTimeMillis(),
            stop_ns = System.nanoTime(),
            out = Json.obj(
              "result" -> result,
            ).applyOnIf(debug)(_ ++ Json.obj("ctx" -> ctx.json))
          )
        )
      }
      if (all_plugins.size == 1) {
        val wrapper = all_plugins.head
        val pluginConfig: JsValue = wrapper.plugin.defaultConfig.map(dc => dc ++ wrapper.instance.config.raw).getOrElse(wrapper.instance.config.raw)
        val ctx = __ctx.copy(config = pluginConfig)
        val debug = route.debugFlow || wrapper.instance.debug
        val in: JsValue = if (debug) Json.obj("ctx" -> ctx.json) else JsNull
        val item = NgReportPluginSequenceItem(wrapper.instance.plugin, wrapper.plugin.name, System.currentTimeMillis(), System.nanoTime(), -1L, -1L, in, JsNull)
        FEither(wrapper.plugin.transformRequest(ctx).transform {
          case Failure(exception) =>
            markPluginItem(item, ctx, debug, Json.obj("kind" -> "failure", "error" -> JsonHelpers.errToJson(exception)))
            report.setContext(sequence.stopSequence().json)
            Success(Left(NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_server_error", "error_description" -> "an error happened during request-transformation plugins phase", "error" -> JsonHelpers.errToJson(exception))))))
          case Success(Left(result)) =>
            markPluginItem(item, ctx, debug, Json.obj("kind" -> "short-circuit", "status" -> result.header.status, "headers" -> result.header.headers))
            report.setContext(sequence.stopSequence().json)
            Success(Left(NgResultProxyEngineError(result)))
          case Success(Right(req_next)) =>
            markPluginItem(item, ctx.copy(otoroshiRequest = req_next), debug, Json.obj("kind" -> "successful"))
            report.setContext(sequence.stopSequence().json)
            Success(Right(req_next))
        })
      } else {
        val promise = Promise[Either[NgProxyEngineError, NgPluginHttpRequest]]()
        def next(_ctx: NgTransformerRequestContext, plugins: Seq[NgPluginWrapper[NgRequestTransformer]]): Unit = {
          plugins.headOption match {
            case None => promise.trySuccess(Right(_ctx.otoroshiRequest))
            case Some(wrapper) => {
              val pluginConfig: JsValue = wrapper.plugin.defaultConfig.map(dc => dc ++ wrapper.instance.config.raw).getOrElse(wrapper.instance.config.raw)
              val ctx = _ctx.copy(config = pluginConfig)
              val debug = route.debugFlow || wrapper.instance.debug
              val in: JsValue = if (debug) Json.obj("ctx" -> ctx.json) else JsNull
              val item = NgReportPluginSequenceItem(wrapper.instance.plugin, wrapper.plugin.name, System.currentTimeMillis(), System.nanoTime(), -1L, -1L, in, JsNull)
              wrapper.plugin.transformRequest(ctx).andThen {
                case Failure(exception) =>
                  markPluginItem(item, ctx, debug, Json.obj("kind" -> "failure", "error" -> JsonHelpers.errToJson(exception)))
                  report.setContext(sequence.stopSequence().json)
                  promise.trySuccess(Left(NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_server_error", "error_description" -> "an error happened during request-transformation plugins phase", "error" -> JsonHelpers.errToJson(exception))))))
                case Success(Left(result)) =>
                  markPluginItem(item, ctx, debug, Json.obj("kind" -> "short-circuit", "status" -> result.header.status, "headers" -> result.header.headers))
                  report.setContext(sequence.stopSequence().json)
                  promise.trySuccess(Left(NgResultProxyEngineError(result)))
                case Success(Right(req_next)) if plugins.size == 1 =>
                  markPluginItem(item, ctx.copy(otoroshiRequest = req_next), debug, Json.obj("kind" -> "successful"))
                  report.setContext(sequence.stopSequence().json)
                  promise.trySuccess(Right(req_next))
                case Success(Right(req_next)) =>
                  markPluginItem(item, ctx.copy(otoroshiRequest = req_next), debug, Json.obj("kind" -> "successful"))
                  next(_ctx.copy(otoroshiRequest = req_next), plugins.tail)
              }
            }
          }
        }

        next(__ctx, all_plugins)
        FEither.apply(promise.future)
      }
    } else {
      FEither.right(otoroshiRequest)
    }
  }

  def callWsBackend(snowflake: String, rawRequest: RequestHeader, request : NgPluginHttpRequest, route: NgRoute, backend: NgTarget)(implicit ec: ExecutionContext, env: Env, report: NgExecutionReport, globalConfig: GlobalConfig, attrs: TypedMap, mat: Materializer): FEither[NgProxyEngineError, Flow[PlayWSMessage, PlayWSMessage, _]] = {
    val finalTarget: Target = request.backend.getOrElse(backend).toTarget
    attrs.put(otoroshi.plugins.Keys.RequestTargetKey -> finalTarget)
    val all_tunnel_handlers = route.plugins.tunnelHandlerPlugins(rawRequest)
    if (all_tunnel_handlers.nonEmpty) {
       val handler = all_tunnel_handlers.head
       if (request.relativeUri.startsWith("/.well-known/otoroshi/tunnel")) {
         val ctx = NgTunnelHandlerContext(
           snowflake = snowflake,
           request = rawRequest,
           route = route,
           config = handler.instance.config.raw,
           attrs = attrs,
         )
         FEither(handler.plugin.handle(ctx).right.vfuture)
       } else {
         FEither(Errors
           .craftResponseResult(
             s"Resource not found",
             NotFound,
             rawRequest,
             None,
             Some("errors.resource.not.found"),
             attrs = attrs,
             maybeRoute = route.some
           ).map(r => Left(NgResultProxyEngineError(r))))
       }
    } else {
      if (route.useAkkaHttpWsClient) {
        FEither(WebSocketProxyActor.wsCall(
          UrlSanitizer.sanitize(request.url),
          request.headers.toSeq,
          route.serviceDescriptor,
          finalTarget
        ).right.vfuture)
      } else {
        FEither(ActorFlow
          .actorRef(out =>
            WebSocketProxyActor.props(
              UrlSanitizer.sanitize(request.url),
              out,
              request.headers.toSeq,
              route.serviceDescriptor,
              finalTarget,
              env
            )
          )(env.otoroshiActorSystem, env.otoroshiMaterializer).right.vfuture)
      }
    }
  }

  def callBackend(snowflake: String, rawRequest: Request[Source[ByteString, _]], request : NgPluginHttpRequest, route: NgRoute, backend: NgTarget)(implicit ec: ExecutionContext, env: Env, report: NgExecutionReport, globalConfig: GlobalConfig, attrs: TypedMap, mat: Materializer): FEither[NgProxyEngineError, WSResponse] = {
    val currentReqHasBody = rawRequest.theHasBody
    val wsCookiesIn = request.cookies
    val finalTarget: Target = request.backend.getOrElse(backend).toTarget
    attrs.put(otoroshi.plugins.Keys.RequestTargetKey -> finalTarget)
    val clientConfig = route.client
    val clientReq = route.useAkkaHttpClient match {
      case _ if finalTarget.mtlsConfig.mtls =>
        env.gatewayClient.akkaUrlWithTarget(
          UrlSanitizer.sanitize(request.url),
          finalTarget,
          clientConfig
        )
      case true                             =>
        env.gatewayClient.akkaUrlWithTarget(
          UrlSanitizer.sanitize(request.url),
          finalTarget,
          clientConfig
        )
      case false                            =>
        env.gatewayClient.urlWithTarget(
          UrlSanitizer.sanitize(request.url),
          finalTarget,
          clientConfig
        )
    }
    val host = request.headers.get("Host").orElse(request.headers.get("host")).getOrElse(rawRequest.theHost)
    val extractedTimeout = route.client.extractTimeout(rawRequest.relativeUri, _.callAndStreamTimeout, _.callAndStreamTimeout)
    val builder          = clientReq
      .withRequestTimeout(extractedTimeout)
      .withFailureIndicator(fakeFailureIndicator)
      .withMethod(request.method)
      .withHttpHeaders(request.headers.filterNot(_._1.toLowerCase == "cookie").+("Host" -> host).toSeq: _*)
      .withCookies(wsCookiesIn: _*)
      .withFollowRedirects(false)
      .withMaybeProxyServer(
        route.client.proxy.orElse(globalConfig.proxies.services)
      )

    val counterIn = attrs.get(otoroshi.plugins.Keys.RequestCounterInKey).get
    val theBody = request.body.map { bs =>
      counterIn.addAndGet(bs.length)
      bs
    }
    // because writeableOf_WsBody always add a 'Content-Type: application/octet-stream' header
    val builderWithBody = if (currentReqHasBody) {
      builder.withBody(theBody)
    } else {
      builder
    }

    report.markOverheadIn()
    FEither.fright(builderWithBody.stream().andThen {
      case Success(_) => report.startOverheadOut()
    })
  }

  def callResponseTransformer(snowflake: String, rawRequest: Request[Source[ByteString, _]], response: WSResponse, quotas: RemainingQuotas, route: NgRoute, backend: NgTarget, plugins: NgContextualPlugins)(implicit ec: ExecutionContext, env: Env, report: NgExecutionReport, globalConfig: GlobalConfig, attrs: TypedMap, mat: Materializer): FEither[NgProxyEngineError, NgPluginHttpResponse] = {

    val rawResponse = NgPluginHttpResponse(
      status = response.status,
      headers = response.headers.mapValues(_.last),
      cookies = response.cookies,
      body = response.bodyAsSource
    )
    val otoroshiResponse = NgPluginHttpResponse(
      status = response.status,
      headers = response.headers.mapValues(_.last),
      cookies = response.cookies,
      body = response.bodyAsSource
    )

    val all_plugins = plugins.transformerPluginsThatTransformsResponse
    if (all_plugins.nonEmpty) {
      var sequence = NgReportPluginSequence(
        size = all_plugins.size,
        kind = "response-transformer-plugins",
        start = System.currentTimeMillis(),
        stop = 0L,
        start_ns = System.nanoTime(),
        stop_ns = 0L,
        plugins = Seq.empty,
      )
      val __ctx = NgTransformerResponseContext(
        snowflake = snowflake,
        request = rawRequest,
        response = response,
        rawResponse = rawResponse,
        otoroshiResponse = otoroshiResponse,
        apikey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey),
        user = attrs.get(otoroshi.plugins.Keys.UserKey),
        route = route,
        config = Json.obj(),
        globalConfig = globalConfig.plugins.config,
        attrs = attrs,
        report = report
      )
      def markPluginItem(item: NgReportPluginSequenceItem, ctx: NgTransformerResponseContext, debug: Boolean, result: JsValue): Unit = {
        sequence = sequence.copy(
          plugins = sequence.plugins :+ item.copy(
            stop = System.currentTimeMillis(),
            stop_ns = System.nanoTime(),
            out = Json.obj(
              "result" -> result,
            ).applyOnIf(debug)(_ ++ Json.obj("ctx" -> ctx.json))
          )
        )
      }
      if (all_plugins.size == 1) {
        val wrapper = all_plugins.head
        val pluginConfig: JsValue = wrapper.plugin.defaultConfig.map(dc => dc ++ wrapper.instance.config.raw).getOrElse(wrapper.instance.config.raw)
        val ctx = __ctx.copy(config = pluginConfig)
        val debug = route.debugFlow || wrapper.instance.debug
        val in: JsValue = if (debug) Json.obj("ctx" -> ctx.json) else JsNull
        val item = NgReportPluginSequenceItem(wrapper.instance.plugin, wrapper.plugin.name, System.currentTimeMillis(), System.nanoTime(), -1L, -1L, in, JsNull)
        FEither(wrapper.plugin.transformResponse(ctx).transform {
          case Failure(exception) =>
            markPluginItem(item, ctx, debug, Json.obj("kind" -> "failure", "error" -> JsonHelpers.errToJson(exception)))
            report.setContext(sequence.stopSequence().json)
            Success(Left(NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_server_error", "error_description" -> "an error happened during response-transformation plugins phase", "error" -> JsonHelpers.errToJson(exception))))))
          case Success(Left(result)) =>
            markPluginItem(item, ctx, debug, Json.obj("kind" -> "short-circuit", "status" -> result.header.status, "headers" -> result.header.headers))
            report.setContext(sequence.stopSequence().json)
            Success(Left(NgResultProxyEngineError(result)))
          case Success(Right(resp_next)) =>
            markPluginItem(item, ctx.copy(otoroshiResponse = resp_next), debug, Json.obj("kind" -> "successful"))
            report.setContext(sequence.stopSequence().json)
            Success(Right(resp_next))
        })
      } else {
        val promise = Promise[Either[NgProxyEngineError, NgPluginHttpResponse]]()
        def next(_ctx: NgTransformerResponseContext, plugins: Seq[NgPluginWrapper[NgRequestTransformer]]): Unit = {
          plugins.headOption match {
            case None => promise.trySuccess(Right(_ctx.otoroshiResponse))
            case Some(wrapper) => {
              val pluginConfig: JsValue = wrapper.plugin.defaultConfig.map(dc => dc ++ wrapper.instance.config.raw).getOrElse(wrapper.instance.config.raw)
              val ctx = _ctx.copy(config = pluginConfig)
              val debug = route.debugFlow || wrapper.instance.debug
              val in: JsValue = if (debug) Json.obj("ctx" -> ctx.json) else JsNull
              val item = NgReportPluginSequenceItem(wrapper.instance.plugin, wrapper.plugin.name, System.currentTimeMillis(), System.nanoTime(), -1L, -1L, in, JsNull)
              wrapper.plugin.transformResponse(ctx).andThen {
                case Failure(exception) =>
                  markPluginItem(item, ctx, debug, Json.obj("kind" -> "failure", "error" -> JsonHelpers.errToJson(exception)))
                  report.setContext(sequence.stopSequence().json)
                  promise.trySuccess(Left(NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_server_error", "error_description" -> "an error happened during response-transformation plugins phase", "error" -> JsonHelpers.errToJson(exception))))))
                case Success(Left(result)) =>
                  markPluginItem(item, ctx, debug, Json.obj("kind" -> "short-circuit", "status" -> result.header.status, "headers" -> result.header.headers))
                  report.setContext(sequence.stopSequence().json)
                  promise.trySuccess(Left(NgResultProxyEngineError(result)))
                case Success(Right(resp_next)) if plugins.size == 1 =>
                  markPluginItem(item, ctx.copy(otoroshiResponse = resp_next), debug, Json.obj("kind" -> "successful"))
                  report.setContext(sequence.stopSequence().json)
                  promise.trySuccess(Right(resp_next))
                case Success(Right(resp_next)) =>
                  markPluginItem(item, ctx.copy(otoroshiResponse = resp_next), debug, Json.obj("kind" -> "successful"))
                  next(_ctx.copy(otoroshiResponse = resp_next), plugins.tail)
              }
            }
          }
        }

        next(__ctx, all_plugins)
        FEither.apply(promise.future)
      }
    } else {
      FEither.right(otoroshiResponse)
    }
  }

  def streamResponse(snowflake: String, rawRequest: Request[Source[ByteString, _]], rawResponse: WSResponse, response: NgPluginHttpResponse, route: NgRoute, backend: NgTarget)(implicit ec: ExecutionContext, env: Env, report: NgExecutionReport, globalConfig: GlobalConfig, attrs: TypedMap, mat: Materializer): FEither[NgProxyEngineError, Result] = {
    val contentType: Option[String] = response.headers
      .get("Content-Type")
      .orElse(response.headers.get("content-type"))
    val contentLength: Option[Long] = response.headers
      .get("Content-Length")
      .orElse(response.headers.get("content-length"))
      .orElse(rawResponse.contentLengthStr)
      .map(_.toLong)
    val _cookies = response.cookies.map {
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
    val cookies = attrs.get(otoroshi.plugins.Keys.RequestTrackingIdKey) match {
      case None => _cookies
      case Some(trackingId) => {
        _cookies :+ play.api.mvc.Cookie(
          name = "otoroshi-tracking",
          value = trackingId,
          maxAge = Some(2592000),
          path = "/",
          domain = Some(rawRequest.theDomain),
          httpOnly = false
        )
      }
    }
    val noContentLengthHeader: Boolean =
      rawResponse.contentLength.isEmpty
    val hasChunkedHeader: Boolean      = rawResponse
      .header("Transfer-Encoding")
      .orElse(response.headers.get("Transfer-Encoding"))
      .exists(h => h.toLowerCase().contains("chunked"))
    val isChunked: Boolean             = rawResponse.isChunked() match {
      case Some(chunked)                                                                         => chunked
      case None if !env.emptyContentLengthIsChunked                                              =>
        hasChunkedHeader // false
      case None if env.emptyContentLengthIsChunked && hasChunkedHeader                           =>
        true
      case None if env.emptyContentLengthIsChunked && !hasChunkedHeader && noContentLengthHeader =>
        true
      case _                                                                                     => false
    }
    val status = attrs.get(otoroshi.plugins.Keys.StatusOverrideKey).getOrElse(response.status)
    val isHttp10 = rawRequest.version == "HTTP/1.0"
    val willStream = if (isHttp10) false else (!isChunked)
    val headersOutFiltered = Seq(
      env.Headers.OtoroshiStateResp,
    ).++(headersOutStatic).map(_.toLowerCase)
    val headers: Seq[(String, String)] = response.headers.filterNot {
      case (key, _) => headersOutFiltered.contains(key.toLowerCase())
    }.applyOnIf(!isHttp10)(_.filterNot(h => h._1.toLowerCase() == "content-length")).toSeq
    val counterOut = attrs.get(otoroshi.plugins.Keys.RequestCounterOutKey).get
    val theBody = response.body.map { bs =>
      counterOut.addAndGet(bs.length)
      bs
    }
    if (isHttp10) {
      logger.warn(
        s"HTTP/1.0 request, storing temporary result in memory :( (${rawRequest.theProtocol}://${rawRequest.theHost}${rawRequest.relativeUri})"
      )
      FEither(response.body
        .via(
          MaxLengthLimiter(
            globalConfig.maxHttp10ResponseSize.toInt,
            str => logger.warn(str)
          )
        )
        .runWith(
          Sink.reduce[ByteString]((bs, n) => bs.concat(n))
        )
        .map { body =>
          val response: Result = Status(status)(body)
            .withHeaders(headers: _*)
            .withCookies(cookies: _*)
          contentType match {
            case None      => Right(response)
            case Some(ctp) => Right(response.as(ctp))
          }
        })
    } else {
      isChunked match {
        case true => {
          // stream out
          val res = Status(status)
            .chunked(response.body)
            .withHeaders(headers: _*)
            .withCookies(cookies: _*)
          contentType match {
            case None      => FEither.right(res)
            case Some(ctp) => FEither.right(res.as(ctp))
          }
        }
        case false => {
          val res = Results.Status(status)
            .sendEntity(
              HttpEntity.Streamed(
                response.body,
                contentLength,
                contentType
              )
            )
            .withHeaders(headers: _*)
            .withCookies(cookies: _*)
          contentType match {
            case None      => FEither.right(res)
            case Some(ctp) => FEither.right(res.as(ctp))
          }
        }
      }
    }
  }

  def triggerWsProxyDone(snowflake: String, rawRequest: RequestHeader, request: NgPluginHttpRequest, route: NgRoute, backend: NgTarget, sb: NgSelectedBackendTarget)(implicit ec: ExecutionContext, env: Env, report: NgExecutionReport, globalConfig: GlobalConfig, attrs: TypedMap, mat: Materializer): FEither[NgProxyEngineError, Done] = {
    Future {
      val actualDuration: Long = report.getDurationNow()
      val overhead: Long = report.getOverheadNow()
      val upstreamLatency: Long = report.getStep("call-backend").map(_.duration).getOrElse(-1L)
      val apiKey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey)
      val paUsr = attrs.get(otoroshi.plugins.Keys.UserKey)
      val callDate = attrs.get(otoroshi.plugins.Keys.RequestTimestampKey).get
      val counterIn = attrs.get(otoroshi.plugins.Keys.RequestCounterInKey).get
      val counterOut = attrs.get(otoroshi.plugins.Keys.RequestCounterOutKey).get
      val fromOtoroshi = rawRequest.headers
        .get(env.Headers.OtoroshiRequestId)
        .orElse(rawRequest.headers.get(env.Headers.OtoroshiGatewayParentRequest))
      val duration: Long = {
        if (route.id == env.backOfficeServiceId && actualDuration > 300L)
          300L
        else actualDuration
      }
      env.analyticsQueue ! AnalyticsQueueEvent(
        route.serviceDescriptor,
        duration,
        overhead,
        counterIn.get(),
        counterOut.get(),
        upstreamLatency,
        globalConfig
      )
      route.backend.loadBalancing match {
        case BestResponseTime =>
          BestResponseTime.incrementAverage(route.id, backend.toTarget, duration)
        case WeightedBestResponseTime(_) =>
          BestResponseTime.incrementAverage(route.id, backend.toTarget, duration)
        case _ =>
      }
      val fromLbl =
        rawRequest.headers
          .get(env.Headers.OtoroshiVizFromLabel)
          .getOrElse("internet")
      val viz: OtoroshiViz = OtoroshiViz(
        to = route.id,
        toLbl = route.name,
        from = rawRequest.headers
          .get(env.Headers.OtoroshiVizFrom)
          .getOrElse("internet"),
        fromLbl = fromLbl,
        fromTo = s"$fromLbl###${route.name}"
      )
      val cbDuration = System.currentTimeMillis() - sb.cbStart
      val evt = GatewayEvent(
        `@id` = env.snowflakeGenerator.nextIdStr(),
        reqId = snowflake,
        parentReqId = fromOtoroshi,
        `@timestamp` = DateTime.now(),
        `@calledAt` = callDate,
        protocol = rawRequest.version,
        to = Location(
          scheme = rawRequest.theProtocol,
          host = rawRequest.theHost,
          uri = rawRequest.relativeUri
        ),
        target = Location(
          scheme = backend.toTarget.scheme,
          host = backend.toTarget.host,
          uri = rawRequest.relativeUri
        ),
        duration = duration,
        overhead = overhead,
        cbDuration = cbDuration,
        overheadWoCb = overhead - cbDuration,
        callAttempts = sb.attempts,
        url = rawRequest.theUrl,
        method = rawRequest.method,
        from = rawRequest.theIpAddress,
        env = "prod",
        data = DataInOut(
          dataIn = counterIn.get(),
          dataOut = counterOut.get()
        ),
        status = 200,
        headers = rawRequest.headers.toSimpleMap.toSeq.map(Header.apply),
        headersOut = Seq.empty,
        otoroshiHeadersIn = request.headers.toSeq.map(Header.apply),
        otoroshiHeadersOut = Seq.empty,
        extraInfos = attrs.get(otoroshi.plugins.Keys.GatewayEventExtraInfosKey),
        identity = apiKey
          .map(k =>
            Identity(
              identityType = "APIKEY",
              identity = k.clientId,
              label = k.clientName
            )
          )
          .orElse(
            paUsr.map(k =>
              Identity(
                identityType = "PRIVATEAPP",
                identity = k.email,
                label = k.name
              )
            )
          ),
        responseChunked = false,
        `@serviceId` = route.id,
        `@service` = route.name,
        descriptor = None,
        route = Some(route),
        `@product` = route.metadata.getOrElse("product", "--"),
        remainingQuotas = attrs.get(otoroshi.plugins.Keys.ApiKeyRemainingQuotasKey).getOrElse(RemainingQuotas()),
        viz = Some(viz),
        clientCertChain = rawRequest.clientCertChainPem,
        err = attrs.get(otoroshi.plugins.Keys.GwErrorKey).isDefined,
        gwError = attrs.get(otoroshi.plugins.Keys.GwErrorKey).map(_.message),
        userAgentInfo = attrs.get[JsValue](otoroshi.plugins.Keys.UserAgentInfoKey),
        geolocationInfo = attrs.get[JsValue](otoroshi.plugins.Keys.GeolocationInfoKey),
        extraAnalyticsData = attrs.get[JsValue](otoroshi.plugins.Keys.ExtraAnalyticsDataKey)
      )
      evt.toAnalytics()
    }(env.analyticsExecutionContext)
    FEither.right(Done)
  }

  def triggerProxyDone(snowflake: String, rawRequest: Request[Source[ByteString, _]], rawResponse: WSResponse, request: NgPluginHttpRequest, response: NgPluginHttpResponse, route: NgRoute, backend: NgTarget, sb: NgSelectedBackendTarget)(implicit ec: ExecutionContext, env: Env, report: NgExecutionReport, globalConfig: GlobalConfig, attrs: TypedMap, mat: Materializer): FEither[NgProxyEngineError, Done] = {
    Future {
      val actualDuration: Long = report.getDurationNow()
      val overhead: Long = report.getOverheadNow()
      val upstreamLatency: Long = report.getStep("call-backend").map(_.duration).getOrElse(-1L)
      val apiKey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey)
      val paUsr = attrs.get(otoroshi.plugins.Keys.UserKey)
      val callDate = attrs.get(otoroshi.plugins.Keys.RequestTimestampKey).get
      val counterIn = attrs.get(otoroshi.plugins.Keys.RequestCounterInKey).get
      val counterOut = attrs.get(otoroshi.plugins.Keys.RequestCounterOutKey).get
      val fromOtoroshi = rawRequest.headers
        .get(env.Headers.OtoroshiRequestId)
        .orElse(rawRequest.headers.get(env.Headers.OtoroshiGatewayParentRequest))
      val noContentLengthHeader: Boolean =
        rawResponse.contentLength.isEmpty
      val hasChunkedHeader: Boolean = rawResponse
        .header("Transfer-Encoding")
        .exists(h => h.toLowerCase().contains("chunked"))
      val isChunked: Boolean = rawResponse.isChunked() match {
        case Some(chunked) => chunked
        case None if !env.emptyContentLengthIsChunked =>
          hasChunkedHeader // false
        case None if env.emptyContentLengthIsChunked && hasChunkedHeader =>
          true
        case None if env.emptyContentLengthIsChunked && !hasChunkedHeader && noContentLengthHeader =>
          true
        case _ => false
      }
      val duration: Long = {
        if (route.id == env.backOfficeServiceId && actualDuration > 300L)
          300L
        else actualDuration
      }
      env.analyticsQueue ! AnalyticsQueueEvent(
        route.serviceDescriptor,
        duration,
        overhead,
        counterIn.get(),
        counterOut.get(),
        upstreamLatency,
        globalConfig
      )
      route.backend.loadBalancing match {
        case BestResponseTime =>
          BestResponseTime.incrementAverage(route.id, backend.toTarget, duration)
        case WeightedBestResponseTime(_) =>
          BestResponseTime.incrementAverage(route.id, backend.toTarget, duration)
        case _ =>
      }
      val fromLbl =
        rawRequest.headers
          .get(env.Headers.OtoroshiVizFromLabel)
          .getOrElse("internet")
      val viz: OtoroshiViz = OtoroshiViz(
        to = route.id,
        toLbl = route.name,
        from = rawRequest.headers
          .get(env.Headers.OtoroshiVizFrom)
          .getOrElse("internet"),
        fromLbl = fromLbl,
        fromTo = s"$fromLbl###${route.name}"
      )
      val cbDuration = System.currentTimeMillis() - sb.cbStart
      val evt = GatewayEvent(
        `@id` = env.snowflakeGenerator.nextIdStr(),
        reqId = snowflake,
        parentReqId = fromOtoroshi,
        `@timestamp` = DateTime.now(),
        `@calledAt` = callDate,
        protocol = rawRequest.version,
        to = Location(
          scheme = rawRequest.theProtocol,
          host = rawRequest.theHost,
          uri = rawRequest.relativeUri
        ),
        target = Location(
          scheme = backend.toTarget.scheme,
          host = backend.toTarget.host,
          uri = rawRequest.relativeUri
        ),
        duration = duration,
        overhead = overhead,
        cbDuration = cbDuration,
        overheadWoCb = overhead - cbDuration,
        callAttempts = sb.attempts,
        url = rawRequest.theUrl,
        method = rawRequest.method,
        from = rawRequest.theIpAddress,
        env = "prod",
        data = DataInOut(
          dataIn = counterIn.get(),
          dataOut = counterOut.get()
        ),
        status = rawResponse.status,
        headers = rawRequest.headers.toSimpleMap.toSeq.map(Header.apply),
        headersOut = rawResponse.headers.mapValues(_.last).toSeq.map(Header.apply),
        otoroshiHeadersIn = request.headers.toSeq.map(Header.apply),
        otoroshiHeadersOut = response.headers.toSeq.map(Header.apply),
        extraInfos = attrs.get(otoroshi.plugins.Keys.GatewayEventExtraInfosKey),
        identity = apiKey
          .map(k =>
            Identity(
              identityType = "APIKEY",
              identity = k.clientId,
              label = k.clientName
            )
          )
          .orElse(
            paUsr.map(k =>
              Identity(
                identityType = "PRIVATEAPP",
                identity = k.email,
                label = k.name
              )
            )
          ),
        responseChunked = isChunked,
        `@serviceId` = route.id,
        `@service` = route.name,
        descriptor = None,
        route = Some(route),
        `@product` = route.metadata.getOrElse("product", "--"),
        remainingQuotas = attrs.get(otoroshi.plugins.Keys.ApiKeyRemainingQuotasKey).getOrElse(RemainingQuotas()),
        viz = Some(viz),
        clientCertChain = rawRequest.clientCertChainPem,
        err = attrs.get(otoroshi.plugins.Keys.GwErrorKey).isDefined,
        gwError = attrs.get(otoroshi.plugins.Keys.GwErrorKey).map(_.message),
        userAgentInfo = attrs.get[JsValue](otoroshi.plugins.Keys.UserAgentInfoKey),
        geolocationInfo = attrs.get[JsValue](otoroshi.plugins.Keys.GeolocationInfoKey),
        extraAnalyticsData = attrs.get[JsValue](otoroshi.plugins.Keys.ExtraAnalyticsDataKey)
      )
      evt.toAnalytics()
    }(env.analyticsExecutionContext)
    FEither.right(Done)
  }
}

case class RequestFlowReport(report: NgExecutionReport, route: NgRoute) extends AnalyticEvent {

  override def `@service`: String   = route.name
  override def `@serviceId`: String = route.id
  def `@id`: String = IdGenerator.uuid
  def `@timestamp`: org.joda.time.DateTime = timestamp
  def `@type`: String = "RequestFlowReport"
  override def fromOrigin: Option[String]    = None
  override def fromUserAgent: Option[String] = None

  val timestamp = DateTime.now()

  override def toJson(implicit env: Env): JsValue =
    Json.obj(
      "@id"          -> `@id`,
      "@timestamp"   -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(timestamp),
      "@type"        -> "RequestFlowReport",
      "@product"     -> "otoroshi",
      "@serviceId"   -> `@serviceId`,
      "@service"     -> `@service`,
      "@env"         -> "prod",
      "route"        -> route.json,
      "report"       -> report.json
    )
}