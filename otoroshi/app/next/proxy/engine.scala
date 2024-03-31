package otoroshi.next.proxy

import akka.Done
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.ByteString
import com.github.blemale.scaffeine.{Cache, Scaffeine}
import org.joda.time.DateTime
import otoroshi.el.TargetExpressionLanguage
import otoroshi.env.Env
import otoroshi.events._
import otoroshi.gateway._
import otoroshi.models._
import otoroshi.netty.NettyHttpClient
import otoroshi.next.events.TrafficCaptureEvent
import otoroshi.next.models._
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
import otoroshi.utils.{RegexPool, TypedMap, UrlSanitizer}
import play.api.Logger
import play.api.http.{HttpChunk, HttpEntity}
import play.api.http.websocket.{Message => PlayWSMessage}
import play.api.libs.json._
import play.api.libs.streams.ActorFlow
import play.api.libs.ws.WSRequest
import play.api.mvc.Results.Status
import play.api.mvc._
import play.api.mvc.request.RequestAttrKey

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicLong, AtomicReference}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

case class ProxyEngineConfig(
    enabled: Boolean,
    domains: Seq[String],
    denyDomains: Seq[String],
    reporting: Boolean,
    pluginMerge: Boolean,
    exportReporting: Boolean,
    debug: Boolean,
    debugHeaders: Boolean,
    applyLegacyChecks: Boolean,
    capture: Boolean,
    captureMaxEntitySize: Long,
    routingStrategy: RoutingStrategy
) {
  lazy val useTree: Boolean = routingStrategy == RoutingStrategy.Tree
  def json: JsValue         = Json.obj(
    "enabled"              -> enabled,
    "domains"              -> domains,
    "deny_domains"         -> denyDomains,
    "reporting"            -> reporting,
    "merge_sync_steps"     -> pluginMerge,
    "export_reporting"     -> exportReporting,
    "apply_legacy_checks"  -> applyLegacyChecks,
    "debug"                -> debug,
    "capture"              -> capture,
    "captureMaxEntitySize" -> captureMaxEntitySize,
    "debug_headers"        -> debugHeaders,
    "routing_strategy"     -> routingStrategy.json
  )
}

object ProxyEngineConfig {
  lazy val default: ProxyEngineConfig = ProxyEngineConfig(
    enabled = true,
    domains = Seq("*"),
    denyDomains = Seq.empty,
    reporting = true,
    pluginMerge = true,
    exportReporting = false,
    debug = false,
    debugHeaders = false,
    applyLegacyChecks = true,
    capture = false,
    captureMaxEntitySize = 4 * 1024 * 1024,
    routingStrategy = RoutingStrategy.Tree
  )
  def parse(config: JsValue, env: Env): ProxyEngineConfig = {
    val enabled                    = config.select("enabled").asOpt[Boolean].getOrElse(true)
    val domains                    =
      if (enabled) config.select("domains").asOpt[Seq[String]].getOrElse(Seq("*"))
      else Seq.empty[String]
    val denyDomains                =
      if (enabled) config.select("deny_domains").asOpt[Seq[String]].getOrElse(Seq.empty) else Seq.empty[String]
    val reporting                  = config.select("reporting").asOpt[Boolean].getOrElse(true)
    val pluginMerge                = config
      .select("merge_sync_steps")
      .asOpt[Boolean]
      .orElse(
        env.configuration.getOptionalWithFileSupport[Boolean]("otoroshi.next.plugins.merge-sync-steps")
      )
      .getOrElse(true)
    val applyLegacyChecks          = config
      .select("apply_legacy_checks")
      .asOpt[Boolean]
      .orElse(
        env.configuration.getOptionalWithFileSupport[Boolean]("otoroshi.next.plugins.apply-legacy-checks")
      )
      .getOrElse(true)
    val routingStrategy            = config.select("routing_strategy").asOpt[String].getOrElse("tree")
    val exportReporting            = config
      .select("export_reporting")
      .asOpt[Boolean]
      .orElse(
        env.configuration.getOptionalWithFileSupport[Boolean]("otoroshi.next.export-reporting")
      )
      .getOrElse(false)
    val debug                      = config.select("debug").asOpt[Boolean].getOrElse(false)
    val capture                    = config.select("capture").asOpt[Boolean].getOrElse(false)
    val captureMaxEntitySize: Long = config.select("captureMaxEntitySize").asOpt[Long].getOrElse(4 * 1024 * 1024)
    val debugHeaders               = config.select("debug_headers").asOpt[Boolean].getOrElse(false)
    ProxyEngineConfig(
      enabled = enabled,
      domains = domains,
      denyDomains = denyDomains,
      reporting = reporting,
      pluginMerge = pluginMerge,
      exportReporting = exportReporting,
      debug = debug,
      capture = capture,
      captureMaxEntitySize = captureMaxEntitySize,
      debugHeaders = debugHeaders,
      applyLegacyChecks = applyLegacyChecks,
      routingStrategy = RoutingStrategy.parse(routingStrategy)
    )
  }
}

object ProxyEngine {
  def configRoot: String = "NextGenProxyEngine"
}

class ProxyEngine() extends RequestHandler {

  def badDefaultRoutingHttp(req: Request[Source[ByteString, _]]): Future[Result]                             =
    Results.InternalServerError("bad default routing").vfuture
  def badDefaultRoutingWs(req: RequestHeader): Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]] =
    Results.InternalServerError("bad default routing").left.vfuture

  private val logger               = Logger("otoroshi-next-gen-proxy-engine")
  private val fakeFailureIndicator = new AtomicBoolean(false)
  private val reqCounter           = new AtomicInteger(0)

  private val enabledRef     = new AtomicBoolean(true)
  private val enabledDomains = new AtomicReference(Seq.empty[String])

  private val configCache: Cache[String, ProxyEngineConfig] = Scaffeine()
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

  override def name: String = "Otoroshi next proxy engine (experimental)"

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Experimental)
  override def steps: Seq[NgStep]                = Seq(NgStep.HandlesRequest)

  override def description: Option[String] =
    """
      |This plugin holds the next generation otoroshi proxy engine implementation. This engine is **experimental** and may not work as expected !
      |
      |You can active this plugin only on some domain names so you can easily A/B test the new engine.
      |The new proxy engine is designed to be more reactive and more efficient generally.
      |It is also designed to be very efficient on path routing where it wasn't the old engines strong suit.
      |
      |The idea is to only rely on plugins to work and avoid losing time with features that are not used in service descriptors.
      |An automated conversion happens for every service descriptor. If the exposed domain is handled by this plugin, it will be served by this plugin.
      |This plugin introduces new entities that will replace (one day maybe) service descriptors:
      |
      | - `routes`: a unique routing rule based on hostname, path, method and headers that will execute a bunch of plugins
      | - `route-compositions`: multiple routing rules based on hostname, path, method and headers that will execute the same list of plugins
      | - `backends`: a list of targets to contact a backend
      |
      |as an example, let say you want to use the new engine on your service exposed on `api.foo.bar/api`.
      |To do that, just add the plugin in the `global plugins` section of the danger zone, inject the default configuration,
      |enabled it and in `domains` add the value `api.foo.bar` (it is possible to use `*.foo.bar` if that's what you want to do).
      |The next time a request hits the `api.foo.bar` domain, the new engine will handle it instead of the old one.
      |
      |```json
      |{
      |  "NextGenProxyEngine" : {
      |    "enabled" : true,
      |    "debug" : false,
      |    "debug_headers" : false,
      |    "reporting": true,
      |    "routing_strategy" : "tree",
      |    "merge_sync_steps" : true,
      |    "domains" : [ "api.foo.bar" ]
      |  }
      |}
      |```
      |
      |""".stripMargin.some

  override def configRoot: Option[String] = ProxyEngine.configRoot.some

  override def defaultConfig: Option[JsObject] = {
    Json
      .obj(
        ProxyEngine.configRoot -> ProxyEngineConfig.default.json
      )
      .some
  }

  @inline
  def getConfig()(implicit ec: ExecutionContext, env: Env): ProxyEngineConfig = {
    configCache.get(
      "config",
      _ => {
        val config = env.datastores.globalConfigDataStore
          .latest()
          .plugins
          .config
          .select(configRoot.get)
          .asOpt[JsObject]
          .map(v => ProxyEngineConfig.parse(v, env))
          .getOrElse(ProxyEngineConfig.default)

        enabledRef.set(config.enabled)
        enabledDomains.set(config.domains)
        config
      }
    )
  }

  private def otoroshiJsonError(
      error: JsObject,
      status: Results.Status,
      route: Option[NgRoute],
      attrs: TypedMap,
      req: RequestHeader
  )(implicit env: Env, ec: ExecutionContext): Result = {
    if (env.isDev) {
      logger.error(s"proxy engine error on route '${route.map(_.id).getOrElse("")}/${route
        .map(_.name)
        .getOrElse("")}' - ${req.method} ${req.path}: ${error.prettify}")
    }
    Errors.craftResponseResultSync(
      message = error.select("error_description").asOpt[String].getOrElse("an error occurred !"),
      status = status,
      req = req,
      maybeCauseId = error.select("error").asOpt[String],
      attrs = attrs,
      maybeRoute = route
    )
  }

  override def handledDomains(implicit ec: ExecutionContext, env: Env): Seq[String] = {
    val config = getConfig()
    enabledDomains.get()
  }

  override def handle(
      request: Request[Source[ByteString, _]],
      defaultRouting: Request[Source[ByteString, _]] => Future[Result]
  )(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    implicit val globalConfig = env.datastores.globalConfigDataStore.latest()
    val config                = getConfig()
    val shouldNotHandle       =
      if (config.denyDomains.isEmpty) false
      else config.denyDomains.exists(d => RegexPool.apply(d).matches(request.theDomain))
    if (enabledRef.get() && !shouldNotHandle) {
      handleRequest(request, config)
    } else {
      defaultRouting(request)
    }
  }

  override def handleWs(
      request: RequestHeader,
      defaultRouting: RequestHeader => Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]]
  )(implicit ec: ExecutionContext, env: Env): Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]] = {
    implicit val globalConfig = env.datastores.globalConfigDataStore.latest()
    val config                = getConfig()
    val shouldNotHandle       =
      if (config.denyDomains.isEmpty) false
      else config.denyDomains.exists(d => RegexPool.apply(d).matches(request.theDomain))
    if (enabledRef.get() && !shouldNotHandle) {
      handleWsRequest(request, config)
    } else {
      defaultRouting(request)
    }
  }

  @inline
  def handleRequest(request: Request[Source[ByteString, _]], _config: ProxyEngineConfig)(implicit
      ec: ExecutionContext,
      env: Env,
      globalConfig: GlobalConfig
  ): Future[Result] = {
    val start                                                                                                = System.currentTimeMillis()
    val tryItId                                                                                              = request.headers.get("Otoroshi-Try-It-Request-Id")
    val tryIt                                                                                                = tryItId.exists(id => env.proxyState.isReportEnabledFor(id))
    val requestId                                                                                            = IdGenerator.uuid
    val ProxyEngineConfig(_, _, _, reporting, pluginMerge, exportReporting, debug, debugHeaders, _, _, _, _) = _config
    val useTree                                                                                              = _config.useTree
    implicit val report                                                                                      = NgExecutionReport(requestId, reporting)
    report.start("start-handling")

    implicit val mat       = env.otoroshiMaterializer
    val snowflake          = env.snowflakeGenerator.nextIdStr()
    val callDate           = DateTime.now()
    val requestTimestamp   = callDate.toString("yyyy-MM-dd'T'HH:mm:ss.SSSZZ")
    val reqNumber          = reqCounter.incrementAndGet()
    val counterIn          = new AtomicLong(0L)
    val counterOut         = new AtomicLong(0L)
    val responseEndPromise = Promise[Done]()
    implicit val attrs     = TypedMap.empty.put(
      otoroshi.next.plugins.Keys.ReportKey        -> report,
      otoroshi.plugins.Keys.RequestNumberKey      -> reqNumber,
      otoroshi.plugins.Keys.SnowFlakeKey          -> snowflake,
      otoroshi.plugins.Keys.RequestTimestampKey   -> callDate,
      otoroshi.plugins.Keys.RequestStartKey       -> start,
      otoroshi.plugins.Keys.RequestWebsocketKey   -> false,
      otoroshi.plugins.Keys.RequestCounterInKey   -> counterIn,
      otoroshi.plugins.Keys.RequestCounterOutKey  -> counterOut,
      otoroshi.plugins.Keys.ResponseEndPromiseKey -> responseEndPromise
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
      _         <- handleConcurrentRequest(request)
      _          = report.markDoneAndStart("find-route")
      route     <- findRoute(useTree, request, request.body, global_plugins__, tryIt)
      _         <- handleRelayTraffic(route, request, request.body)
      config     = (route.metadata.get("otoroshi-core-apply-legacy-checks") match {
                     case Some("false") => _config.copy(applyLegacyChecks = false)
                     case Some("true")  => _config.copy(applyLegacyChecks = true)
                     case _             => _config
                   }).applyOnIf(route.capture)(_.copy(capture = true))
      _          = report.markDoneAndStart("compute-plugins")
      gplugs     = global_plugins__
      ctxPlugins = route.contextualPlugins(gplugs, pluginMerge, request).seffectOn(_.allPlugins)
      _          = attrs.put(Keys.ContextualPluginsKey -> ctxPlugins)
      _          = report.markDoneAndStart(
                     "tenant-check",
                     Json
                       .obj(
                         "disabled_plugins" -> ctxPlugins.disabledPlugins.map(p => JsString(p.plugin)),
                         "excluded_plugins" -> ctxPlugins.filteredPlugins.map(p => JsString(p.plugin)),
                         "included_plugins" -> ctxPlugins.allPlugins.map(p => JsString(p.plugin))
                       )
                       .some
                   )
      _         <- handleTenantCheck(route, request)
      _          = report.markDoneAndStart("check-global-maintenance")
      _         <- checkGlobalMaintenance(route, request, config)
      _          = report.markDoneAndStart("call-before-request-callbacks")
      _         <- callPluginsBeforeRequestCallback(snowflake, request, route, ctxPlugins)
      _          = report.markDoneAndStart("extract-tracking-id")
      _          = extractTrackingId(snowflake, request, reqNumber, route)
      _          = report.markDoneAndStart("call-pre-route-plugins")
      _         <- callPreRoutePlugins(snowflake, request, route, ctxPlugins)
      _          = report.markDoneAndStart("call-access-validator-plugins")
      _         <- callAccessValidatorPlugins(snowflake, request, route, ctxPlugins)
      // _          = report.markDoneAndStart("update-apikey-quotas")
      // _         <- updateApikeyQuotas(config)
      _          = report.markDoneAndStart(
                     "handle-legacy-checks",
                     attrs
                       .get(otoroshi.plugins.Keys.ApiKeyRemainingQuotasKey)
                       .map(remQuotas => Json.obj("remaining_quotas" -> remQuotas.toJson))
                   )
      _         <- handleLegacyChecks(request, route, config)
      _          = report.markDoneAndStart("choose-backend")
      result    <- callTarget(snowflake, reqNumber, request, route) {
                     case sb @ NgSelectedBackendTarget(backend, attempts, alreadyFailed, cbStart) =>
                       report.markDoneAndStart("transform-request", Json.obj("backend" -> backend.json).some)
                       for {
                         finalRequest <-
                           callRequestTransformer(snowflake, request, request.body, route, backend, ctxPlugins)
                         _             = report.markDoneAndStart("call-backend")
                         response     <- callBackend(
                                           snowflake,
                                           noBackendCallerPlugin = false,
                                           request,
                                           finalRequest,
                                           route,
                                           backend,
                                           ctxPlugins,
                                           config
                                         )
                         _             = report.markDoneAndStart("transform-response")
                         finalResp    <-
                           callResponseTransformer(snowflake, request, response, route, backend, ctxPlugins)
                         _             = report.markDoneAndStart("stream-response")
                         clientResp   <-
                           streamResponse(snowflake, request, finalRequest, response, finalResp, route, backend, config)
                         _             = report.markDoneAndStart("trigger-analytics")
                         _            <- triggerProxyDone(snowflake, request, response, finalRequest, finalResp, route, backend, sb)
                       } yield clientResp
                   }
    } yield {
      result
    }).value
      .flatMap {
        case Left(error)   =>
          report.markDoneAndStart("rendering-intermediate-result").markSuccess()
          attrs.get(otoroshi.next.plugins.Keys.ResponseAddHeadersKey) match {
            case None             => error.asResult()
            case Some(addHeaders) => error.asResult().map(r => r.withHeaders(addHeaders: _*))
          }
        case Right(result) =>
          report.markSuccess()
          result.vfuture
      }
      .recover { case t: Throwable =>
        logger.error("last-recover", t)
        report.markFailure("last-recover", t)
        otoroshiJsonError(
          Json
            .obj("error" -> "internal_server_error", "error_description" -> t.getMessage)
            .applyOnIf(env.isDev) { obj => obj ++ Json.obj("report" -> report.json) },
          Results.InternalServerError,
          attrs.get(otoroshi.next.plugins.Keys.RouteKey),
          attrs,
          request
        )
      }
      .applyOnWithOpt(attrs.get(Keys.ResultTransformerKey)) { case (future, transformer) =>
        future.flatMap(transformer)
      }
      .andThen { case _ =>
        report.markOverheadOut()
        report.markDurations()
        closeCurrentRequest(env)
        attrs.get(Keys.RouteKey).foreach { route =>
          attrs
            .get(Keys.ContextualPluginsKey)
            .foreach(ctxplgs => callPluginsAfterRequestCallback(snowflake, request, route, ctxplgs))
          handleHighOverhead(request, route.some)
          if (tryIt) {
            tryItId.foreach(id => env.proxyState.addReport(id, report))
          }
          if (exportReporting || route.exportReporting) {
            RequestFlowReport(report, route).toAnalytics()
          }
        }
      }
      .applyOnIf( /*env.isDev && */ (debug || debugHeaders))(_.map { res =>
        val addHeaders =
          if (reporting && debugHeaders)
            Seq(
              "x-otoroshi-request-overhead"            -> report.overheadStr,
              "x-otoroshi-request-overhead-in"         -> report.overheadInStr,
              "x-otoroshi-request-overhead-out"        -> report.overheadOutStr,
              "x-otoroshi-request-duration"            -> report.gdurationStr,
              "x-otoroshi-request-call-duration"       -> report.getStep("call-backend").map(_.durationStr).getOrElse("--"),
              "x-otoroshi-request-find-route-duration" -> report
                .getStep("find-route")
                .map(_.durationStr)
                .getOrElse("--"),
              "x-otoroshi-request-state"               -> report.state.name,
              "x-otoroshi-request-creation"            -> report.creation.toString,
              "x-otoroshi-request-termination"         -> report.termination.toString
            ).applyOnIf(report.state == NgExecutionReportState.Failed) { seq =>
              seq :+ (
                "x-otoroshi-request-failure" ->
                report
                  .getStep("request-failure")
                  .flatMap(_.ctx.select("error").select("message").asOpt[String])
                  .getOrElse("--")
              )
            }
          else Seq.empty
        // if (debug) logger.info(report.json.prettify)
        // if (reporting && report.getStep("find-route").flatMap(_.ctx.select("found_route").select("debug_flow").asOpt[Boolean]).getOrElse(false)) {
        //   java.nio.file.Files.writeString(new java.io.File("./request-debug.json").toPath, report.json.prettify)
        // }
        res.withHeaders(addHeaders: _*)
      })
      .map { result =>
        result.copy(body = result.body match {
          case HttpEntity.NoEntity                      => HttpEntity.NoEntity
          case b @ HttpEntity.Strict(_, _)              => b
          case HttpEntity.Streamed(source, length, typ) =>
            HttpEntity
              .Streamed(source.alsoTo(Sink.onComplete { case _ => responseEndPromise.trySuccess(Done) }), length, typ)
          case HttpEntity.Chunked(source, typ)          =>
            HttpEntity.Chunked(source.alsoTo(Sink.onComplete { case _ => responseEndPromise.trySuccess(Done) }), typ)
        })
      }
  }

  @inline
  def handleWsRequest(request: RequestHeader, _config: ProxyEngineConfig)(implicit
      ec: ExecutionContext,
      env: Env,
      globalConfig: GlobalConfig
  ): Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]] = {
    val start                                                                                 = System.currentTimeMillis()
    val tryItId                                                                               = request.headers.get("Otoroshi-Try-It-Request-Id")
    val tryIt                                                                                 = tryItId.exists(id => env.proxyState.isReportEnabledFor(id))
    val requestId                                                                             = IdGenerator.uuid
    val ProxyEngineConfig(_, _, _, reporting, pluginMerge, exportReporting, _, _, _, _, _, _) = _config
    val useTree                                                                               = _config.useTree
    implicit val report                                                                       = NgExecutionReport(requestId, reporting)

    report.start("start-handling")
    implicit val mat = env.otoroshiMaterializer

    val snowflake        = env.snowflakeGenerator.nextIdStr()
    val callDate         = DateTime.now()
    val requestTimestamp = callDate.toString("yyyy-MM-dd'T'HH:mm:ss.SSSZZ")
    val reqNumber        = reqCounter.incrementAndGet()
    val counterIn        = new AtomicLong(0L)
    val counterOut       = new AtomicLong(0L)
    implicit val attrs   = TypedMap.empty.put(
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

    val fakeBody         = Source.empty[ByteString]
    val global_plugins__ = NgPlugins.readFrom(globalConfig.plugins.config.select("ng"))

    report.markDoneAndStart("check-concurrent-requests")
    (for {
      _         <- handleConcurrentRequest(request)
      _          = report.markDoneAndStart("find-route")
      route     <- findRoute(useTree, request, fakeBody, global_plugins__, tryIt)
      config     = route.metadata.get("otoroshi-core-apply-legacy-checks") match {
                     case Some("false") => _config.copy(applyLegacyChecks = false)
                     case Some("true")  => _config.copy(applyLegacyChecks = true)
                     case _             => _config
                   }
      _          = report.markDoneAndStart("compute-plugins")
      ctxPlugins = route.contextualPlugins(global_plugins__, pluginMerge, request).seffectOn(_.allPlugins)
      _          = attrs.put(Keys.ContextualPluginsKey -> ctxPlugins)
      _          = report.markDoneAndStart(
                     "tenant-check",
                     Json
                       .obj(
                         "disabled_plugins" -> ctxPlugins.disabledPlugins.map(p => JsString(p.plugin)),
                         "excluded_plugins" -> ctxPlugins.filteredPlugins.map(p => JsString(p.plugin)),
                         "included_plugins" -> ctxPlugins.allPlugins.map(p => JsString(p.plugin))
                       )
                       .some
                   )
      _         <- handleTenantCheck(route, request)
      _          = report.markDoneAndStart("check-global-maintenance")
      _         <- checkGlobalMaintenance(route, request, config)
      _          = report.markDoneAndStart("call-before-request-callbacks")
      _         <- callPluginsBeforeRequestCallback(snowflake, request, route, ctxPlugins)
      _          = report.markDoneAndStart("extract-tracking-id")
      _          = extractTrackingId(snowflake, request, reqNumber, route)
      _          = report.markDoneAndStart("call-pre-route-plugins")
      _         <- callPreRoutePlugins(snowflake, request, route, ctxPlugins)
      _          = report.markDoneAndStart("call-access-validator-plugins")
      _         <- callAccessValidatorPlugins(snowflake, request, route, ctxPlugins)
      // _          = report.markDoneAndStart("update-apikey-quotas")
      // _         <- updateApikeyQuotas(config)
      _          = report.markDoneAndStart("handle-legacy-checks")
      _         <- handleLegacyChecks(request, route, config)
      _          = report.markDoneAndStart(
                     "choose-backend",
                     attrs
                       .get(otoroshi.plugins.Keys.ApiKeyRemainingQuotasKey)
                       .map(remQuotas => Json.obj("remaining_quotas" -> remQuotas.toJson))
                   )
      result    <- callWsTarget(snowflake, reqNumber, request, route) {
                     case sb @ NgSelectedBackendTarget(backend, attempts, alreadyFailed, cbStart) =>
                       report.markDoneAndStart("transform-requests", Json.obj("backend" -> backend.json).some)
                       for {
                         finalRequest <- callRequestTransformer(snowflake, request, fakeBody, route, backend, ctxPlugins)
                         _             = report.markDoneAndStart("call-backend")
                         flow         <- callWsBackend(snowflake, request, finalRequest, route, backend, ctxPlugins)
                         _             = report.markDoneAndStart("trigger-analytics")
                         _            <- triggerWsProxyDone(snowflake, request, finalRequest, route, backend, sb)
                       } yield flow
                   }
    } yield {
      result
    }).value
      .flatMap {
        case Left(error) =>
          report.markDoneAndStart("rendering-intermediate-result").markSuccess()
          error.asResult().map(r => Left(r))
        case Right(flow) =>
          report.markSuccess()
          Right(flow).vfuture
      }
      .recover { case t: Throwable =>
        logger.error("last-recover", t)
        report.markFailure("last-recover", t)
        otoroshiJsonError(
          Json
            .obj("error" -> "internal_server_error", "error_description" -> t.getMessage)
            .applyOnIf(env.isDev) { obj => obj ++ Json.obj("report" -> report.json) },
          Results.InternalServerError,
          attrs.get(otoroshi.next.plugins.Keys.RouteKey),
          attrs,
          request
        ).left
      }
      .applyOnWithOpt(attrs.get(Keys.ResultTransformerKey)) { case (future, transformer) =>
        future.flatMap {
          case Left(r)      => transformer(r).map(Left.apply)
          case r @ Right(_) => r.vfuture
        }
      }
      .andThen { case _ =>
        report.markOverheadOut()
        report.markDurations()
        closeCurrentRequest(env)
        attrs.get(Keys.RouteKey).foreach { route =>
          callPluginsAfterRequestCallback(snowflake, request, route, attrs.get(Keys.ContextualPluginsKey).get)
          handleHighOverhead(request, route.some)
          if (tryIt) {
            tryItId.foreach(id => env.proxyState.addReport(id, report))
          }
          if (exportReporting || route.exportReporting) {
            RequestFlowReport(report, route).toAnalytics()
          }
        }
      }
  }

  def handleRelayTraffic(route: NgRoute, req: RequestHeader, body: Source[ByteString, _])(implicit
      ec: ExecutionContext,
      env: Env,
      report: NgExecutionReport,
      globalConfig: GlobalConfig,
      attrs: TypedMap,
      mat: Materializer
  ): FEither[NgProxyEngineError, Done] = {
    if (env.clusterConfig.relay.enabled) {
      val location                 = env.clusterConfig.relay.location
      val matchRack: Boolean       = if (route.hasDeploymentRacks) route.deploymentRacks.contains(location.rack) else true
      val matchDatacenter: Boolean =
        if (route.hasDeploymentDatacenters) route.deploymentDatacenters.contains(location.datacenter) else true
      val matchZone: Boolean       = if (route.hasDeploymentZones) route.deploymentZones.contains(location.zone) else true
      val matchRegion: Boolean     =
        if (route.hasDeploymentRegions) route.deploymentRegions.contains(location.region) else true
      val matchProvider: Boolean   =
        if (route.hasDeploymentProviders) route.deploymentProviders.contains(location.provider) else true
      val matching: Boolean        = matchRack && matchDatacenter && matchZone && matchRegion && matchProvider
      if (matching) {
        FEither.right(Done)
      } else {
        // Here, choose zone leader and forward the call
        FEither(env.datastores.clusterStateDataStore.getMembers().flatMap { members =>
          val possibleLeaders = new PossibleLeaders(members, route)
          val leader          = possibleLeaders.chooseNext(reqCounter)
          leader.call(req, body)
        })
      }
    } else {
      FEither.right(Done)
    }
  }

  def extractTrackingId(snowflake: String, req: RequestHeader, reqNumber: Int, route: NgRoute)(implicit
      attrs: TypedMap
  ): Unit = {
    if (route.backend.loadBalancing.needTrackingCookie) {
      val trackingId: String = req.cookies
        .get("otoroshi-tracking")
        .map(_.value)
        .getOrElse(IdGenerator.uuid + "-" + reqNumber)
      attrs.put(otoroshi.plugins.Keys.RequestTrackingIdKey -> trackingId)
    }
  }

  def handleHighOverhead(req: RequestHeader, route: Option[NgRoute])(implicit
      ec: ExecutionContext,
      env: Env,
      report: NgExecutionReport,
      globalConfig: GlobalConfig,
      attrs: TypedMap,
      mat: Materializer
  ): FEither[NgProxyEngineError, Done] = {
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

  def handleConcurrentRequest(request: RequestHeader)(implicit
      ec: ExecutionContext,
      env: Env,
      report: NgExecutionReport,
      globalConfig: GlobalConfig,
      attrs: TypedMap,
      mat: Materializer
  ): FEither[NgProxyEngineError, Done] = {
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
      FEither.apply(
        Errors
          .craftResponseResult(
            s"Cannot process more request",
            Results.TooManyRequests,
            request,
            None,
            Some("errors.cant.process.more.request"),
            attrs = attrs
          )
          .map(r => Left(NgResultProxyEngineError(r)))
      )
    } else {
      FEither.right(Done)
    }
  }

  def closeCurrentRequest(env: Env): Unit = {
    val requests = env.datastores.requestsDataStore.decrementHandledRequests()
    env.metrics.markLong(s"${env.snowflakeSeed}.concurrent-requests", requests)
  }

  def handleTenantCheck(route: NgRoute, request: RequestHeader)(implicit
      ec: ExecutionContext,
      env: Env,
      report: NgExecutionReport,
      globalConfig: GlobalConfig,
      attrs: TypedMap,
      mat: Materializer
  ): FEither[NgProxyEngineError, Done] = {
    if (
      env.clusterConfig.mode.isWorker
      && env.clusterConfig.worker.tenants.nonEmpty
      && !env.clusterConfig.worker.tenants.contains(route.location.tenant)
    ) {
      report.markFailure(s"this worker cannot serve tenant '${route.location.tenant.value}'")
      FEither.left(
        NgResultProxyEngineError(
          otoroshiJsonError(
            Json.obj("error" -> "not_found", "error_description" -> "no route found !"),
            Results.NotFound,
            attrs.get(otoroshi.next.plugins.Keys.RouteKey),
            attrs,
            request
          )
        )
      )
    } else {
      FEither.right(Done)
    }
  }

  def findRoute(
      useTree: Boolean,
      request: RequestHeader,
      body: Source[ByteString, _],
      global_plugins: NgPlugins,
      tryIt: Boolean
  )(implicit
      ec: ExecutionContext,
      env: Env,
      report: NgExecutionReport,
      globalConfig: GlobalConfig,
      attrs: TypedMap,
      mat: Materializer
  ): FEither[NgProxyEngineError, NgRoute] = {
    val routers                            = global_plugins.routerPlugins(request)
    val pluginRoute                        =
      if (routers.nonEmpty)
        routers.findFirstSome(p =>
          p.plugin.findRoute(
            NgRouterContext(
              request = request,
              config = p.instance.config.raw,
              attrs = attrs
            )
          )
        )
      else None
    val maybeRoute: Option[NgMatchedRoute] = pluginRoute.orElse {
      if (useTree) {
        env.proxyState.findRoute(request, attrs)
      } else {
        env.proxyState
          .getDomainRoutes(request.theDomain)
          .flatMap(
            _.find(
              _.matches(
                request,
                attrs,
                "/",
                scala.collection.mutable.HashMap.empty,
                noMoreSegments = false,
                skipDomainVerif = true,
                skipPathVerif = false
              )
            )
          )
          .map(r => NgMatchedRoute(r))
      }
    }
    maybeRoute match {
      case Some(_route) =>
        val route = if (tryIt) {
          val nroute: NgRoute = _route.route.copy(debugFlow = true)
          _route.copy(route = nroute)
        } else {
          _route
        }
        attrs.put(Keys.RouteKey -> route.route)
        attrs.put(Keys.MatchedRouteKey -> route)
        val rts: Seq[String] = attrs.get(Keys.MatchedRoutesKey).getOrElse(Seq.empty[String])
        report.setContext(
          Json.obj(
            "found_route"    -> route.route.json,
            "matched_path"   -> route.path,
            "exact"          -> route.noMoreSegments,
            "params"         -> route.pathParams,
            "matched_routes" -> rts
          )
        )
        FEither.right(route.route)
      case None         => callRequestSinkPlugins(request, body, global_plugins)
    }
  }

  def callRequestSinkPlugins(request: RequestHeader, body: Source[ByteString, _], global_plugins: NgPlugins)(implicit
      ec: ExecutionContext,
      env: Env,
      report: NgExecutionReport,
      globalConfig: GlobalConfig,
      attrs: TypedMap,
      mat: Materializer
  ): FEither[NgProxyEngineError, NgRoute] = {
    def failure(): FEither[NgProxyEngineError, NgRoute] = {
      report.markFailure(s"route not found for domain: '${request.theDomain}${request.thePath}'")
      FEither.left(
        NgResultProxyEngineError(
          otoroshiJsonError(
            Json.obj("error" -> "not_found", "error_description" -> "no route found !"),
            Results.NotFound,
            attrs.get(otoroshi.next.plugins.Keys.RouteKey),
            attrs,
            request
          )
        )
      )
    }
    val all_plugins = global_plugins.requestSinkPlugins(request)

    // TODO - async version
//    if (all_plugins.nonEmpty) {
//      val wrappers = all_plugins
//        .map { wrapper =>
//          val ctx = NgRequestSinkContext(
//            snowflake = attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).get,
//            request = request,
//            config = wrapper.instance.config.raw,
//            attrs = attrs,
//            origin = NgRequestOrigin.NgReverseProxy,
//            status = 404,
//            message = s"route not found",
//            body = body
//          )
//          (wrapper, ctx)
//        }
//      FEither.apply[NgProxyEngineError, NgRoute] (
//        Future.sequence(wrappers.map { case (wrapper, ctx) =>  wrapper.plugin.matches(ctx)})
//          .flatMap(results => {
//             results.indexWhere(matched => matched) match {
//               case -1 => failure().value
//               case n =>
//                 val (wrapper, ctx) = wrappers(n)
//                 FEither.apply[NgProxyEngineError, NgRoute] (
//                  wrapper.plugin.handle (ctx).map (r => Left (NgResultProxyEngineError (r) ) )
//                 ).value
//             }
//          })
//      )
//    } else {
//      failure()
//    }
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
        .find { case (wrapper, ctx) =>
          wrapper.plugin.matches(ctx)
        }
        .map { case (wrapper, ctx) =>
          FEither.apply[NgProxyEngineError, NgRoute](
            wrapper.plugin.handle(ctx).map(r => Left(NgResultProxyEngineError(r)))
          )
        }
        .getOrElse {
          failure()
        }
    } else {
      failure()
    }
  }

  def checkGlobalMaintenance(route: NgRoute, request: RequestHeader, config: ProxyEngineConfig)(implicit
      ec: ExecutionContext,
      env: Env,
      report: NgExecutionReport,
      globalConfig: GlobalConfig,
      attrs: TypedMap,
      mat: Materializer
  ): FEither[NgProxyEngineError, Done] = {
    if (config.applyLegacyChecks) {
      if (route.id != env.backOfficeServiceId && globalConfig.maintenanceMode) {
        report.markFailure(s"global maintenance activated")
        FEither.left(
          NgResultProxyEngineError(
            otoroshiJsonError(
              Json.obj("error" -> "service_unavailable", "error_description" -> "Service in maintenance mode"),
              Results.ServiceUnavailable,
              attrs.get(otoroshi.next.plugins.Keys.RouteKey),
              attrs,
              request
            )
          )
        )
      } else {
        FEither.right(Done)
      }
    } else {
      FEither.right(Done)
    }
  }

  def callPluginsBeforeRequestCallback(
      snowflake: String,
      request: RequestHeader,
      route: NgRoute,
      plugins: NgContextualPlugins
  )(implicit
      ec: ExecutionContext,
      env: Env,
      report: NgExecutionReport,
      globalConfig: GlobalConfig,
      attrs: TypedMap,
      mat: Materializer
  ): FEither[NgProxyEngineError, Done] = {
    val all_plugins = plugins.transformerPluginsWithCallbacks
    if (all_plugins.nonEmpty) {
      var sequence = NgReportPluginSequence(
        size = all_plugins.size,
        kind = "before-request-plugins",
        start = System.currentTimeMillis(),
        stop = 0L,
        start_ns = System.nanoTime(),
        stop_ns = 0L,
        plugins = Seq.empty
      )
      val _ctx     = NgBeforeRequestContext(
        snowflake = snowflake,
        request = request,
        route = route,
        config = Json.obj(),
        globalConfig = globalConfig.plugins.config,
        attrs = attrs
      )
      def markPluginItem(
          item: NgReportPluginSequenceItem,
          ctx: NgBeforeRequestContext,
          debug: Boolean,
          result: JsValue
      ): Unit = {
        sequence = sequence.copy(
          plugins = sequence.plugins :+ item.copy(
            stop = System.currentTimeMillis(),
            stop_ns = System.nanoTime(),
            out = Json
              .obj(
                "not_triggered" -> plugins.tpwoCallbacks.map(_.instance.plugin),
                "result"        -> result
              )
              .applyOnIf(debug)(_ ++ Json.obj("ctx" -> ctx.json))
          )
        )
      }
      if (all_plugins.size == 1) {
        val wrapper               = all_plugins.head
        val pluginConfig: JsValue = wrapper.plugin.defaultConfig
          .map(dc => dc ++ wrapper.instance.config.raw)
          .getOrElse(wrapper.instance.config.raw)
        val ctx                   = _ctx.copy(config = pluginConfig)
        val debug                 = route.debugFlow || wrapper.instance.debug
        val in: JsValue           = if (debug) Json.obj("ctx" -> ctx.json) else JsNull
        val item                  = NgReportPluginSequenceItem(
          wrapper.instance.plugin,
          wrapper.plugin.name,
          System.currentTimeMillis(),
          System.nanoTime(),
          -1L,
          -1L,
          in,
          JsNull
        )
        FEither(
          wrapper.plugin
            .beforeRequest(ctx)
            .map { _ =>
              markPluginItem(item, ctx, debug, Json.obj("kind" -> "successful"))
              report.setContext(sequence.stopSequence().json)
              Right(Done)
            }
            .recover { case exception: Throwable =>
              markPluginItem(
                item,
                ctx,
                debug,
                Json.obj("kind" -> "failure", "error" -> JsonHelpers.errToJson(exception))
              )
              report.setContext(sequence.stopSequence().json)
              Left(
                NgResultProxyEngineError(
                  otoroshiJsonError(
                    Json
                      .obj(
                        "error"             -> "internal_server_error",
                        "error_description" -> "an error happened during before-request plugins phase"
                      )
                      .applyOnIf(env.isDev) { obj => obj ++ Json.obj("jvm_error" -> JsonHelpers.errToJson(exception)) },
                    Results.InternalServerError,
                    attrs.get(otoroshi.next.plugins.Keys.RouteKey),
                    attrs,
                    request
                  )
                )
              )
            }
        )
      } else {
        val promise = Promise[Either[NgProxyEngineError, Done]]()
        def next(plugins: Seq[NgPluginWrapper[NgRequestTransformer]]): Unit = {
          plugins.headOption match {
            case None          => promise.trySuccess(Right(Done))
            case Some(wrapper) => {
              val pluginConfig: JsValue = wrapper.plugin.defaultConfig
                .map(dc => dc ++ wrapper.instance.config.raw)
                .getOrElse(wrapper.instance.config.raw)
              val ctx                   = _ctx.copy(config = pluginConfig, idx = wrapper.instance.instanceId)
              val debug                 = route.debugFlow || wrapper.instance.debug
              val in: JsValue           = if (debug) Json.obj("ctx" -> ctx.json) else JsNull
              val item                  = NgReportPluginSequenceItem(
                wrapper.instance.plugin,
                wrapper.plugin.name,
                System.currentTimeMillis(),
                System.nanoTime(),
                -1L,
                -1L,
                in,
                JsNull
              )
              wrapper.plugin.beforeRequest(ctx).andThen {
                case Failure(exception)              =>
                  markPluginItem(
                    item,
                    ctx,
                    debug,
                    Json.obj("kind" -> "failure", "error" -> JsonHelpers.errToJson(exception))
                  )
                  report.setContext(sequence.stopSequence().json)
                  promise.trySuccess(
                    Left(
                      NgResultProxyEngineError(
                        otoroshiJsonError(
                          Json
                            .obj(
                              "error"             -> "internal_server_error",
                              "error_description" -> "an error happened during before-request plugins phase"
                            )
                            .applyOnIf(env.isDev) { obj =>
                              obj ++ Json.obj("jvm_error" -> JsonHelpers.errToJson(exception))
                            },
                          Results.InternalServerError,
                          attrs.get(otoroshi.next.plugins.Keys.RouteKey),
                          attrs,
                          request
                        )
                      )
                    )
                  )
                case Success(_) if plugins.size == 1 =>
                  markPluginItem(item, ctx, debug, Json.obj("kind" -> "successful"))
                  report.setContext(sequence.stopSequence().json)
                  promise.trySuccess(Right(Done))
                case Success(_)                      =>
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

  def callPluginsAfterRequestCallback(
      snowflake: String,
      request: RequestHeader,
      route: NgRoute,
      plugins: NgContextualPlugins
  )(implicit
      ec: ExecutionContext,
      env: Env,
      report: NgExecutionReport,
      globalConfig: GlobalConfig,
      attrs: TypedMap,
      mat: Materializer
  ): FEither[NgProxyEngineError, Done] = {
    val all_plugins = plugins.transformerPluginsWithCallbacks
    if (all_plugins.nonEmpty) {
      var sequence = NgReportPluginSequence(
        size = all_plugins.size,
        kind = "after-request-plugins",
        start = System.currentTimeMillis(),
        stop = 0L,
        start_ns = System.nanoTime(),
        stop_ns = 0L,
        plugins = Seq.empty
      )
      val _ctx     = NgAfterRequestContext(
        snowflake = snowflake,
        request = request,
        route = route,
        config = Json.obj(),
        globalConfig = globalConfig.plugins.config,
        attrs = attrs
      )
      def markPluginItem(
          item: NgReportPluginSequenceItem,
          ctx: NgAfterRequestContext,
          debug: Boolean,
          result: JsValue
      ): Unit = {
        sequence = sequence.copy(
          plugins = sequence.plugins :+ item.copy(
            stop = System.currentTimeMillis(),
            stop_ns = System.nanoTime(),
            out = Json
              .obj(
                "not_triggered" -> plugins.tpwoCallbacks.map(_.instance.plugin),
                "result"        -> result
              )
              .applyOnIf(debug)(_ ++ Json.obj("ctx" -> ctx.json))
          )
        )
      }
      if (all_plugins.size == 1) {
        val wrapper               = all_plugins.head
        val pluginConfig: JsValue = wrapper.plugin.defaultConfig
          .map(dc => dc ++ wrapper.instance.config.raw)
          .getOrElse(wrapper.instance.config.raw)
        val ctx                   = _ctx.copy(config = pluginConfig)
        val debug                 = route.debugFlow || wrapper.instance.debug
        val in: JsValue           = if (debug) Json.obj("ctx" -> ctx.json) else JsNull
        val item                  = NgReportPluginSequenceItem(
          wrapper.instance.plugin,
          wrapper.plugin.name,
          System.currentTimeMillis(),
          System.nanoTime(),
          -1L,
          -1L,
          in,
          JsNull
        )
        FEither(
          wrapper.plugin
            .afterRequest(ctx)
            .map { _ =>
              markPluginItem(item, ctx, debug, Json.obj("kind" -> "successful"))
              report.setContext(sequence.stopSequence().json)
              Right(Done)
            }
            .recover { case exception: Throwable =>
              markPluginItem(
                item,
                ctx,
                debug,
                Json.obj("kind" -> "failure", "error" -> JsonHelpers.errToJson(exception))
              )
              report.setContext(sequence.stopSequence().json)
              Left(
                NgResultProxyEngineError(
                  otoroshiJsonError(
                    Json
                      .obj(
                        "error"             -> "internal_server_error",
                        "error_description" -> "an error happened during after-request plugins phase"
                      )
                      .applyOnIf(env.isDev) { obj => obj ++ Json.obj("jvm_error" -> JsonHelpers.errToJson(exception)) },
                    Results.InternalServerError,
                    attrs.get(otoroshi.next.plugins.Keys.RouteKey),
                    attrs,
                    request
                  )
                )
              )
            }
        )
      } else {
        val promise = Promise[Either[NgProxyEngineError, Done]]()
        def next(plugins: Seq[NgPluginWrapper[NgRequestTransformer]]): Unit = {
          plugins.headOption match {
            case None          => promise.trySuccess(Right(Done))
            case Some(wrapper) => {
              val pluginConfig: JsValue = wrapper.plugin.defaultConfig
                .map(dc => dc ++ wrapper.instance.config.raw)
                .getOrElse(wrapper.instance.config.raw)
              val ctx                   = _ctx.copy(config = pluginConfig, idx = wrapper.instance.instanceId)
              val debug                 = route.debugFlow || wrapper.instance.debug
              val in: JsValue           = if (debug) Json.obj("ctx" -> ctx.json) else JsNull
              val item                  = NgReportPluginSequenceItem(
                wrapper.instance.plugin,
                wrapper.plugin.name,
                System.currentTimeMillis(),
                System.nanoTime(),
                -1L,
                -1L,
                in,
                JsNull
              )
              wrapper.plugin.afterRequest(ctx).andThen {
                case Failure(exception)              =>
                  markPluginItem(
                    item,
                    ctx,
                    debug,
                    Json.obj("kind" -> "failure", "error" -> JsonHelpers.errToJson(exception))
                  )
                  report.setContext(sequence.stopSequence().json)
                  promise.trySuccess(
                    Left(
                      NgResultProxyEngineError(
                        otoroshiJsonError(
                          Json
                            .obj(
                              "error"             -> "internal_server_error",
                              "error_description" -> "an error happened during before-request plugins phase"
                            )
                            .applyOnIf(env.isDev) { obj =>
                              obj ++ Json.obj("jvm_error" -> JsonHelpers.errToJson(exception))
                            },
                          Results.InternalServerError,
                          attrs.get(otoroshi.next.plugins.Keys.RouteKey),
                          attrs,
                          request
                        )
                      )
                    )
                  )
                case Success(_) if plugins.size == 1 =>
                  markPluginItem(item, ctx, debug, Json.obj("kind" -> "successful"))
                  report.setContext(sequence.stopSequence().json)
                  promise.trySuccess(Right(Done))
                case Success(_)                      =>
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

  def callPreRoutePlugins(snowflake: String, request: RequestHeader, route: NgRoute, plugins: NgContextualPlugins)(
      implicit
      ec: ExecutionContext,
      env: Env,
      report: NgExecutionReport,
      globalConfig: GlobalConfig,
      attrs: TypedMap,
      mat: Materializer
  ): FEither[NgProxyEngineError, Done] = {
    val all_plugins = plugins.preRoutePlugins
    if (all_plugins.nonEmpty) {
      var sequence = NgReportPluginSequence(
        size = all_plugins.size,
        kind = "pre-route-plugins",
        start = System.currentTimeMillis(),
        stop = 0L,
        start_ns = System.nanoTime(),
        stop_ns = 0L,
        plugins = Seq.empty
      )
      def markPluginItem(
          item: NgReportPluginSequenceItem,
          ctx: NgPreRoutingContext,
          debug: Boolean,
          result: JsValue
      ): Unit = {
        sequence = sequence.copy(
          plugins = sequence.plugins :+ item.copy(
            stop = System.currentTimeMillis(),
            stop_ns = System.nanoTime(),
            out = Json
              .obj(
                "result" -> result
              )
              .applyOnIf(debug)(_ ++ Json.obj("ctx" -> ctx.json))
          )
        )
      }
      val _ctx     = NgPreRoutingContext(
        snowflake = snowflake,
        request = request,
        route = route,
        config = Json.obj(),
        globalConfig = globalConfig.plugins.config,
        attrs = attrs,
        report = report,
        sequence = sequence,
        markPluginItem = markPluginItem
      )
      if (all_plugins.size == 1) {
        val wrapper               = all_plugins.head
        val pluginConfig: JsValue = wrapper.plugin.defaultConfig
          .map(dc => dc ++ wrapper.instance.config.raw)
          .getOrElse(wrapper.instance.config.raw)
        val ctx                   = _ctx.copy(config = pluginConfig)
        val debug                 = route.debugFlow || wrapper.instance.debug
        val in: JsValue           = if (debug) Json.obj("ctx" -> ctx.json) else JsNull
        val item                  = NgReportPluginSequenceItem(
          wrapper.instance.plugin,
          wrapper.plugin.name,
          System.currentTimeMillis(),
          System.nanoTime(),
          -1L,
          -1L,
          in,
          JsNull
        )
        FEither(
          wrapper.plugin
            .preRoute(ctx)
            .transform {
              case Failure(exception) =>
                markPluginItem(
                  item,
                  ctx,
                  debug,
                  Json.obj("kind" -> "failure", "error" -> JsonHelpers.errToJson(exception))
                )
                report.setContext(sequence.stopSequence().json)
                Success(
                  Left(
                    NgResultProxyEngineError(
                      otoroshiJsonError(
                        Json
                          .obj(
                            "error"             -> "internal_server_error",
                            "error_description" -> "an error happened during pre-routing plugins phase"
                          )
                          .applyOnIf(env.isDev) { obj =>
                            obj ++ Json.obj("jvm_error" -> JsonHelpers.errToJson(exception))
                          },
                        Results.InternalServerError,
                        attrs.get(otoroshi.next.plugins.Keys.RouteKey),
                        attrs,
                        request
                      )
                    )
                  )
                )
              case Success(Left(err)) =>
                val result = err.result
                markPluginItem(
                  item,
                  ctx,
                  debug,
                  Json.obj(
                    "kind"    -> "short-circuit",
                    "status"  -> result.header.status,
                    "headers" -> result.header.headers
                  )
                )
                report.setContext(sequence.stopSequence().json)
                Success(Left(NgResultProxyEngineError(result)))
              case Success(Right(_))  =>
                markPluginItem(item, ctx, debug, Json.obj("kind" -> "successful"))
                report.setContext(sequence.stopSequence().json)
                Success(Right(Done))
            }
        )
      } else {
        val promise = Promise[Either[NgProxyEngineError, Done]]()
        def next(plugins: Seq[NgPluginWrapper[NgPreRouting]]): Unit = {
          plugins.headOption match {
            case None          => promise.trySuccess(Right(Done))
            case Some(wrapper) => {
              val pluginConfig: JsValue = wrapper.plugin.defaultConfig
                .map(dc => dc ++ wrapper.instance.config.raw)
                .getOrElse(wrapper.instance.config.raw)
              val ctx                   = _ctx.copy(config = pluginConfig)
              val debug                 = route.debugFlow || wrapper.instance.debug
              val in: JsValue           = if (debug) Json.obj("ctx" -> ctx.json) else JsNull
              val item                  = NgReportPluginSequenceItem(
                wrapper.instance.plugin,
                wrapper.plugin.name,
                System.currentTimeMillis(),
                System.nanoTime(),
                -1L,
                -1L,
                in,
                JsNull
              )
              wrapper.plugin.preRoute(ctx).andThen {
                case Failure(exception)                     =>
                  markPluginItem(
                    item,
                    ctx,
                    debug,
                    Json.obj("kind" -> "failure", "error" -> JsonHelpers.errToJson(exception))
                  )
                  report.setContext(sequence.stopSequence().json)
                  promise.trySuccess(
                    Left(
                      NgResultProxyEngineError(
                        otoroshiJsonError(
                          Json
                            .obj(
                              "error"             -> "internal_server_error",
                              "error_description" -> "an error happened during pre-routing plugins phase"
                            )
                            .applyOnIf(env.isDev) { obj =>
                              obj ++ Json.obj("jvm_error" -> JsonHelpers.errToJson(exception))
                            },
                          Results.InternalServerError,
                          attrs.get(otoroshi.next.plugins.Keys.RouteKey),
                          attrs,
                          request
                        )
                      )
                    )
                  )
                case Success(Left(err))                     =>
                  val result = err.result
                  markPluginItem(
                    item,
                    ctx,
                    debug,
                    Json.obj(
                      "kind"    -> "short-circuit",
                      "status"  -> result.header.status,
                      "headers" -> result.header.headers
                    )
                  )
                  report.setContext(sequence.stopSequence().json)
                  promise.trySuccess(Left(NgResultProxyEngineError(result)))
                case Success(Right(_)) if plugins.size == 1 =>
                  markPluginItem(item, ctx, debug, Json.obj("kind" -> "successful"))
                  report.setContext(sequence.stopSequence().json)
                  promise.trySuccess(Right(Done))
                case Success(Right(_))                      =>
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

  def callAccessValidatorPlugins(
      snowflake: String,
      request: RequestHeader,
      route: NgRoute,
      plugins: NgContextualPlugins
  )(implicit
      ec: ExecutionContext,
      env: Env,
      report: NgExecutionReport,
      globalConfig: GlobalConfig,
      attrs: TypedMap,
      mat: Materializer
  ): FEither[NgProxyEngineError, Done] = {
    val all_plugins = plugins.accessValidatorPlugins
    if (all_plugins.nonEmpty) {
      var sequence = NgReportPluginSequence(
        size = all_plugins.size,
        kind = "access-validator-plugins",
        start = System.currentTimeMillis(),
        stop = 0L,
        start_ns = System.nanoTime(),
        stop_ns = 0L,
        plugins = Seq.empty
      )
      def markPluginItem(
          item: NgReportPluginSequenceItem,
          ctx: NgAccessContext,
          debug: Boolean,
          result: JsValue
      ): Unit = {
        sequence = sequence.copy(
          plugins = sequence.plugins :+ item.copy(
            stop = System.currentTimeMillis(),
            stop_ns = System.nanoTime(),
            out = Json
              .obj(
                "result" -> result
              )
              .applyOnIf(debug)(_ ++ Json.obj("ctx" -> ctx.json))
          )
        )
      }
      val _ctx     = NgAccessContext(
        snowflake = snowflake,
        request = request,
        route = route,
        config = Json.obj(),
        globalConfig = globalConfig.plugins.config,
        attrs = attrs,
        apikey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey),
        user = attrs.get(otoroshi.plugins.Keys.UserKey),
        report = report,
        sequence = sequence,
        markPluginItem = markPluginItem
      )
      if (all_plugins.size == 1) {
        val wrapper               = all_plugins.head
        val pluginConfig: JsValue = wrapper.plugin.defaultConfig
          .map(dc => dc ++ wrapper.instance.config.raw)
          .getOrElse(wrapper.instance.config.raw)
        val ctx                   = _ctx.copy(config = pluginConfig)
        val debug                 = route.debugFlow || wrapper.instance.debug
        val in: JsValue           = if (debug) Json.obj("ctx" -> ctx.json) else JsNull
        val item                  = NgReportPluginSequenceItem(
          wrapper.instance.plugin,
          wrapper.plugin.name,
          System.currentTimeMillis(),
          System.nanoTime(),
          -1L,
          -1L,
          in,
          JsNull
        )
        FEither(wrapper.plugin.access(ctx).transform {
          case Failure(exception)                 =>
            markPluginItem(item, ctx, debug, Json.obj("kind" -> "failure", "error" -> JsonHelpers.errToJson(exception)))
            report.setContext(sequence.stopSequence().json)
            Success(
              Left(
                NgResultProxyEngineError(
                  otoroshiJsonError(
                    Json
                      .obj(
                        "error"             -> "internal_server_error",
                        "error_description" -> "an error happened during pre-routing plugins phase"
                      )
                      .applyOnIf(env.isDev) { obj => obj ++ Json.obj("jvm_error" -> JsonHelpers.errToJson(exception)) },
                    Results.InternalServerError,
                    attrs.get(otoroshi.next.plugins.Keys.RouteKey),
                    attrs,
                    request
                  )
                )
              )
            )
          case Success(NgAccess.NgDenied(result)) =>
            markPluginItem(item, ctx, debug, Json.obj("kind" -> "denied", "status" -> result.header.status))
            report.setContext(sequence.stopSequence().json)
            Success(Left(NgResultProxyEngineError(result)))
          case Success(NgAccess.NgAllowed)        =>
            markPluginItem(item, ctx, debug, Json.obj("kind" -> "allowed"))
            report.setContext(sequence.stopSequence().json)
            Success(Right(Done))
        })
      } else {
        val promise = Promise[Either[NgProxyEngineError, Done]]()
        def next(plugins: Seq[NgPluginWrapper[NgAccessValidator]]): Unit = {
          plugins.headOption match {
            case None          => promise.trySuccess(Right(Done))
            case Some(wrapper) => {
              val pluginConfig: JsValue = wrapper.plugin.defaultConfig
                .map(dc => dc ++ wrapper.instance.config.raw)
                .getOrElse(wrapper.instance.config.raw)
              val ctx                   = _ctx.copy(
                config = pluginConfig,
                apikey = _ctx.apikey.orElse(attrs.get(otoroshi.plugins.Keys.ApiKeyKey)),
                user = _ctx.user.orElse(attrs.get(otoroshi.plugins.Keys.UserKey)),
                idx = wrapper.instance.instanceId
              )
              val debug                 = route.debugFlow || wrapper.instance.debug
              val in: JsValue           = if (debug) Json.obj("ctx" -> ctx.json) else JsNull
              val item                  = NgReportPluginSequenceItem(
                wrapper.instance.plugin,
                wrapper.plugin.name,
                System.currentTimeMillis(),
                System.nanoTime(),
                -1L,
                -1L,
                in,
                JsNull
              )
              wrapper.plugin.access(ctx).andThen {
                case Failure(exception)                               =>
                  markPluginItem(
                    item,
                    ctx,
                    debug,
                    Json.obj("kind" -> "failure", "error" -> JsonHelpers.errToJson(exception))
                  )
                  report.setContext(sequence.stopSequence().json)
                  promise.trySuccess(
                    Left(
                      NgResultProxyEngineError(
                        otoroshiJsonError(
                          Json
                            .obj(
                              "error"             -> "internal_server_error",
                              "error_description" -> "an error happened during pre-routing plugins phase"
                            )
                            .applyOnIf(env.isDev) { obj =>
                              obj ++ Json.obj("jvm_error" -> JsonHelpers.errToJson(exception))
                            },
                          Results.InternalServerError,
                          attrs.get(otoroshi.next.plugins.Keys.RouteKey),
                          attrs,
                          request
                        )
                      )
                    )
                  )
                case Success(NgAccess.NgDenied(result))               =>
                  markPluginItem(item, ctx, debug, Json.obj("kind" -> "denied", "status" -> result.header.status))
                  report.setContext(sequence.stopSequence().json)
                  promise.trySuccess(Left(NgResultProxyEngineError(result)))
                case Success(NgAccess.NgAllowed) if plugins.size == 1 =>
                  markPluginItem(item, ctx, debug, Json.obj("kind" -> "allowed"))
                  report.setContext(sequence.stopSequence().json)
                  promise.trySuccess(Right(Done))
                case Success(NgAccess.NgAllowed)                      =>
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

  // def updateApikeyQuotas(config: ProxyEngineConfig)(implicit
  //     ec: ExecutionContext,
  //     env: Env,
  //     report: NgExecutionReport,
  //     globalConfig: GlobalConfig,
  //     attrs: TypedMap,
  //     mat: Materializer
  // ): FEither[NgProxyEngineError, RemainingQuotas] = {
  //   FEither.right(attrs.get(otoroshi.plugins.Keys.ApiKeyRemainingQuotasKey).getOrElse(RemainingQuotas()))
  //   // if (config.applyLegacyChecks) {
  //   //   // increments calls for apikey
  //   //   val quotas = attrs
  //   //     .get(otoroshi.plugins.Keys.ApiKeyKey)
  //   //     .map(_.updateQuotas())
  //   //     .getOrElse(RemainingQuotas().vfuture)
  //   //     .andThen { case Success(value) =>
  //   //       attrs.put(otoroshi.plugins.Keys.ApiKeyRemainingQuotasKey -> value)
  //   //     }
  //   //     .map(rq => Right.apply[NgProxyEngineError, RemainingQuotas](rq))
  //   //   FEither(quotas)
  //   // } else {
  //   //   FEither.right(RemainingQuotas())
  //   // }
  // }

  def handleLegacyChecks(request: RequestHeader, route: NgRoute, config: ProxyEngineConfig)(implicit
      ec: ExecutionContext,
      env: Env,
      report: NgExecutionReport,
      globalConfig: GlobalConfig,
      attrs: TypedMap,
      mat: Materializer
  ): FEither[NgProxyEngineError, Done] = {
    if (config.applyLegacyChecks) {
      // generic.scala (1269)
      val remoteAddress = request.theIpAddress
      def errorResult(
          status: Results.Status,
          message: String,
          code: String
      ): Future[Either[NgProxyEngineError, Done]] = {
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
            maybeRoute = route.some
          )
          .map(e => Left(NgResultProxyEngineError(e)))
      }
      // quotasValidationFor increments calls for ip address
      FEither(env.datastores.globalConfigDataStore.quotasValidationFor(remoteAddress).flatMap { r =>
        val (within, secCalls, maybeQuota) = r
        val quota                          = maybeQuota.getOrElse(globalConfig.perIpThrottlingQuota)
        if (secCalls > (quota * 10L)) {
          errorResult(Results.TooManyRequests, "[IP] You performed too much requests", "errors.too.much.requests")
        } else {
          if (!within) {
            errorResult(Results.TooManyRequests, "[GLOBAL] You performed too much requests", "errors.too.much.requests")
          } else if (globalConfig.ipFiltering.notMatchesWhitelist(remoteAddress)) {
            errorResult(
              Results.Forbidden,
              "Your IP address is not allowed",
              "errors.ip.address.not.allowed"
            ) // global whitelist
          } else if (globalConfig.ipFiltering.matchesBlacklist(remoteAddress)) {
            errorResult(
              Results.Forbidden,
              "Your IP address is not allowed",
              "errors.ip.address.not.allowed"
            ) // global blacklist
          } else if (globalConfig.matchesEndlessIpAddresses(remoteAddress)) {
            val gigas: Long            = 128L * 1024L * 1024L * 1024L
            val middleFingers          = ByteString.fromString(
              "\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95\uD83D\uDD95"
            )
            val zeros                  =
              ByteString.fromInts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
            val characters: ByteString =
              if (!globalConfig.middleFingers) middleFingers else zeros
            val expected: Long         = (gigas / characters.size) + 1L
            Left(
              NgResultProxyEngineError(
                Results
                  .Status(200)
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
            ).vfuture
          } else {
            Done.right.vfuture
          }
        }
      })
    } else {
      FEither.right(Done)
    }
  }

  def getBackend(target: Target, route: NgRoute, attrs: TypedMap)(implicit env: Env): NgTarget = {
    attrs
      .get(otoroshi.plugins.Keys.PreExtractedRequestTargetsKey)
      .getOrElse(route.backend.allTargets)
      .find(b => b.id == target.tags.head)
      .get
  }

  def callTarget(snowflake: String, reqNumber: Long, request: Request[Source[ByteString, _]], _route: NgRoute)(
      f: NgSelectedBackendTarget => FEither[NgProxyEngineError, Result]
  )(implicit
      ec: ExecutionContext,
      env: Env,
      report: NgExecutionReport,
      globalConfig: GlobalConfig,
      attrs: TypedMap,
      mat: Materializer
  ): FEither[NgProxyEngineError, Result] = {
    val cbStart             = System.currentTimeMillis()
    val route               =
      attrs.get(otoroshi.next.plugins.Keys.PossibleBackendsKey).map(b => _route.copy(backend = b)).getOrElse(_route)
    val trackingId          = attrs.get(otoroshi.plugins.Keys.RequestTrackingIdKey).getOrElse(IdGenerator.uuid)
    val bodyAlreadyConsumed = new AtomicBoolean(false)
    attrs.put(Keys.BodyAlreadyConsumedKey -> bodyAlreadyConsumed)

    if (globalConfig.useCircuitBreakers) {
      val counter            = new AtomicInteger(0)
      val relUri             = request.relativeUri
      val cachedPath: String =
        route.backend.client.legacy
          .timeouts(relUri)
          .map(_ => relUri)
          .getOrElse("")

      def callF(target: Target, attempts: Int, alreadyFailed: AtomicBoolean): Future[Either[Result, Result]] = {
        val backend = getBackend(target, route, attrs)
        attrs.put(Keys.BackendKey -> backend)
        f(NgSelectedBackendTarget(backend, attempts, alreadyFailed, cbStart)).value.flatMap {
          case Left(err)        => err.asResult().map(Left.apply)
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
                maybeRoute = route.some
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
                maybeRoute = route.some
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
                maybeRoute = route.some
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
                maybeRoute = route.some
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
                maybeRoute = route.some
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
                maybeRoute = route.some
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
                maybeRoute = route.some
              )
              .map(Left.apply)
        }
      }

      implicit val scheduler = env.otoroshiScheduler

      FEither(
        env.circuitBeakersHolder
          .get(
            route.cacheableId + cachedPath,
            () => new ServiceDescriptorCircuitBreaker()
          )
          .callGenNg[Result](
            route.cacheableId,
            route.name,
            attrs
              .get(otoroshi.plugins.Keys.PreExtractedRequestTargetsKey)
              .getOrElse(route.backend.allTargets)
              .map(_.toTarget),
            route.backend.loadBalancing,
            route.backend.client.legacy,
            reqNumber.toString,
            trackingId,
            request.relativeUri,
            request,
            bodyAlreadyConsumed,
            s"${request.method} ${request.relativeUri}",
            counter,
            attrs,
            callF
          ) recoverWith { case t: Throwable =>
          handleError(t)
        } map {
          case Left(res)    => Left(NgResultProxyEngineError(res))
          case Right(value) => Right(value)
        }
      )
    } else {
      val target  = attrs
        .get(otoroshi.plugins.Keys.PreExtractedRequestTargetKey)
        .getOrElse {
          val targets: Seq[Target] = attrs
            .get(otoroshi.plugins.Keys.PreExtractedRequestTargetsKey)
            .getOrElse(route.backend.allTargets)
            .map(_.toTarget)
            .filter(_.predicate.matches(reqNumber.toString, request, attrs))
            .flatMap(t => Seq.fill(t.weight)(t))
          route.backend.loadBalancing
            .select(
              reqNumber.toString,
              trackingId,
              request,
              targets,
              route.cacheableId
            )
        }
      //val index = reqCounter.get() % (if (targets.nonEmpty) targets.size else 1)
      // Round robin loadbalancing is happening here !!!!!
      //val target = targets.apply(index.toInt)
      val backend = getBackend(target, route, attrs)
      attrs.put(Keys.BackendKey -> backend)
      f(NgSelectedBackendTarget(backend, 1, new AtomicBoolean(false), cbStart))
    }
  }

  def callWsTarget(snowflake: String, reqNumber: Long, request: RequestHeader, _route: NgRoute)(
      f: NgSelectedBackendTarget => FEither[NgProxyEngineError, Flow[PlayWSMessage, PlayWSMessage, _]]
  )(implicit
      ec: ExecutionContext,
      env: Env,
      report: NgExecutionReport,
      globalConfig: GlobalConfig,
      attrs: TypedMap,
      mat: Materializer
  ): FEither[NgProxyEngineError, Flow[PlayWSMessage, PlayWSMessage, _]] = {
    val cbStart             = System.currentTimeMillis()
    val route               =
      attrs.get(otoroshi.next.plugins.Keys.PossibleBackendsKey).map(b => _route.copy(backend = b)).getOrElse(_route)
    val trackingId          = attrs.get(otoroshi.plugins.Keys.RequestTrackingIdKey).getOrElse(IdGenerator.uuid)
    val bodyAlreadyConsumed = new AtomicBoolean(false)
    attrs.put(Keys.BodyAlreadyConsumedKey -> bodyAlreadyConsumed)
    if (globalConfig.useCircuitBreakers) {
      val counter            = new AtomicInteger(0)
      val relUri             = request.relativeUri
      val cachedPath: String =
        route.backend.client.legacy
          .timeouts(relUri)
          .map(_ => relUri)
          .getOrElse("")

      def callF(
          target: Target,
          attempts: Int,
          alreadyFailed: AtomicBoolean
      ): Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]] = {
        val backend = getBackend(target, route, attrs)
        attrs.put(Keys.BackendKey -> backend)
        f(NgSelectedBackendTarget(backend, attempts, alreadyFailed, cbStart)).value.flatMap {
          case Left(err)        => err.asResult().map(Left.apply)
          case r @ Right(value) => Right(value).vfuture
        }
      }

      def handleError(t: Throwable): Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]] = {
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
                maybeRoute = route.some
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
                maybeRoute = route.some
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
                maybeRoute = route.some
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
                maybeRoute = route.some
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
                maybeRoute = route.some
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
                maybeRoute = route.some
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
                maybeRoute = route.some
              )
              .map(Left.apply)
        }
      }

      implicit val scheduler = env.otoroshiScheduler
      FEither(
        env.circuitBeakersHolder
          .get(
            route.cacheableId + cachedPath,
            () => new ServiceDescriptorCircuitBreaker()
          )
          .callGenNg[Flow[PlayWSMessage, PlayWSMessage, _]](
            route.cacheableId,
            route.name,
            route.backend.allTargets.map(_.toTarget),
            route.backend.loadBalancing,
            route.backend.client.legacy,
            reqNumber.toString,
            trackingId,
            request.relativeUri,
            request,
            bodyAlreadyConsumed,
            s"${request.method} ${request.relativeUri}",
            counter,
            attrs,
            callF
          ) recoverWith { case t: Throwable =>
          handleError(t)
        } map {
          case Left(res)    => Left(NgResultProxyEngineError(res))
          case Right(value) => Right(value)
        }
      )
    } else {
      val target: Target = attrs
        .get(otoroshi.plugins.Keys.PreExtractedRequestTargetKey)
        .getOrElse {
          val targets: Seq[Target] = attrs
            .get(otoroshi.plugins.Keys.PreExtractedRequestTargetsKey)
            .getOrElse(route.backend.allTargets)
            .map(_.toTarget)
            .filter(_.predicate.matches(reqNumber.toString, request, attrs))
            .flatMap(t => Seq.fill(t.weight)(t))
          route.backend.loadBalancing
            .select(
              reqNumber.toString,
              trackingId,
              request,
              targets,
              route.cacheableId
            )
        }
      //val index = reqCounter.get() % (if (targets.nonEmpty) targets.size else 1)
      // Round robin loadbalancing is happening here !!!!!
      //val target = targets.apply(index.toInt)
      val backend        = getBackend(target, route, attrs)
      attrs.put(Keys.BackendKey -> backend)
      f(NgSelectedBackendTarget(backend, 1, new AtomicBoolean(false), cbStart))
    }
  }

  def maybeStrippedUri(req: RequestHeader, rawUri: String, route: NgRoute, attrs: TypedMap): String = {
    if (route.frontend.stripPath) {
      attrs.get(Keys.MatchedRouteKey) match {
        case Some(mroute) =>
          if (mroute.path.nonEmpty) {
            val allPaths                 = route.frontend.domains.map(_.path)
            val containsWildcard         = allPaths.exists(_.contains("*"))
            val containsNamedParams      = allPaths.exists(_.contains("/:"))
            val containsRegexNamedParams = allPaths.exists(_.contains("/$"))
            if (
              !containsWildcard && !containsNamedParams && !containsRegexNamedParams && allPaths.size == 1 && allPaths
                .contains("/")
            ) {
              if (logger.isDebugEnabled) logger.debug("cleanup uri stripping")
              rawUri
            } else {
              // WARNING: this one can cause issue as here path segments can be stripped for the bad reasons
              val mpath = mroute.path.substring(1)
              attrs.put(otoroshi.plugins.Keys.StrippedPathKey -> mroute.path)
              rawUri.replaceFirst(mpath, "") // handles wildcard
            }
          } else {
            rawUri
          }
        case None         => {
          val allPaths    = route.frontend.domains.map(_.path)
          val root        = req.relativeUri
          val rootMatched = allPaths match { //rootMatched was this.matchingRoot
            case ps if ps.isEmpty => None
            case ps               => ps.find(p => root.startsWith(p))
          }
          rootMatched
            .filter(m => route.frontend.stripPath && root.startsWith(m))
            .map { m =>
              val replaced = m.replace(".", "\\.")
              attrs.put(otoroshi.plugins.Keys.StrippedPathKey -> replaced)
              root.replaceFirst(replaced, "")
            }
            .getOrElse(rawUri)
        }
      }
    } else {
      rawUri
    }
  }

  def callRequestTransformer(
      snowflake: String,
      request: RequestHeader,
      body: Source[ByteString, _],
      route: NgRoute,
      backend: NgTarget,
      plugins: NgContextualPlugins
  )(implicit
      ec: ExecutionContext,
      env: Env,
      report: NgExecutionReport,
      globalConfig: GlobalConfig,
      attrs: TypedMap,
      mat: Materializer
  ): FEither[NgProxyEngineError, NgPluginHttpRequest] = {
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
    val target      = backend.toTarget
    val root        = route.backend.root
    val rawUri      = request.relativeUri.substring(1)
    val uri         = maybeStrippedUri(request, rawUri, route, attrs)
    val lazySource  = Source.single(ByteString.empty).flatMapConcat { _ =>
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
      "Otoroshi-Try-It-Request-Id"
    ).++(headersInStatic).map(_.toLowerCase)

    val headers = request.headers.toSimpleMap
      .filterNot { case (key, _) =>
        headersInFiltered.contains(key.toLowerCase())
      }

    val rawRequest      = NgPluginHttpRequest(
      url = s"${request.theProtocol}://${request.theHost}${request.relativeUri}",
      method = request.method,
      headers = request.headers.toSimpleMap,
      cookies = wsCookiesIn,
      version = request.version,
      clientCertificateChain = () => request.clientCertificateChain,
      body = lazySource,
      backend = None
    )
    val targetUrlRaw    =
      if (route.backend.rewrite) s"${target.scheme}://${target.host}$root"
      else s"${target.scheme}://${target.host}$root$uri"
    val targetUrl       = TargetExpressionLanguage(
      targetUrlRaw,
      request.some,
      route.serviceDescriptor.some,
      route.some,
      attrs.get(otoroshi.plugins.Keys.ApiKeyKey),
      attrs.get(otoroshi.plugins.Keys.UserKey),
      attrs.get(otoroshi.plugins.Keys.ElCtxKey).get,
      attrs,
      env
    )
    val otoroshiRequest = NgPluginHttpRequest(
      url = targetUrl,
      method = request.method,
      headers = headers,
      cookies = wsCookiesIn,
      version = request.version,
      clientCertificateChain = () => request.clientCertificateChain,
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
        plugins = Seq.empty
      )
      def markPluginItem(
          item: NgReportPluginSequenceItem,
          ctx: NgTransformerRequestContext,
          debug: Boolean,
          result: JsValue
      ): Unit = {
        sequence = sequence.copy(
          plugins = sequence.plugins :+ item.copy(
            stop = System.currentTimeMillis(),
            stop_ns = System.nanoTime(),
            out = Json
              .obj(
                "not_triggered" -> plugins.tpwoRequest.map(_.instance.plugin),
                "result"        -> result
              )
              .applyOnIf(debug)(_ ++ Json.obj("ctx" -> ctx.json))
          )
        )
      }
      val __ctx    = NgTransformerRequestContext(
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
        report = report,
        sequence = sequence,
        markPluginItem = markPluginItem
      )

      if (all_plugins.size == 1) {
        val wrapper               = all_plugins.head
        val pluginConfig: JsValue = wrapper.plugin.defaultConfig
          .map(dc => dc ++ wrapper.instance.config.raw)
          .getOrElse(wrapper.instance.config.raw)
        val ctx                   = __ctx.copy(config = pluginConfig)
        val debug                 = route.debugFlow || wrapper.instance.debug
        val in: JsValue           = if (debug) Json.obj("ctx" -> ctx.json) else JsNull
        val item                  = NgReportPluginSequenceItem(
          wrapper.instance.plugin,
          wrapper.plugin.name,
          System.currentTimeMillis(),
          System.nanoTime(),
          -1L,
          -1L,
          in,
          JsNull
        )
        FEither(wrapper.plugin.transformRequest(ctx).transform {
          case Failure(exception)       =>
            markPluginItem(item, ctx, debug, Json.obj("kind" -> "failure", "error" -> JsonHelpers.errToJson(exception)))
            report.setContext(sequence.stopSequence().json)
            Success(
              Left(
                NgResultProxyEngineError(
                  otoroshiJsonError(
                    Json
                      .obj(
                        "error"             -> "internal_server_error",
                        "error_description" -> "an error happened during request-transformation plugins phase"
                      )
                      .applyOnIf(env.isDev) { obj => obj ++ Json.obj("jvm_error" -> JsonHelpers.errToJson(exception)) },
                    Results.InternalServerError,
                    attrs.get(otoroshi.next.plugins.Keys.RouteKey),
                    attrs,
                    request
                  )
                )
              )
            )
          case Success(Left(result))    =>
            markPluginItem(
              item,
              ctx,
              debug,
              Json.obj("kind" -> "short-circuit", "status" -> result.header.status, "headers" -> result.header.headers)
            )
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
            case None          => promise.trySuccess(Right(_ctx.otoroshiRequest))
            case Some(wrapper) => {
              val pluginConfig: JsValue = wrapper.plugin.defaultConfig
                .map(dc => dc ++ wrapper.instance.config.raw)
                .getOrElse(wrapper.instance.config.raw)
              val ctx                   = _ctx.copy(
                config = pluginConfig,
                apikey = _ctx.apikey.orElse(attrs.get(otoroshi.plugins.Keys.ApiKeyKey)),
                user = _ctx.user.orElse(attrs.get(otoroshi.plugins.Keys.UserKey)),
                idx = wrapper.instance.instanceId
              )
              val debug                 = route.debugFlow || wrapper.instance.debug
              val in: JsValue           = if (debug) Json.obj("ctx" -> ctx.json) else JsNull
              val item                  = NgReportPluginSequenceItem(
                wrapper.instance.plugin,
                wrapper.plugin.name,
                System.currentTimeMillis(),
                System.nanoTime(),
                -1L,
                -1L,
                in,
                JsNull
              )
              wrapper.plugin.transformRequest(ctx).andThen {
                case Failure(exception)                            =>
                  markPluginItem(
                    item,
                    ctx,
                    debug,
                    Json.obj("kind" -> "failure", "error" -> JsonHelpers.errToJson(exception))
                  )
                  report.setContext(sequence.stopSequence().json)
                  promise.trySuccess(
                    Left(
                      NgResultProxyEngineError(
                        otoroshiJsonError(
                          Json
                            .obj(
                              "error"             -> "internal_server_error",
                              "error_description" -> "an error happened during request-transformation plugins phase"
                            )
                            .applyOnIf(env.isDev) { obj =>
                              obj ++ Json.obj("jvm_error" -> JsonHelpers.errToJson(exception))
                            },
                          Results.InternalServerError,
                          attrs.get(otoroshi.next.plugins.Keys.RouteKey),
                          attrs,
                          request
                        )
                      )
                    )
                  )
                case Success(Left(result))                         =>
                  markPluginItem(
                    item,
                    ctx,
                    debug,
                    Json.obj(
                      "kind"    -> "short-circuit",
                      "status"  -> result.header.status,
                      "headers" -> result.header.headers
                    )
                  )
                  report.setContext(sequence.stopSequence().json)
                  promise.trySuccess(Left(NgResultProxyEngineError(result)))
                case Success(Right(req_next)) if plugins.size == 1 =>
                  markPluginItem(item, ctx.copy(otoroshiRequest = req_next), debug, Json.obj("kind" -> "successful"))
                  report.setContext(sequence.stopSequence().json)
                  promise.trySuccess(Right(req_next))
                case Success(Right(req_next))                      =>
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

  def callWsBackend(
      snowflake: String,
      rawRequest: RequestHeader,
      request: NgPluginHttpRequest,
      route: NgRoute,
      backend: NgTarget,
      ctxPlugins: NgContextualPlugins
  )(implicit
      ec: ExecutionContext,
      env: Env,
      report: NgExecutionReport,
      globalConfig: GlobalConfig,
      attrs: TypedMap,
      mat: Materializer
  ): FEither[NgProxyEngineError, Flow[PlayWSMessage, PlayWSMessage, _]] = {
    val finalTarget: Target = request.backend.getOrElse(backend).toTarget
    attrs.put(otoroshi.plugins.Keys.RequestTargetKey -> finalTarget)
    val all_tunnel_handlers = ctxPlugins.tunnelHandlerPlugins
    if (all_tunnel_handlers.nonEmpty) {
      val handler = all_tunnel_handlers.head
      if (request.relativeUri.startsWith("/.well-known/otoroshi/tunnel")) {
        val ctx = NgTunnelHandlerContext(
          snowflake = snowflake,
          request = rawRequest,
          route = route,
          config = handler.instance.config.raw,
          attrs = attrs
        )
        FEither(handler.plugin.handle(ctx).right.vfuture)
      } else {
        FEither(
          Errors
            .craftResponseResult(
              s"Resource not found",
              Results.NotFound,
              rawRequest,
              None,
              Some("errors.resource.not.found"),
              attrs = attrs,
              maybeRoute = route.some
            )
            .map(r => Left(NgResultProxyEngineError(r)))
        )
      }
    } else if (ctxPlugins.hasWebsocketBackendPlugins) {
      val handler  = ctxPlugins.websocketBackendPlugins.head
      val wsEngine = if (ctxPlugins.hasWebsocketPlugins) {
        new WebsocketEngine(route, ctxPlugins, rawRequest, finalTarget, attrs)
      } else {
        new WebsocketEngine(NgRoute.empty, NgContextualPlugins.empty(rawRequest), rawRequest, finalTarget, attrs)
      }
      val ctx      = NgWebsocketPluginContext(
        idx = 0,
        snowflake = snowflake,
        request = rawRequest,
        route = route,
        config = handler.instance.config.raw,
        attrs = attrs,
        target = finalTarget
      )
      FEither(handler.plugin.callBackendOrError(ctx).flatMap {
        case Left(proxyError) => proxyError.leftf
        case Right(flow)      => {
          val outFlow: Flow[PlayWSMessage, PlayWSMessage, _] = flow
            .mapAsync(1) { mess =>
              WebsocketMessage.PlayMessage(mess).asAkka.flatMap { m =>
                wsEngine.handleResponse(m)(_ => ())
              }
            }
            .takeWhile(_.isRight)
            .collect { case Right(message) =>
              message
            }
            .mapAsync(1) { message =>
              message.asPlay
            }
          val inFlow                                         = Flow
            .fromFunction[PlayWSMessage, PlayWSMessage](identity)
            .mapAsync(1) { mess =>
              wsEngine.handleRequest(mess)(_ => ())
            }
            .takeWhile(_.isRight)
            .collect { case Right(message) =>
              message
            }
            .mapAsync(1) { message =>
              message.asPlay
            }
          val finalFlow                                      = inFlow.via(outFlow)
          finalFlow.right.vfuture
        }
      })
    } else {
      if (route.useAkkaHttpWsClient && ctxPlugins.hasNoWebsocketPlugins) {
        FEither(
          WebSocketProxyActor
            .wsCall(
              UrlSanitizer.sanitize(request.url),
              request.headers.toSeq,
              route.serviceDescriptor,
              target = finalTarget,
              rawRequest = rawRequest,
              route = route.some
            )
            .right
            .vfuture
        )
      } else {
        FEither(
          ActorFlow
            .actorRef(out =>
              WebSocketProxyActor.props(
                UrlSanitizer.sanitize(request.url),
                out,
                request.headers.toSeq,
                rawRequest,
                route.serviceDescriptor,
                route.some,
                ctxPlugins.some,
                finalTarget,
                attrs,
                env
              )
            )(env.otoroshiActorSystem, env.otoroshiMaterializer)
            .right
            .vfuture
        )
      }
    }
  }

  def callBackend(
      snowflake: String,
      noBackendCallerPlugin: Boolean,
      rawRequest: Request[Source[ByteString, _]],
      request: NgPluginHttpRequest,
      route: NgRoute,
      backend: NgTarget,
      plugins: NgContextualPlugins,
      engineConfig: ProxyEngineConfig
  )(implicit
      ec: ExecutionContext,
      env: Env,
      report: NgExecutionReport,
      globalConfig: GlobalConfig,
      attrs: TypedMap,
      mat: Materializer
  ): FEither[NgProxyEngineError, BackendCallResponse] = {
    if (!noBackendCallerPlugin && plugins.hasBackendCallPlugin) {
      val pluginInstance = plugins.backendCallPlugin
      val ctx            = NgbBackendCallContext(
        snowflake = snowflake,
        rawRequest = rawRequest,
        request = request,
        route = route,
        backend = backend,
        apikey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey),
        user = attrs.get(otoroshi.plugins.Keys.UserKey),
        config = pluginInstance.instance.config.json,
        globalConfig = globalConfig.plugins.config,
        attrs = attrs
      )
      FEither.fromEitherT(
        pluginInstance.plugin.callBackend(
          ctx,
          () =>
            callBackend(
              snowflake,
              noBackendCallerPlugin = true,
              rawRequest,
              request,
              route,
              backend,
              plugins,
              engineConfig
            ).value
        )
      )
    } else {
      val finalTarget: Target = request.backend.getOrElse(backend).toTarget
      attrs.put(otoroshi.plugins.Keys.RequestTargetKey -> finalTarget)
      val contentLengthIn: Option[Long]                  = request.contentLengthStr
        .orElse(rawRequest.contentLengthStr)
        .map(_.toLong)
      val counterIn                                      = attrs.get(otoroshi.plugins.Keys.RequestCounterInKey).get
      counterIn.addAndGet(contentLengthIn.getOrElse(0L))
      val (currentReqHasBody, shouldInjectContentLength) = request.hasBodyWithoutLength
      val wsCookiesIn                                    = request.cookies
      val clientConfig                                   = route.backend.client
      val clientReq: WSRequest                           = if (route.useNettyClient || finalTarget.protocol.isHttp2OrHttp3) {
        env.reactorClientGateway
          .url(UrlSanitizer.sanitize(request.url))
          .withTarget(finalTarget)
          .withClientConfig(clientConfig.legacy)
      } else {
        route.useAkkaHttpClient match {
          case _ if finalTarget.mtlsConfig.mtls =>
            env.gatewayClient.akkaUrlWithTarget(
              UrlSanitizer.sanitize(request.url),
              finalTarget,
              clientConfig.legacy
            )
          case true                             =>
            env.gatewayClient.akkaUrlWithTarget(
              UrlSanitizer.sanitize(request.url),
              finalTarget,
              clientConfig.legacy
            )
          case false                            =>
            env.gatewayClient.urlWithTarget(
              UrlSanitizer.sanitize(request.url),
              finalTarget,
              clientConfig.legacy
            )
        }
      }
      val host                                           = request.headers.get("Host").orElse(request.headers.get("host")).getOrElse(rawRequest.theHost)
      val extractedTimeout                               =
        route.backend.client.legacy
          .extractTimeout(rawRequest.relativeUri, _.callAndStreamTimeout, _.callAndStreamTimeout)
      val isTargetHttp1                                  =
        finalTarget.protocol == HttpProtocols.HTTP_1_0 || finalTarget.protocol == HttpProtocols.HTTP_1_1
      val isTargetHttp2                                  = finalTarget.protocol == HttpProtocols.HTTP_2_0
      val version                                        = rawRequest.version.toLowerCase
      val isRequestAboveHttp1                            =
        (!version.startsWith("http/1")) && (version.startsWith("http/2") || version.startsWith("http3"))
      val isRequestAboveHttp2                            =
        (!version.startsWith("http/1") && !version.startsWith("http/2")) && version.startsWith("http3")
      val requestHeaders                                 = request.headers
        .filterNot(_._1.toLowerCase == "cookie")
        .+("Host" -> host)
        .toSeq
        .applyOnIf(isTargetHttp1 && isRequestAboveHttp1) { s =>
          s
            .filterNot(_._1.toLowerCase().startsWith("x-http2"))
            .filterNot(_._1.toLowerCase().startsWith("x-http3"))
        }
        .applyOnIf(isTargetHttp2 && isRequestAboveHttp2) { s =>
          s.filterNot(_._1.toLowerCase().startsWith("x-http3"))
        }
      val builder                                        = clientReq
        .withRequestTimeout(extractedTimeout)
        .withFailureIndicator(fakeFailureIndicator)
        .withMethod(request.method)
        .withHttpHeaders(requestHeaders: _*)
        .withCookies(wsCookiesIn: _*)
        .withFollowRedirects(false)
        .withMaybeProxyServer(
          route.backend.client.proxy.orElse(globalConfig.proxies.services)
        )
      val theBody                                        = request.body
        .applyOnIf(env.dynamicBodySizeCompute && contentLengthIn.isEmpty) { body =>
          body.map { chunk =>
            counterIn.addAndGet(chunk.size)
            chunk
          }
        }
        .applyOnIf(request.hasBody && engineConfig.capture) { source =>
          var requestChunks = ByteString("")
          source
            .map { chunk =>
              if ((requestChunks.size + chunk.size) <= engineConfig.captureMaxEntitySize) {
                requestChunks = requestChunks ++ chunk
              }
              chunk
            }
            .alsoTo(Sink.onComplete { case _ =>
              attrs.put(otoroshi.plugins.Keys.CaptureRequestBodyKey -> requestChunks)
            })
        }
      // because writeableOf_WsBody always add a 'Content-Type: application/octet-stream' header
      val builderWithBody                                = if (currentReqHasBody) {
        if (shouldInjectContentLength) {
          builder.addHttpHeaders("Content-Length" -> "0").withBody(theBody)
        } else {
          builder.withBody(theBody)
        }
      } else {
        builder
      }

      report.markOverheadIn()
      val start                           = System.currentTimeMillis()
      val fu: Future[BackendCallResponse] = builderWithBody
        .stream()
        .map { response =>
          attrs.put(otoroshi.plugins.Keys.BackendDurationKey -> (System.currentTimeMillis() - start))
          val idOpt              = rawRequest.attrs.get(otoroshi.netty.NettyRequestKeys.TrailerHeadersIdKey)
          val hasTrailerHeaders  =
            rawRequest.headers.get("te").contains("trailers") || response.headers.containsIgnoreCase("trailer")
          val shouldHaveTrailers =
            (route.useNettyClient || finalTarget.protocol.isHttp2OrHttp3) && rawRequest.attrs // trailers works for http/1.1, h2 and h3
              .get(RequestAttrKey.Server)
              .contains("netty-experimental") && hasTrailerHeaders
          if (shouldHaveTrailers) {
            val id = idOpt.get
            response match {
              case r: otoroshi.netty.TrailerSupport =>
                val future = r.trailingHeaders()
                otoroshi.netty.NettyRequestAwaitingTrailers.add(id, Left(future))
                future.map(trls => otoroshi.netty.NettyRequestAwaitingTrailers.add(id, Right(trls)))
              case _                                =>
            }
          }
          BackendCallResponse(
            NgPluginHttpResponse(
              status = response.status,
              headers = response.headers.mapValues(_.last).applyOnIf(shouldHaveTrailers) { hds =>
                idOpt match {
                  case Some(id) if shouldHaveTrailers => hds ++ Map("otoroshi-netty-trailers" -> id)
                  case _                              => hds
                }
              },
              cookies = response.cookies,
              body = response.bodyAsSource
            ),
            response.some
          )
        }
        .andThen { case _ =>
          report.startOverheadOut()
        }
      FEither.fright(fu)
    }
  }

  def callResponseTransformer(
      snowflake: String,
      rawRequest: Request[Source[ByteString, _]],
      response: BackendCallResponse,
      route: NgRoute,
      backend: NgTarget,
      plugins: NgContextualPlugins
  )(implicit
      ec: ExecutionContext,
      env: Env,
      report: NgExecutionReport,
      globalConfig: GlobalConfig,
      attrs: TypedMap,
      mat: Materializer
  ): FEither[NgProxyEngineError, NgPluginHttpResponse] = {

    val rawResponse      = response.response.copy() /*NgPluginHttpResponse(
      status = response.status,
      headers = response.headers.mapValues(_.last),
      cookies = response.cookies,
      body = response.bodyAsSource
    )*/
    val otoroshiResponse = response.response.copy() /*NgPluginHttpResponse(
      status = response.status,
      headers = response.headers.mapValues(_.last),
      cookies = response.cookies,
      body = response.bodyAsSource
    )*/

    val all_plugins = plugins.transformerPluginsThatTransformsResponse

    if (all_plugins.nonEmpty) {
      var sequence = NgReportPluginSequence(
        size = all_plugins.size,
        kind = "response-transformer-plugins",
        start = System.currentTimeMillis(),
        stop = 0L,
        start_ns = System.nanoTime(),
        stop_ns = 0L,
        plugins = Seq.empty
      )
      def markPluginItem(
          item: NgReportPluginSequenceItem,
          ctx: NgTransformerResponseContext,
          debug: Boolean,
          result: JsValue
      ): Unit = {
        sequence = sequence.copy(
          plugins = sequence.plugins :+ item.copy(
            stop = System.currentTimeMillis(),
            stop_ns = System.nanoTime(),
            out = Json
              .obj(
                "not_triggered" -> plugins.tpwoResponse.map(_.instance.plugin),
                "result"        -> result
              )
              .applyOnIf(debug)(_ ++ Json.obj("ctx" -> ctx.json))
          )
        )
      }
      val __ctx    = NgTransformerResponseContext(
        snowflake = snowflake,
        request = rawRequest,
        response = response.rawResponse,
        rawResponse = rawResponse,
        otoroshiResponse = otoroshiResponse,
        apikey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey),
        user = attrs.get(otoroshi.plugins.Keys.UserKey),
        route = route,
        config = Json.obj(),
        globalConfig = globalConfig.plugins.config,
        attrs = attrs,
        report = report,
        sequence = sequence,
        markPluginItem = markPluginItem
      )
      if (all_plugins.size == 1) {
        val wrapper               = all_plugins.head
        val pluginConfig: JsValue = wrapper.plugin.defaultConfig
          .map(dc => dc ++ wrapper.instance.config.raw)
          .getOrElse(wrapper.instance.config.raw)
        val ctx                   = __ctx.copy(config = pluginConfig)
        val debug                 = route.debugFlow || wrapper.instance.debug
        val in: JsValue           = if (debug) Json.obj("ctx" -> ctx.json) else JsNull
        val item                  = NgReportPluginSequenceItem(
          wrapper.instance.plugin,
          wrapper.plugin.name,
          System.currentTimeMillis(),
          System.nanoTime(),
          -1L,
          -1L,
          in,
          JsNull
        )
        FEither(wrapper.plugin.transformResponse(ctx).transform {
          case Failure(exception)        =>
            markPluginItem(item, ctx, debug, Json.obj("kind" -> "failure", "error" -> JsonHelpers.errToJson(exception)))
            report.setContext(sequence.stopSequence().json)
            Success(
              Left(
                NgResultProxyEngineError(
                  otoroshiJsonError(
                    Json
                      .obj(
                        "error"             -> "internal_server_error",
                        "error_description" -> "an error happened during response-transformation plugins phase"
                      )
                      .applyOnIf(env.isDev) { obj => obj ++ Json.obj("jvm_error" -> JsonHelpers.errToJson(exception)) },
                    Results.InternalServerError,
                    attrs.get(otoroshi.next.plugins.Keys.RouteKey),
                    attrs,
                    rawRequest
                  )
                )
              )
            )
          case Success(Left(result))     =>
            markPluginItem(
              item,
              ctx,
              debug,
              Json.obj("kind" -> "short-circuit", "status" -> result.header.status, "headers" -> result.header.headers)
            )
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
            case None          => promise.trySuccess(Right(_ctx.otoroshiResponse))
            case Some(wrapper) => {
              val pluginConfig: JsValue = wrapper.plugin.defaultConfig
                .map(dc => dc ++ wrapper.instance.config.raw)
                .getOrElse(wrapper.instance.config.raw)
              val ctx                   = _ctx.copy(
                config = pluginConfig,
                apikey = _ctx.apikey.orElse(attrs.get(otoroshi.plugins.Keys.ApiKeyKey)),
                user = _ctx.user.orElse(attrs.get(otoroshi.plugins.Keys.UserKey)),
                idx = wrapper.instance.instanceId
              )
              val debug                 = route.debugFlow || wrapper.instance.debug
              val in: JsValue           = if (debug) Json.obj("ctx" -> ctx.json) else JsNull
              val item                  = NgReportPluginSequenceItem(
                wrapper.instance.plugin,
                wrapper.plugin.name,
                System.currentTimeMillis(),
                System.nanoTime(),
                -1L,
                -1L,
                in,
                JsNull
              )
              wrapper.plugin.transformResponse(ctx).andThen {
                case Failure(exception)                             =>
                  markPluginItem(
                    item,
                    ctx,
                    debug,
                    Json.obj("kind" -> "failure", "error" -> JsonHelpers.errToJson(exception))
                  )
                  report.setContext(sequence.stopSequence().json)
                  promise.trySuccess(
                    Left(
                      NgResultProxyEngineError(
                        otoroshiJsonError(
                          Json
                            .obj(
                              "error"             -> "internal_server_error",
                              "error_description" -> "an error happened during response-transformation plugins phase"
                            )
                            .applyOnIf(env.isDev) { obj =>
                              obj ++ Json.obj("jvm_error" -> JsonHelpers.errToJson(exception))
                            },
                          Results.InternalServerError,
                          attrs.get(otoroshi.next.plugins.Keys.RouteKey),
                          attrs,
                          rawRequest
                        )
                      )
                    )
                  )
                case Success(Left(result))                          =>
                  markPluginItem(
                    item,
                    ctx,
                    debug,
                    Json.obj(
                      "kind"    -> "short-circuit",
                      "status"  -> result.header.status,
                      "headers" -> result.header.headers
                    )
                  )
                  report.setContext(sequence.stopSequence().json)
                  promise.trySuccess(Left(NgResultProxyEngineError(result)))
                case Success(Right(resp_next)) if plugins.size == 1 =>
                  markPluginItem(item, ctx.copy(otoroshiResponse = resp_next), debug, Json.obj("kind" -> "successful"))
                  report.setContext(sequence.stopSequence().json)
                  promise.trySuccess(Right(resp_next))
                case Success(Right(resp_next))                      =>
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

  def streamResponse(
      snowflake: String,
      rawRequest: Request[Source[ByteString, _]],
      request: NgPluginHttpRequest,
      rawResponse: BackendCallResponse,
      response: NgPluginHttpResponse,
      route: NgRoute,
      backend: NgTarget,
      engineConfig: ProxyEngineConfig
  )(implicit
      ec: ExecutionContext,
      env: Env,
      report: NgExecutionReport,
      globalConfig: GlobalConfig,
      attrs: TypedMap,
      mat: Materializer
  ): FEither[NgProxyEngineError, Result] = {
    val counterOut                  = attrs.get(otoroshi.plugins.Keys.RequestCounterOutKey).get
    val contentType: Option[String] = response.header("Content-Type")
    val contentLength: Option[Long] = response
      .header("Content-Length")
      .orElse(rawResponse.contentLengthStr) // legit
      .map(_.toLong)
    val contentLengthOut: Option[Long] = response
      .header("Content-Length")
      .map(_.toLong)
    counterOut.addAndGet(contentLength.getOrElse(0L))
    val _cookies                       = response.cookies.map {
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
        val sameSite: Option[Cookie.SameSite] =
          rawResponse.headers.get("Set-Cookie").orElse(rawResponse.headers.get("set-cookie")).flatMap {
            values => // legit
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
    val cookies                        = attrs.get(otoroshi.plugins.Keys.RequestTrackingIdKey) match {
      case None             => _cookies
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
    val isContentLengthZero: Boolean   = response.headers.getIgnoreCase("Content-Length").contains("0")
    val noContentLengthHeader: Boolean = !response.hasLength // rawResponse.contentLength.isEmpty
    val hasChunkedHeader: Boolean      = response.isChunked /*rawResponse
      .header("Transfer-Encoding")
      .orElse(response.headers.get("Transfer-Encoding"))
      .exists(h => h.toLowerCase().contains("chunked"))*/

    val isChunked: Boolean             = rawResponse.isChunked() match { // don't know if actualy legit ...
      case _ if isContentLengthZero                                                              => false
//      case Some(true)                                                                                   => true
//      case Some(false) if !env.emptyContentLengthIsChunked                                              => hasChunkedHeader
//      case Some(false) if env.emptyContentLengthIsChunked && noContentLengthHeader                      => true
//      case Some(false) if env.emptyContentLengthIsChunked && !hasChunkedHeader && noContentLengthHeader => true
//      case Some(false)                                                                                  => false
      case Some(chunked)                                                                         => chunked
      case None if !env.emptyContentLengthIsChunked                                              =>
        hasChunkedHeader // false
      case None if env.emptyContentLengthIsChunked && hasChunkedHeader                           =>
        true
      case None if env.emptyContentLengthIsChunked && !hasChunkedHeader && noContentLengthHeader =>
        true
      case _                                                                                     => false
    }
    val status                         = attrs.get(otoroshi.plugins.Keys.StatusOverrideKey).getOrElse(response.status)
    val isHttp10                       = rawRequest.version == "HTTP/1.0"
    val willStream                     = if (isHttp10) false else (!isChunked)
    val headersOutFiltered             = Seq(
      env.Headers.OtoroshiStateResp
    ).++(headersOutStatic).map(_.toLowerCase)
    val headers: Seq[(String, String)] = response.headers
      .filterNot { case (key, _) =>
        headersOutFiltered.contains(key.toLowerCase())
      }
      .applyOnIf(!isHttp10)(_.filterNot(h => h._1.toLowerCase() == "content-length"))
      .toSeq // ++ Seq(("Connection" -> "keep-alive"), ("X-Connection" -> "keep-alive"))

    val theBody = response.body
      .applyOnIf(env.dynamicBodySizeCompute && contentLength.isEmpty) { body =>
        body.map { chunk =>
          counterOut.addAndGet(chunk.size)
          chunk
        }
      }
      .applyOnIf(engineConfig.capture) { source =>
        var responseChunks = ByteString("")
        source
          .map { chunk =>
            if ((responseChunks.size + chunk.size) <= engineConfig.captureMaxEntitySize) {
              responseChunks = responseChunks ++ chunk
            }
            chunk
          }
          .alsoTo(Sink.onComplete { case _ =>
            TrafficCaptureEvent(route, rawRequest, request, rawResponse.response, response, responseChunks, attrs)
              .toAnalytics()
          })
      } /*.map { bs =>
      counterOut.addAndGet(bs.length)
      bs
    }*/
    // TODO: should we enforce it as specified in rfc7230 ?
    // * https://datatracker.ietf.org/doc/html/rfc7230#section-3.3
    // * https://datatracker.ietf.org/doc/html/rfc7230#section-3.3.1
    // * https://datatracker.ietf.org/doc/html/rfc7230#section-3.3.2
    // * https://datatracker.ietf.org/doc/html/rfc7230#section-3.3.3
    // if (response.status == 204) {
    //   FEither.right(Results
    //     .Status(status)
    //     .sendEntity(HttpEntity.NoEntity)
    //     .withHeaders(headers: _*)
    //     .withCookies(cookies: _*))
    // } else
    if (isHttp10) {
      logger.warn(
        s"HTTP/1.0 request, storing temporary result in memory :( (${rawRequest.theProtocol}://${rawRequest.theHost}${rawRequest.relativeUri})"
      )
      FEither(
        theBody
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
            val response: Result = Results
              .Status(status)
              .sendEntity(HttpEntity.Strict(body, contentType))
              .withHeaders(headers: _*)
              .withCookies(cookies: _*)
            //Status(status)(body)
            //.withHeaders(headers: _*)
            //.withCookies(cookies: _*)
            contentType match {
              case None      => Right(response)
              case Some(ctp) => Right(response.as(ctp))
            }
          }
      )
    } else {
      isChunked match {
        case true  => {
          // stream out
          // val res = Status(status)
          //   .chunked(theBody)
          //   .withHeaders(headers: _*)
          //   .withCookies(cookies: _*)
          val res = Results
            .Status(status)
            .sendEntity(
              HttpEntity.Chunked(
                theBody
                  .map(bs => HttpChunk.Chunk(bs))
                  .concat(Source.single(HttpChunk.LastChunk(Headers.create()))),
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
        case false => {
          val res = Results
            .Status(status)
            .sendEntity(
              HttpEntity.Streamed(
                theBody,
                contentLengthOut,
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

  def triggerWsProxyDone(
      snowflake: String,
      rawRequest: RequestHeader,
      request: NgPluginHttpRequest,
      route: NgRoute,
      backend: NgTarget,
      sb: NgSelectedBackendTarget
  )(implicit
      ec: ExecutionContext,
      env: Env,
      report: NgExecutionReport,
      globalConfig: GlobalConfig,
      attrs: TypedMap,
      mat: Materializer
  ): FEither[NgProxyEngineError, Done] = {
    Future {
      val actualDuration: Long  = report.getDurationNow()
      val overhead: Long        = report.getOverheadNow()
      val upstreamLatency: Long = report.getStep("call-backend").map(_.duration).getOrElse(-1L)
      val apiKey                = attrs.get(otoroshi.plugins.Keys.ApiKeyKey)
      val paUsr                 = attrs.get(otoroshi.plugins.Keys.UserKey)
      val callDate              = attrs.get(otoroshi.plugins.Keys.RequestTimestampKey).get
      val counterIn             = attrs.get(otoroshi.plugins.Keys.RequestCounterInKey).get
      val counterOut            = attrs.get(otoroshi.plugins.Keys.RequestCounterOutKey).get
      val fromOtoroshi          = rawRequest.headers
        .get(env.Headers.OtoroshiRequestId)
        .orElse(rawRequest.headers.get(env.Headers.OtoroshiGatewayParentRequest))
      val duration: Long = {
        if (route.id == env.backOfficeServiceId && actualDuration > 300L)
          300L
        else actualDuration
      }
      // increments calls for service and globally
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
        case BestResponseTime            =>
          BestResponseTime.incrementAverage(route.cacheableId, backend.toTarget, duration)
        case WeightedBestResponseTime(_) =>
          BestResponseTime.incrementAverage(route.cacheableId, backend.toTarget, duration)
        case _                           =>
      }
      val fromLbl               =
        rawRequest.headers
          .get(env.Headers.OtoroshiVizFromLabel)
          .getOrElse("internet")
      val viz: OtoroshiViz      = OtoroshiViz(
        to = route.id,
        toLbl = route.name,
        from = rawRequest.headers
          .get(env.Headers.OtoroshiVizFrom)
          .getOrElse("internet"),
        fromLbl = fromLbl,
        fromTo = s"$fromLbl###${route.name}"
      )
      val cbDuration            = System.currentTimeMillis() - sb.cbStart
      val evt                   = GatewayEvent(
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
        backendDuration = attrs.get(otoroshi.plugins.Keys.BackendDurationKey).getOrElse(-1L),
        duration = duration,
        overhead = overhead,
        cbDuration = cbDuration,
        overheadWoCb = Math.abs(overhead - cbDuration),
        callAttempts = sb.attempts,
        url = rawRequest.theUrl,
        method = rawRequest.method,
        from = rawRequest.theIpAddress,
        env = route.metadata.get("otoroshi-core-env").getOrElse("prod"),
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
              label = k.clientName,
              tags = k.tags,
              metadata = k.metadata
            )
          )
          .orElse(
            paUsr.map(k =>
              Identity(
                identityType = "PRIVATEAPP",
                identity = k.email,
                label = k.name,
                tags = k.tags,
                metadata = k.metadata
              )
            )
          ),
        responseChunked = false,
        `@serviceId` = route.id,
        `@service` = route.name,
        descriptor = Some(route.legacy),
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

  def triggerProxyDone(
      snowflake: String,
      rawRequest: Request[Source[ByteString, _]],
      rawResponse: BackendCallResponse,
      request: NgPluginHttpRequest,
      response: NgPluginHttpResponse,
      route: NgRoute,
      backend: NgTarget,
      sb: NgSelectedBackendTarget
  )(implicit
      ec: ExecutionContext,
      env: Env,
      report: NgExecutionReport,
      globalConfig: GlobalConfig,
      attrs: TypedMap,
      mat: Materializer
  ): FEither[NgProxyEngineError, Done] = {
    attrs
      .get(otoroshi.plugins.Keys.ResponseEndPromiseKey)
      .foreach(_.future.andThen { case _ =>
        val actualDuration: Long           = report.getDurationNow()
        val overhead: Long                 = report.getOverheadNow()
        val upstreamLatency: Long          = report.getStep("call-backend").map(_.duration).getOrElse(-1L)
        val apiKey                         = attrs.get(otoroshi.plugins.Keys.ApiKeyKey)
        val paUsr                          = attrs.get(otoroshi.plugins.Keys.UserKey)
        val callDate                       = attrs.get(otoroshi.plugins.Keys.RequestTimestampKey).get
        val counterIn                      = attrs.get(otoroshi.plugins.Keys.RequestCounterInKey).get
        val counterOut                     = attrs.get(otoroshi.plugins.Keys.RequestCounterOutKey).get
        val fromOtoroshi                   = rawRequest.headers
          .get(env.Headers.OtoroshiRequestId)
          .orElse(rawRequest.headers.get(env.Headers.OtoroshiGatewayParentRequest))
        val noContentLengthHeader: Boolean =
          rawResponse.contentLength.isEmpty
        val hasChunkedHeader: Boolean      = rawResponse
          .header("Transfer-Encoding")
          .exists(h => h.toLowerCase().contains("chunked"))
        val isContentLengthZero: Boolean   = rawResponse.header("Content-Length").contains("0")
        val isChunked: Boolean             = rawResponse.isChunked() match {
          case _ if isContentLengthZero                                                              => false
          case Some(chunked)                                                                         => chunked
          case None if !env.emptyContentLengthIsChunked                                              =>
            hasChunkedHeader // false
          case None if env.emptyContentLengthIsChunked && hasChunkedHeader                           =>
            true
          case None if env.emptyContentLengthIsChunked && !hasChunkedHeader && noContentLengthHeader =>
            true
          case _                                                                                     => false
        }
        val duration: Long = {
          if (route.id == env.backOfficeServiceId && actualDuration > 300L)
            300L
          else actualDuration
        }
        // increments calls for service and globally
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
          case BestResponseTime            =>
            BestResponseTime.incrementAverage(route.cacheableId, backend.toTarget, duration)
          case WeightedBestResponseTime(_) =>
            BestResponseTime.incrementAverage(route.cacheableId, backend.toTarget, duration)
          case _                           =>
        }
        val fromLbl                        =
          rawRequest.headers
            .get(env.Headers.OtoroshiVizFromLabel)
            .getOrElse("internet")
        val viz: OtoroshiViz               = OtoroshiViz(
          to = route.id,
          toLbl = route.name,
          from = rawRequest.headers
            .get(env.Headers.OtoroshiVizFrom)
            .getOrElse("internet"),
          fromLbl = fromLbl,
          fromTo = s"$fromLbl###${route.name}"
        )
        val cbDuration                     = System.currentTimeMillis() - sb.cbStart
        val evt                            = GatewayEvent(
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
          backendDuration = attrs.get(otoroshi.plugins.Keys.BackendDurationKey).getOrElse(-1L),
          duration = duration,
          overhead = overhead,
          cbDuration = cbDuration,
          overheadWoCb = Math.abs(overhead - cbDuration),
          callAttempts = sb.attempts,
          url = rawRequest.theUrl,
          method = rawRequest.method,
          from = rawRequest.theIpAddress,
          env = route.metadata.get("otoroshi-core-env").getOrElse("prod"),
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
                label = k.clientName,
                tags = k.tags,
                metadata = k.metadata
              )
            )
            .orElse(
              paUsr.map(k =>
                Identity(
                  identityType = "PRIVATEAPP",
                  identity = k.email,
                  label = k.name,
                  tags = k.tags,
                  metadata = k.metadata
                )
              )
            ),
          responseChunked = isChunked,
          `@serviceId` = route.id,
          `@service` = route.name,
          descriptor = Some(route.legacy),
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
      }(env.analyticsExecutionContext))
    FEither.right(Done)
  }
}
