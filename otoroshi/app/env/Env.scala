package env

import java.lang.management.ManagementFactory
import java.rmi.registry.LocateRegistry
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import akka.actor.{ActorSystem, Cancellable, PoisonPill, Scheduler}
import akka.http.scaladsl.util.FastFuture._
import akka.stream.Materializer
import auth.{AuthModuleConfig, SessionCookieValues}
import ch.qos.logback.classic.{Level, LoggerContext}
import cluster._
import com.typesafe.config.{ConfigFactory, ConfigRenderOptions}
import events._
import gateway.CircuitBreakersHolder
import health.{HealthCheckerActor, StartHealthCheck}
import javax.management.remote.{JMXConnectorServerFactory, JMXServiceURL}
import models._
import org.joda.time.DateTime
import org.mindrot.jbcrypt.BCrypt
import org.slf4j.LoggerFactory
import otoroshi.models.{EntityLocation, OtoroshiAdminType, SimpleOtoroshiAdmin, Team, TeamAccess, TeamId, Tenant, TenantAccess, TenantId, UserRight, UserRights, WebAuthnOtoroshiAdmin}
import otoroshi.script.{AccessValidatorRef, JobManager, Script, ScriptCompiler, ScriptManager}
import otoroshi.ssl.pki.BouncyCastlePki
import otoroshi.storage.DataStores
import otoroshi.storage.drivers.cassandra._
import otoroshi.storage.drivers.inmemory._
import otoroshi.storage.drivers.lettuce._
import otoroshi.storage.drivers.leveldb._
import otoroshi.storage.drivers.mongo._
import otoroshi.storage.drivers.reactivepg.ReactivePgDataStores
import otoroshi.storage.drivers.rediscala._
import otoroshi.tcp.TcpService
import play.api._
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.{JsArray, JsNull, JsObject, JsValue, Json}
import play.api.libs.ws._
import play.api.libs.ws.ahc._
import play.twirl.api.Html
import security.{ClaimCrypto, IdGenerator}
import ssl.{Cert, DynamicSSLEngineProvider}
import utils.http._
import utils.{HasMetrics, Metrics}
import otoroshi.utils.syntax.implicits._
import play.shaded.ahc.org.asynchttpclient.{AsyncHttpClient, DefaultAsyncHttpClient}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.io.Source
import scala.util.{Failure, Success}

case class SidecarConfig(
    serviceId: String,
    target: Target,
    from: String = "127.0.0.1",
    apiKeyClientId: Option[String] = None,
    strict: Boolean = true
)

class Env(val configuration: Configuration,
          val environment: Environment,
          val lifecycle: ApplicationLifecycle,
          wsClient: WSClient,
          val circuitBeakersHolder: CircuitBreakersHolder,
          getHttpPort: => Option[Int],
          getHttpsPort: => Option[Int],
          testing: Boolean)
    extends HasMetrics {

  val logger = Logger("otoroshi-env")

  val otoroshiConfig: Configuration = (for {
    appConfig <- configuration.getOptionalWithFileSupport[Configuration]("app")
    otoConfig <- configuration.getOptionalWithFileSupport[Configuration]("otoroshi")
  } yield {
    val appConfigJson: JsObject =
      Json.parse(appConfig.underlying.root().render(ConfigRenderOptions.concise())).as[JsObject]
    val otoConfigJson: JsObject =
      Json.parse(otoConfig.underlying.root().render(ConfigRenderOptions.concise())).as[JsObject]
    val finalConfigJson1: JsObject = appConfigJson ++ otoConfigJson
    Configuration(ConfigFactory.parseString(Json.stringify(finalConfigJson1)))
  }) getOrElse configuration

  private lazy val xmasStart =
    DateTime.now().withMonthOfYear(12).withDayOfMonth(20).withMillisOfDay(0)
  private lazy val xmasStop =
    DateTime.now().withMonthOfYear(12).dayOfMonth().withMaximumValue().plusDays(1).withMillisOfDay(1)

  private lazy val halloweenStart =
    DateTime.now().withMonthOfYear(10).withDayOfMonth(31).withMillisOfDay(0)
  private lazy val halloweenStop =
    DateTime.now().withMonthOfYear(10).withDayOfMonth(31).plusDays(1).withMillisOfDay(1)

  private lazy val disableFunnyLogos: Boolean =
    configuration.getOptionalWithFileSupport[Boolean]("otoroshi.options.disableFunnyLogos").getOrElse(false)

  def otoroshiLogo: String = {
    val now = DateTime.now()
    if (disableFunnyLogos) {
      "/__otoroshi_assets/images/otoroshi-logo-color.png"
    } else if (now.isAfter(xmasStart) && now.isBefore(xmasStop)) {
      "/__otoroshi_assets/images/otoroshi-logo-xmas.png"
    } else if (now.isAfter(halloweenStart) && now.isBefore(halloweenStop)) {
      "/__otoroshi_assets/images/otoroshi-logo-halloween3.png"
    } else {
      "/__otoroshi_assets/images/otoroshi-logo-color.png"
    }
  }

  val otoroshiActorSystem: ActorSystem = ActorSystem(
    "otoroshi-actor-system",
    configuration
      .getOptionalWithFileSupport[Configuration]("app.actorsystems.otoroshi")
      .orElse(configuration.getOptionalWithFileSupport[Configuration]("otoroshi.analytics.actorsystem"))
      .map(_.underlying)
      .getOrElse(ConfigFactory.empty)
  )
  val otoroshiExecutionContext: ExecutionContext = otoroshiActorSystem.dispatcher
  val otoroshiScheduler: Scheduler               = otoroshiActorSystem.scheduler
  val otoroshiMaterializer: Materializer         = Materializer(otoroshiActorSystem)

  val analyticsPressureEnabled: Boolean =
    configuration.getOptionalWithFileSupport[Boolean]("otoroshi.analytics.pressure.enabled").getOrElse(false)

  val analyticsActorSystem: ActorSystem =
    if (analyticsPressureEnabled)
      ActorSystem(
        "otoroshi-analytics-actor-system",
        configuration
          .getOptionalWithFileSupport[Configuration]("app.actorsystems.analytics")
          .map(_.underlying)
          .getOrElse(ConfigFactory.empty)
      )
    else otoroshiActorSystem
  val analyticsExecutionContext: ExecutionContext =
    if (analyticsPressureEnabled) analyticsActorSystem.dispatcher else otoroshiExecutionContext
  val analyticsScheduler: Scheduler =
    if (analyticsPressureEnabled) analyticsActorSystem.scheduler else otoroshiScheduler
  val analyticsMaterializer: Materializer =
    if (analyticsPressureEnabled) Materializer(analyticsActorSystem) else otoroshiMaterializer

  def timeout(duration: FiniteDuration): Future[Unit] = {
    val promise = Promise[Unit]
    otoroshiActorSystem.scheduler.scheduleOnce(duration) {
      promise.trySuccess(())
    }(otoroshiExecutionContext)
    promise.future
  }

  val healthCheckerActor = otoroshiActorSystem.actorOf(HealthCheckerActor.props(this))
  val otoroshiEventsActor = otoroshiActorSystem.actorOf(OtoroshiEventsActorSupervizer.props(this))

  lazy val sidecarConfig: Option[SidecarConfig] = (
    configuration.getOptionalWithFileSupport[String]("app.sidecar.serviceId"),
    configuration.getOptionalWithFileSupport[String]("app.sidecar.target"),
    configuration.getOptionalWithFileSupport[String]("app.sidecar.from"),
    configuration.getOptionalWithFileSupport[String]("app.sidecar.apikey.clientId"),
    configuration.getOptionalWithFileSupport[Boolean]("app.sidecar.strict")
  ) match {
    case (Some(serviceId), Some(target), from, clientId, strict) =>
      val conf = SidecarConfig(
        serviceId = serviceId,
        target = Target(target.split("://")(1), target.split("://")(0)),
        from = from.getOrElse("127.0.0.1"),
        apiKeyClientId = clientId,
        strict = strict.getOrElse(true)
      )
      Some(conf)
    case a => None
  }

  lazy val healtCheckWorkers: Int = configuration.getOptionalWithFileSupport[Int]("otoroshi.healthcheck.workers").getOrElse(4)
  lazy val healtCheckBlockOnRed: Boolean = configuration.getOptionalWithFileSupport[Boolean]("otoroshi.healthcheck.block-on-red").getOrElse(false)
  lazy val healtCheckTTL: Long = configuration.getOptionalWithFileSupport[Long]("otoroshi.healthcheck.ttl").getOrElse(60 * 1000)
  lazy val healtCheckTTLOnly: Boolean = configuration.getOptionalWithFileSupport[Boolean]("otoroshi.healthcheck.ttl-only").getOrElse(true)

  lazy val maxWebhookSize: Int = configuration.getOptionalWithFileSupport[Int]("app.webhooks.size").getOrElse(100)

  lazy val clusterConfig: ClusterConfig = ClusterConfig(
    configuration.getOptionalWithFileSupport[Configuration]("otoroshi.cluster").getOrElse(Configuration.empty)
  )
  lazy val clusterAgent: ClusterAgent             = ClusterAgent(clusterConfig, this)
  lazy val clusterLeaderAgent: ClusterLeaderAgent = ClusterLeaderAgent(clusterConfig, this)

  lazy val bypassUserRightsCheck: Boolean =
    configuration.getOptionalWithFileSupport[Boolean]("otoroshi.bypassUserRightsCheck").getOrElse(false)

  lazy val globalMaintenanceMode: Boolean =
    configuration.getOptionalWithFileSupport[Boolean]("otoroshi.maintenanceMode").getOrElse(false)

  lazy val metricsEnabled: Boolean =
    configuration.getOptionalWithFileSupport[Boolean]("otoroshi.metrics.enabled").getOrElse(true)

  lazy val staticExposedDomain: Option[String] =
    configuration.getOptionalWithFileSupport[String]("otoroshi.options.staticExposedDomain")

  lazy val staticExposedDomainEnabled: Boolean = staticExposedDomain.isDefined

  lazy val providerDashboardUrl: Option[String] =
    configuration.getOptionalWithFileSupport[String]("otoroshi.provider.dashboardUrl")

  lazy val providerJsUrl: Option[String] =
    configuration.getOptionalWithFileSupport[String]("otoroshi.provider.jsUrl")

  lazy val providerCssUrl: Option[String] =
    configuration.getOptionalWithFileSupport[String]("otoroshi.provider.cssUrl")

  lazy val providerJsUrlHtml: Html =
    providerJsUrl.map(url => Html(s"""<script type="text/javascript" src="$url"></script>""")).getOrElse(Html(""))

  lazy val providerCssUrlHtml: Html =
    providerCssUrl.map(url => Html(s"""<link href="$url" rel="stylesheet">""")).getOrElse(Html(""))

  lazy val otoroshiSecret: String = configuration.getOptionalWithFileSupport[String]("otoroshi.secret").get

  lazy val providerDashboardSecret: String =
    configuration.getOptionalWithFileSupport[String]("otoroshi.provider.secret").getOrElse("secret")

  lazy val providerDashboardTitle: String =
    configuration.getOptionalWithFileSupport[String]("otoroshi.provider.title").getOrElse("Provider's dashboard")

  lazy val useEventStreamForScriptEvents: Boolean =
    configuration.getOptionalWithFileSupport[Boolean]("otoroshi.options.useEventStreamForScriptEvents").getOrElse(true)

  lazy val emptyContentLengthIsChunked: Boolean =
    configuration.getOptionalWithFileSupport[Boolean]("otoroshi.options.emptyContentLengthIsChunked").getOrElse(false)

  lazy val detectApiKeySooner: Boolean =
    configuration.getOptionalWithFileSupport[Boolean]("otoroshi.options.detectApiKeySooner").getOrElse(false)

  lazy val metricsAccessKey: Option[String] =
    configuration.getOptionalWithFileSupport[String]("otoroshi.metrics.accessKey").orElse(healthAccessKey)

  lazy val metricsEvery: FiniteDuration =
    configuration
      .getOptionalWithFileSupport[Long]("otoroshi.metrics.every")
      .map(v => FiniteDuration(v, TimeUnit.MILLISECONDS))
      .getOrElse(FiniteDuration(30, TimeUnit.SECONDS))

  lazy val staticGlobalScripts: GlobalScripts = {
    GlobalScripts(
      enabled = configuration.getOptionalWithFileSupport[Boolean]("otoroshi.scripts.static.enabled").getOrElse(false),
      transformersRefs = configuration
        .getOptionalWithFileSupport[Seq[String]]("otoroshi.scripts.static.transformersRefs")
        .orElse(
          configuration
            .getOptionalWithFileSupport[String]("otoroshi.scripts.static.transformersRefsStr")
            .map(_.split(",").map(_.trim).toSeq)
        )
        .getOrElse(Seq.empty[String]),
      transformersConfig = configuration
        .getOptionalWithFileSupport[Configuration]("otoroshi.scripts.static.transformersConfig")
        .map(c => Json.parse(c.underlying.root().render(ConfigRenderOptions.concise())))
        .orElse(configuration.getOptionalWithFileSupport[String]("otoroshi.scripts.static.transformersConfigStr").map(Json.parse))
        .getOrElse(Json.obj()),
      validatorRefs = configuration
        .getOptionalWithFileSupport[Seq[String]]("otoroshi.scripts.static.validatorRefs")
        .orElse(
          configuration
            .getOptionalWithFileSupport[String]("otoroshi.scripts.static.validatorRefsStr")
            .map(_.split(",").map(_.trim).toSeq)
        )
        .getOrElse(Seq.empty[String]),
      validatorConfig = configuration
        .getOptionalWithFileSupport[Configuration]("otoroshi.scripts.static.validatorConfig")
        .map(c => Json.parse(c.underlying.root().render(ConfigRenderOptions.concise())))
        .orElse(configuration.getOptionalWithFileSupport[String]("otoroshi.scripts.static.validatorConfigStr").map(Json.parse))
        .getOrElse(Json.obj()),
      preRouteRefs = configuration
        .getOptionalWithFileSupport[Seq[String]]("otoroshi.scripts.static.preRouteRefs")
        .orElse(
          configuration
            .getOptionalWithFileSupport[String]("otoroshi.scripts.static.preRouteRefsStr")
            .map(_.split(",").map(_.trim).toSeq)
        )
        .getOrElse(Seq.empty[String]),
      preRouteConfig = configuration
        .getOptionalWithFileSupport[Configuration]("otoroshi.scripts.static.preRouteConfig")
        .map(c => Json.parse(c.underlying.root().render(ConfigRenderOptions.concise())))
        .orElse(configuration.getOptionalWithFileSupport[String]("otoroshi.scripts.static.preRouteConfigStr").map(Json.parse))
        .getOrElse(Json.obj()),
      sinkRefs = configuration
        .getOptionalWithFileSupport[Seq[String]]("otoroshi.scripts.static.sinkRefs")
        .orElse(
          configuration.getOptionalWithFileSupport[String]("otoroshi.scripts.static.sinkRefsStr").map(_.split(",").map(_.trim).toSeq)
        )
        .getOrElse(Seq.empty[String]),
      sinkConfig = configuration
        .getOptionalWithFileSupport[Configuration]("otoroshi.scripts.static.sinkConfig")
        .map(c => Json.parse(c.underlying.root().render(ConfigRenderOptions.concise())))
        .orElse(configuration.getOptionalWithFileSupport[String]("otoroshi.scripts.static.sinkConfigStr").map(Json.parse))
        .getOrElse(Json.obj()),
      jobRefs = configuration
        .getOptionalWithFileSupport[Seq[String]]("otoroshi.scripts.static.jobsRefs")
        .orElse(
          configuration
            .getOptionalWithFileSupport[String]("otoroshi.scripts.static.jobsRefsStr")
            .map(_.split(",").map(_.trim).toSeq)
        )
        .getOrElse(Seq.empty[String]),
      jobConfig = configuration
        .getOptionalWithFileSupport[Configuration]("otoroshi.scripts.static.jobsConfig")
        .map(c => Json.parse(c.underlying.root().render(ConfigRenderOptions.concise())))
        .orElse(configuration.getOptionalWithFileSupport[String]("otoroshi.scripts.static.jobsConfigStr").map(Json.parse))
        .getOrElse(Json.obj())
    )
  }

  lazy val requestTimeout: FiniteDuration =
    configuration.getOptionalWithFileSupport[Int]("app.proxy.requestTimeout").map(_.millis).getOrElse(1.hour)

  lazy val trustXForwarded: Boolean =
    configuration.getOptionalWithFileSupport[Boolean]("otoroshi.options.trustXForwarded").getOrElse(true)

  lazy val manualDnsResolve: Boolean =
    configuration.getOptionalWithFileSupport[Boolean]("otoroshi.options.manualDnsResolve").getOrElse(true)
  lazy val useOldHeadersComposition: Boolean =
    configuration.getOptionalWithFileSupport[Boolean]("otoroshi.options.useOldHeadersComposition").getOrElse(false)
  lazy val sendClientChainAsPem: Boolean =
    configuration.getOptionalWithFileSupport[Boolean]("otoroshi.options.sendClientChainAsPem").getOrElse(false)
  lazy val validateRequests: Boolean = configuration.getOptionalWithFileSupport[Boolean]("otoroshi.requests.validate").getOrElse(true)
  lazy val maxUrlLength: Long =
    Option(configuration.underlying.getBytes("otoroshi.requests.maxUrlLength")).map(_.toLong).getOrElse(4 * 1024L)
  lazy val maxCookieLength: Long =
    Option(configuration.underlying.getBytes("otoroshi.requests.maxCookieLength")).map(_.toLong).getOrElse(16 * 1024L)
  lazy val maxHeaderValueLength: Long = Option(
    configuration.underlying.getBytes("otoroshi.requests.maxHeaderValueLength")
  ).map(_.toLong).getOrElse(16 * 1024L)
  lazy val maxHeaderNameLength: Long =
    Option(configuration.underlying.getBytes("otoroshi.requests.maxHeaderNameLength")).map(_.toLong).getOrElse(128L)

  lazy val healthAccessKey: Option[String] = configuration.getOptionalWithFileSupport[String]("app.health.accessKey")
  lazy val overheadThreshold: Double       = configuration.getOptionalWithFileSupport[Double]("app.overheadThreshold").getOrElse(500.0)
  lazy val healthLimit: Double             = configuration.getOptionalWithFileSupport[Double]("app.health.limit").getOrElse(1000.0)
  lazy val throttlingWindow: Int           = configuration.getOptionalWithFileSupport[Int]("app.throttlingWindow").getOrElse(10)
  lazy val analyticsWindow: Int            = configuration.getOptionalWithFileSupport[Int]("app.analyticsWindow").getOrElse(30)
  lazy val eventsName: String              = configuration.getOptionalWithFileSupport[String]("app.eventsName").getOrElse("otoroshi")
  lazy val storageRoot: String             = configuration.getOptionalWithFileSupport[String]("app.storageRoot").getOrElse("otoroshi")
  lazy val useCache: Boolean               = configuration.getOptionalWithFileSupport[Boolean]("otoroshi.cache.enabled").getOrElse(false)
  lazy val cacheTtl: Int                   = configuration.getOptionalWithFileSupport[Int]("otoroshi.cache.ttl").filter(_ >= 2000).getOrElse(2000)
  lazy val useRedisScan: Boolean           = configuration.getOptionalWithFileSupport[Boolean]("app.redis.useScan").getOrElse(false)
  lazy val secret: String                  = configuration.getOptionalWithFileSupport[String]("play.crypto.secret").get
  lazy val secretSession: String =
    configuration.getOptionalWithFileSupport[String]("otoroshi.sessions.secret").map(_.padTo(16, "0").mkString("").take(16)).get
  lazy val sharedKey: String     = configuration.getOptionalWithFileSupport[String]("app.claim.sharedKey").get
  lazy val env: String           = configuration.getOptionalWithFileSupport[String]("app.env").getOrElse("prod")
  lazy val number: Int           = configuration.getOptionalWithFileSupport[Int]("app.instance.number").getOrElse(0)
  lazy val name: String          = configuration.getOptionalWithFileSupport[String]("app.instance.name").getOrElse("otoroshi")
  lazy val title: String         = configuration.getOptionalWithFileSupport[String]("app.instance.title").getOrElse("Otoroshi")
  lazy val rack: String          = configuration.getOptionalWithFileSupport[String]("app.instance.rack").getOrElse("local")
  lazy val infraProvider: String = configuration.getOptionalWithFileSupport[String]("app.instance.provider").getOrElse("local")
  lazy val dataCenter: String    = configuration.getOptionalWithFileSupport[String]("app.instance.dc").getOrElse("local")
  lazy val zone: String          = configuration.getOptionalWithFileSupport[String]("app.instance.zone").getOrElse("local")
  lazy val region: String        = configuration.getOptionalWithFileSupport[String]("app.instance.region").getOrElse("local")
  lazy val liveJs: Boolean = configuration
    .getOptionalWithFileSupport[String]("app.env")
    .filter(_ == "dev")
    .map(_ => true)
    .orElse(configuration.getOptionalWithFileSupport[Boolean]("app.liveJs"))
    .getOrElse(false)

  lazy val exposeAdminApi: Boolean =
    if (clusterConfig.mode.isWorker) false
    else configuration.getOptionalWithFileSupport[Boolean]("app.adminapi.exposed").getOrElse(true)
  lazy val exposeAdminDashboard: Boolean =
    if (clusterConfig.mode.isWorker) false
    else configuration.getOptionalWithFileSupport[Boolean]("app.backoffice.exposed").getOrElse(true)
  lazy val adminApiProxyHttps: Boolean = configuration.getOptionalWithFileSupport[Boolean]("app.adminapi.proxy.https").getOrElse(false)
  lazy val adminApiProxyUseLocal: Boolean =
    configuration.getOptionalWithFileSupport[Boolean]("app.adminapi.proxy.local").getOrElse(true)
  lazy val domain: String = configuration.getOptionalWithFileSupport[String]("app.domain").getOrElse("oto.tools")
  lazy val adminApiSubDomain: String =
    configuration.getOptionalWithFileSupport[String]("app.adminapi.targetSubdomain").getOrElse("otoroshi-admin-internal-api")
  lazy val adminApiExposedSubDomain: String =
    configuration.getOptionalWithFileSupport[String]("app.adminapi.exposedSubdomain").getOrElse("otoroshi-api")
  lazy val adminApiAdditionalExposedDomain: Option[String] =
    configuration.getOptionalWithFileSupport[String]("app.adminapi.additionalExposedDomain")
  lazy val backOfficeSubDomain: String =
    configuration.getOptionalWithFileSupport[String]("app.backoffice.subdomain").getOrElse("otoroshi")
  lazy val privateAppsSubDomain: String =
    configuration.getOptionalWithFileSupport[String]("app.privateapps.subdomain").getOrElse("privateapps")
  lazy val privateAppsPort: Option[Int] =
    configuration.getOptionalWithFileSupport[Int]("app.privateapps.port")
  lazy val retries: Int = configuration.getOptionalWithFileSupport[Int]("app.retries").getOrElse(5)

  lazy val backOfficeServiceId = configuration.getOptionalWithFileSupport[String]("app.adminapi.defaultValues.backOfficeServiceId").get
  lazy val backOfficeGroupId   = configuration.getOptionalWithFileSupport[String]("app.adminapi.defaultValues.backOfficeGroupId").get
  lazy val backOfficeApiKeyClientId =
    configuration.getOptionalWithFileSupport[String]("app.adminapi.defaultValues.backOfficeApiKeyClientId").get
  lazy val backOfficeApiKeyClientSecret =
    configuration.getOptionalWithFileSupport[String]("app.adminapi.defaultValues.backOfficeApiKeyClientSecret").get

  def composeMainUrl(subdomain: String): String = s"$subdomain.$domain"

  lazy val adminApiExposedHost = composeMainUrl(adminApiExposedSubDomain)
  lazy val adminApiHost        = composeMainUrl(adminApiSubDomain)
  lazy val backOfficeHost      = composeMainUrl(backOfficeSubDomain)
  lazy val privateAppsHost     = composeMainUrl(privateAppsSubDomain)

  lazy val procNbr = Runtime.getRuntime.availableProcessors()

  lazy val ahcStats = new AtomicReference[Cancellable]()
  lazy val internalAhcStats = new AtomicReference[Cancellable]()

  lazy val gatewayClient = {
    val parser: WSConfigParser = new WSConfigParser(configuration.underlying, environment.classLoader)
    val config: AhcWSClientConfig = new AhcWSClientConfig(wsClientConfig = parser.parse()).copy(
      keepAlive = configuration.getOptionalWithFileSupport[Boolean]("app.proxy.keepAlive").getOrElse(true)
      //setHttpClientCodecMaxChunkSize(1024 * 100)
    )
    val wsClientConfig: WSClientConfig = config.wsClientConfig.copy(
      userAgent = Some("Otoroshi-akka"),
      compressionEnabled = configuration.getOptionalWithFileSupport[Boolean]("app.proxy.compressionEnabled").getOrElse(false),
      idleTimeout =
        configuration.getOptionalWithFileSupport[Int]("app.proxy.idleTimeout").map(_.millis).getOrElse((2 * 60 * 1000).millis),
      connectionTimeout = configuration
        .getOptionalWithFileSupport[Int]("app.proxy.connectionTimeout")
        .map(_.millis)
        .getOrElse((2 * 60 * 1000).millis)
    )
    val ahcClient: AhcWSClient = AhcWSClient(
      config.copy(
        wsClientConfig = wsClientConfig
      )
    )(otoroshiMaterializer)

    import collection.JavaConverters._
    ahcStats.set(otoroshiActorSystem.scheduler.scheduleWithFixedDelay(1.second, 1.second) { () =>
      scala.util.Try {
        val stats = ahcClient.underlying[DefaultAsyncHttpClient].getClientStats
        metrics.histogram("ahc-total-active-connections").update(stats.getTotalActiveConnectionCount)
        metrics.histogram("ahc-total-connections").update(stats.getTotalConnectionCount)
        metrics.histogram("ahc-total-idle-connections").update(stats.getTotalIdleConnectionCount)
        stats.getStatsPerHost.asScala.foreach {
          case (key, value) =>
            metrics.histogram(key + "-ahc-total-active-connections").update(value.getHostActiveConnectionCount)
            metrics.histogram(key + "-ahc-total-connections").update(value.getHostConnectionCount)
            metrics.histogram(key + "-ahc-total-idle-connections").update(value.getHostIdleConnectionCount)
        }
      } match {
        case Success(_) => ()
        case Failure(e) => logger.error("error while publishing ahc stats", e)
      }
    }(otoroshiExecutionContext))

    WsClientChooser(
      ahcClient,
      new AkkWsClient(wsClientConfig, this)(otoroshiActorSystem, otoroshiMaterializer),
      configuration.getOptionalWithFileSupport[Boolean]("app.proxy.useAkkaClient").getOrElse(false),
      this
    )
  }

  lazy val _internalClient = {
    val parser: WSConfigParser = new WSConfigParser(configuration.underlying, environment.classLoader)
    val config: AhcWSClientConfig = new AhcWSClientConfig(wsClientConfig = parser.parse()).copy(
      keepAlive = configuration.getOptionalWithFileSupport[Boolean]("app.proxy.keepAlive").getOrElse(true)
      //setHttpClientCodecMaxChunkSize(1024 * 100)
    )
    val wsClientConfig: WSClientConfig = config.wsClientConfig.copy(
      userAgent = Some("Otoroshi-akka"),
      compressionEnabled = configuration.getOptionalWithFileSupport[Boolean]("app.proxy.compressionEnabled").getOrElse(false),
      idleTimeout =
        configuration.getOptionalWithFileSupport[Int]("app.proxy.idleTimeout").map(_.millis).getOrElse((2 * 60 * 1000).millis),
      connectionTimeout = configuration
        .getOptionalWithFileSupport[Int]("app.proxy.connectionTimeout")
        .map(_.millis)
        .getOrElse((2 * 60 * 1000).millis)
    )
    import collection.JavaConverters._
    internalAhcStats.set(otoroshiActorSystem.scheduler.scheduleWithFixedDelay(1.second, 1.second) { () =>
      scala.util.Try {
        val stats = wsClient.underlying[DefaultAsyncHttpClient].getClientStats
        metrics.histogram("ahc-total-active-connections").update(stats.getTotalActiveConnectionCount)
        metrics.histogram("ahc-total-connections").update(stats.getTotalConnectionCount)
        metrics.histogram("ahc-total-idle-connections").update(stats.getTotalIdleConnectionCount)
        stats.getStatsPerHost.asScala.foreach {
          case (key, value) =>
            metrics.histogram(key + "-ahc-total-active-connections").update(value.getHostActiveConnectionCount)
            metrics.histogram(key + "-ahc-total-connections").update(value.getHostConnectionCount)
            metrics.histogram(key + "-ahc-total-idle-connections").update(value.getHostIdleConnectionCount)
        }
      } match {
        case Success(_) => ()
        case Failure(e) => logger.error("error while publishing ahc stats", e)
      }
    }(otoroshiExecutionContext))
    WsClientChooser(
      wsClient,
      new AkkWsClient(wsClientConfig, this)(otoroshiActorSystem, otoroshiMaterializer),
      configuration.getOptionalWithFileSupport[Boolean]("app.proxy.useAkkaClient").getOrElse(false),
      this
    )
  }

  // lazy val geoloc = new GeoLite2GeolocationHelper(this)
  // lazy val ua = new UserAgentHelper(this)

  lazy val statsd  = new StatsdWrapper(otoroshiActorSystem, this)
  lazy val metrics = new Metrics(this, lifecycle)
  lazy val pki     = new BouncyCastlePki(snowflakeGenerator)

  lazy val hash = s"${System.currentTimeMillis()}"

  lazy val backOfficeSessionExp = configuration.getOptionalWithFileSupport[Long]("app.backoffice.session.exp").get

  lazy val exposedRootScheme = configuration.getOptionalWithFileSupport[String]("app.rootScheme").getOrElse("https")

  def rootScheme               = s"${exposedRootScheme}://"
  def exposedRootSchemeIsHttps = exposedRootScheme == "https"

  lazy val Ws     = _internalClient
  lazy val MtlsWs = utils.http.MtlsWs(_internalClient)

  lazy val snowflakeSeed      = configuration.getOptionalWithFileSupport[Long]("app.snowflake.seed").get
  lazy val snowflakeGenerator = IdGenerator(snowflakeSeed)
  lazy val redirections: Seq[String] =
    configuration.getOptionalWithFileSupport[Seq[String]]("app.redirections").map(_.toSeq).getOrElse(Seq.empty[String])

  lazy val crypto = ClaimCrypto(sharedKey)

  object Headers {
    lazy val OtoroshiVizFromLabel         = configuration.getOptionalWithFileSupport[String]("otoroshi.headers.trace.label").get
    lazy val OtoroshiVizFrom              = configuration.getOptionalWithFileSupport[String]("otoroshi.headers.trace.from").get
    lazy val OtoroshiGatewayParentRequest = configuration.getOptionalWithFileSupport[String]("otoroshi.headers.trace.parent").get
    lazy val OtoroshiAdminProfile         = configuration.getOptionalWithFileSupport[String]("otoroshi.headers.request.adminprofile").get
    lazy val OtoroshiClientId             = configuration.getOptionalWithFileSupport[String]("otoroshi.headers.request.clientid").get
    lazy val OtoroshiSimpleApiKeyClientId =
      configuration.getOptionalWithFileSupport[String]("otoroshi.headers.request.simpleapiclientid").get
    lazy val OtoroshiClientSecret     = configuration.getOptionalWithFileSupport[String]("otoroshi.headers.request.clientsecret").get
    lazy val OtoroshiRequestId        = configuration.getOptionalWithFileSupport[String]("otoroshi.headers.request.id").get
    lazy val OtoroshiRequestTimestamp = configuration.getOptionalWithFileSupport[String]("otoroshi.headers.request.timestamp").get
    lazy val OtoroshiAuthorization    = configuration.getOptionalWithFileSupport[String]("otoroshi.headers.request.authorization").get
    lazy val OtoroshiBearer           = configuration.getOptionalWithFileSupport[String]("otoroshi.headers.request.bearer").get
    lazy val OtoroshiJWTAuthorization =
      configuration.getOptionalWithFileSupport[String]("otoroshi.headers.request.jwtAuthorization").get
    lazy val OtoroshiBasicAuthorization =
      configuration.getOptionalWithFileSupport[String]("otoroshi.headers.request.basicAuthorization").get
    lazy val OtoroshiBearerAuthorization =
      configuration.getOptionalWithFileSupport[String]("otoroshi.headers.request.bearerAuthorization").get
    lazy val OtoroshiProxiedHost  = configuration.getOptionalWithFileSupport[String]("otoroshi.headers.response.proxyhost").get
    lazy val OtoroshiGatewayError = configuration.getOptionalWithFileSupport[String]("otoroshi.headers.response.error").get
    lazy val OtoroshiErrorMsg     = configuration.getOptionalWithFileSupport[String]("otoroshi.headers.response.errormsg").get
    lazy val OtoroshiProxyLatency = configuration.getOptionalWithFileSupport[String]("otoroshi.headers.response.proxylatency").get
    lazy val OtoroshiUpstreamLatency =
      configuration.getOptionalWithFileSupport[String]("otoroshi.headers.response.upstreamlatency").get
    lazy val OtoroshiDailyCallsRemaining = configuration.getOptionalWithFileSupport[String]("otoroshi.headers.response.dailyquota").get
    lazy val OtoroshiMonthlyCallsRemaining =
      configuration.getOptionalWithFileSupport[String]("otoroshi.headers.response.monthlyquota").get
    lazy val OtoroshiState                = configuration.getOptionalWithFileSupport[String]("otoroshi.headers.comm.state").get
    lazy val OtoroshiStateResp            = configuration.getOptionalWithFileSupport[String]("otoroshi.headers.comm.stateresp").get
    lazy val OtoroshiClaim                = configuration.getOptionalWithFileSupport[String]("otoroshi.headers.comm.claim").get
    lazy val OtoroshiHealthCheckLogicTest = configuration.getOptionalWithFileSupport[String]("otoroshi.headers.healthcheck.test").get
    lazy val OtoroshiHealthCheckLogicTestResult =
      configuration.getOptionalWithFileSupport[String]("otoroshi.headers.healthcheck.testresult").get
    lazy val OtoroshiIssuer          = configuration.getOptionalWithFileSupport[String]("otoroshi.headers.jwt.issuer").get
    lazy val OtoroshiTrackerId       = configuration.getOptionalWithFileSupport[String]("otoroshi.headers.canary.tracker").get
    lazy val OtoroshiClientCertChain = configuration.getOptionalWithFileSupport[String]("otoroshi.headers.client.cert.chain").get
  }

  logger.info(s"Otoroshi version ${otoroshiVersion}")
  // logger.info(s"Scala version ${scala.util.Properties.versionNumberString} / ${scala.tools.nsc.Properties.versionNumberString}")
  if (!testing) {
    logger.info(s"Admin API exposed on http://$adminApiExposedHost:$port")
    logger.info(s"Admin UI  exposed on http://$backOfficeHost:$port")
  }

  def displayDefaultValuesWarning(): Unit = {

    def checkValue(value: String, default: String, path: String, envvar: String, desc: String): Option[String] = {
      if (value == default) {
        Some(s"$path (env. var. $envvar): $desc")
      } else {
        None
      }
    }

    val values = Seq(
      checkValue(otoroshiSecret, "verysecretvaluethatyoumustoverwrite", "otoroshi.secret", "OTOROSHI_SECRET", "used to sign various stuff including session cookies"),
      checkValue(backOfficeApiKeyClientSecret, "admin-api-apikey-secret", "otoroshi.admin-api-secret", "OTOROSHI_ADMIN_API_SECRET", "used to access otoroshi admin api"),
    )

    if (otoroshiSecret == "verysecretvaluethatyoumustoverwrite") {
      logger.warn("")
      logger.warn("#########################################")
      logger.warn("")
      logger.warn("DEFAULT VALUES USAGE DETECTED !!!")
      logger.warn("")
      logger.warn("You are using the default values for the following security involved configs:")
      logger.warn("")
      values.collect { case Some(mess) => s" - $mess" }.foreach(m => logger.warn(m))
      logger.warn("")
      logger.warn("You MUST change those values before deploying to production")
      logger.warn("You can change configuration by passing path values with config file or via runtime flags")
      logger.warn("    https://maif.github.io/otoroshi/manual/firstrun/configfile.html")
      logger.warn("You can change configuration by passing environment variables")
      logger.warn("    https://maif.github.io/otoroshi/manual/firstrun/env.html")
      logger.warn("")
      logger.warn("#########################################")
      logger.warn("")
    }
  }

  displayDefaultValuesWarning()

  lazy val datastores: DataStores = {
    configuration.getOptionalWithFileSupport[String]("app.storage").getOrElse("redis") match {
      case _ if clusterConfig.mode == ClusterMode.Worker =>
        new SwappableInMemoryDataStores(configuration, environment, lifecycle, this)
      case "redis-pool" if clusterConfig.mode == ClusterMode.Leader =>
        new RedisCPDataStores(configuration, environment, lifecycle, this)
      case "redis-mpool" if clusterConfig.mode == ClusterMode.Leader =>
        new RedisMCPDataStores(configuration, environment, lifecycle, this)
      case "redis-cluster" if clusterConfig.mode == ClusterMode.Leader =>
        new RedisClusterDataStores(configuration, environment, lifecycle, this)
      case "redis-lf" if clusterConfig.mode == ClusterMode.Leader =>
        new RedisLFDataStores(configuration, environment, lifecycle, this)
      case "redis-sentinel" if clusterConfig.mode == ClusterMode.Leader =>
        new RedisSentinelDataStores(configuration, environment, lifecycle, this)
      case "redis-sentinel-lf" if clusterConfig.mode == ClusterMode.Leader =>
        new RedisSentinelLFDataStores(configuration, environment, lifecycle, this)
      case "redis" if clusterConfig.mode == ClusterMode.Leader =>
        new RedisLFDataStores(configuration, environment, lifecycle, this)
      case "inmemory" if clusterConfig.mode == ClusterMode.Leader =>
        new InMemoryDataStores(configuration, environment, lifecycle, PersistenceKind.NoopPersistenceKind, this)
      case "leveldb" if clusterConfig.mode == ClusterMode.Leader =>
        new LevelDbDataStores(configuration, environment, lifecycle, this)
      case "file" if clusterConfig.mode == ClusterMode.Leader =>
        new InMemoryDataStores(configuration, environment, lifecycle, PersistenceKind.FilePersistenceKind, this)
      case "http" if clusterConfig.mode == ClusterMode.Leader =>
        new InMemoryDataStores(configuration, environment, lifecycle, PersistenceKind.HttpPersistenceKind, this)
      case "cassandra-naive" if clusterConfig.mode == ClusterMode.Leader =>
        new CassandraDataStores(true, configuration, environment, lifecycle, this)
      case "cassandra" if clusterConfig.mode == ClusterMode.Leader =>
        new CassandraDataStores(false, configuration, environment, lifecycle, this)
      case "mongo" if clusterConfig.mode == ClusterMode.Leader =>
        new MongoDataStores(configuration, environment, lifecycle, this)
      case "lettuce" if clusterConfig.mode == ClusterMode.Leader =>
        new LettuceDataStores(configuration, environment, lifecycle, this)
      case "experimental-pg" if clusterConfig.mode == ClusterMode.Leader =>
        new ReactivePgDataStores(configuration, environment, lifecycle, this)
      case "redis"             => new RedisLFDataStores(configuration, environment, lifecycle, this)
      case "inmemory"          => new InMemoryDataStores(configuration, environment, lifecycle, PersistenceKind.NoopPersistenceKind, this)
      case "leveldb"           => new LevelDbDataStores(configuration, environment, lifecycle, this)
      case "file"              => new InMemoryDataStores(configuration, environment, lifecycle, PersistenceKind.FilePersistenceKind, this)
      case "http"              => new InMemoryDataStores(configuration, environment, lifecycle, PersistenceKind.HttpPersistenceKind, this)
      case "cassandra-naive"   => new CassandraDataStores(true, configuration, environment, lifecycle, this)
      case "cassandra"         => new CassandraDataStores(false, configuration, environment, lifecycle, this)
      case "mongo"             => new MongoDataStores(configuration, environment, lifecycle, this)
      case "redis-pool"        => new RedisCPDataStores(configuration, environment, lifecycle, this)
      case "redis-mpool"       => new RedisMCPDataStores(configuration, environment, lifecycle, this)
      case "redis-cluster"     => new RedisClusterDataStores(configuration, environment, lifecycle, this)
      case "redis-lf"          => new RedisLFDataStores(configuration, environment, lifecycle, this)
      case "redis-sentinel"    => new RedisSentinelDataStores(configuration, environment, lifecycle, this)
      case "redis-sentinel-lf" => new RedisSentinelLFDataStores(configuration, environment, lifecycle, this)
      case "lettuce"           => new LettuceDataStores(configuration, environment, lifecycle, this)
      case "experimental-pg"   => new ReactivePgDataStores(configuration, environment, lifecycle, this)
      case e                   => throw new RuntimeException(s"Bad storage value from conf: $e")
    }
  }

  val scriptingEnabled = configuration.getOptionalWithFileSupport[Boolean]("otoroshi.scripts.enabled").getOrElse(false)
  val scriptCompiler   = new ScriptCompiler(this)
  val scriptManager    = new ScriptManager(this).start()

  if (scriptingEnabled) logger.warn("Scripting is enabled on this Otoroshi instance !")

  if (useCache) logger.warn(s"Datastores will use cache to speed up operations")

  val jobManager = new JobManager(this)

  val servers = TcpService.runServers(this)

  datastores.before(configuration, environment, lifecycle)
  // geoloc.start()
  // ua.start()
  lifecycle.addStopHook(() => {
    implicit val ec = otoroshiExecutionContext
    // geoloc.stop()
    // ua.stop()
    healthCheckerActor ! PoisonPill
    otoroshiEventsActor ! PoisonPill
    Option(ahcStats.get()).foreach(_.cancel())
    Option(internalAhcStats.get()).foreach(_.cancel())
    jobManager.stop()
    scriptManager.stop()
    clusterAgent.stop()
    clusterLeaderAgent.stop()
    otoroshiActorSystem.terminate()
    datastores.after(configuration, environment, lifecycle)
    servers.stop()
    // FastFuture.successful(())
  })

  lazy val port = getHttpPort.getOrElse(
    configuration
      .getOptionalWithFileSupport[Int]("play.server.http.port")
      .orElse(configuration.getOptionalWithFileSupport[Int]("http.port"))
      .getOrElse(9999)
  )

  lazy val httpsPort = getHttpsPort.getOrElse(
    configuration
      .getOptionalWithFileSupport[Int]("play.server.https.port")
      .orElse(configuration.getOptionalWithFileSupport[Int]("https.port"))
      .getOrElse(9998)
  )

  lazy val defaultConfig = GlobalConfig(
    perIpThrottlingQuota = 500,
    throttlingQuota = 100000,
    maxLogsSize = configuration.getOptionalWithFileSupport[Int]("app.events.maxSize").getOrElse(100),
    otoroshiId = configuration.getOptionalWithFileSupport[String]("otoroshi.instance.instanceId").getOrElse(IdGenerator.uuid),
    scripts = GlobalScripts(
      enabled = true,
      sinkRefs = Seq("cp:otoroshi.plugins.apikeys.ClientCredentialService"),
      sinkConfig = Json.obj(
        "ClientCredentialService" -> Json.obj(
          "domain"  -> "*",
          "expiration"     -> 1.hour.toMillis,
          "defaultKeyPair" -> Cert.OtoroshiJwtSigning,
          "secure"         -> true
        )
      )
    )
  )

  lazy val backOfficeGroup = ServiceGroup(
    id = backOfficeGroupId,
    name = "Otoroshi Admin Api group",
    metadata = Map.empty
  )

  lazy val backOfficeApiKey = ApiKey(
    backOfficeApiKeyClientId,
    backOfficeApiKeyClientSecret,
    "Otoroshi Backoffice ApiKey",
    Seq(ServiceGroupIdentifier(backOfficeGroupId)),
    validUntil = None
  )

  private lazy val backOfficeDescriptorHostHeader: String = s"$adminApiSubDomain.$domain"

  lazy val backOfficeDescriptor = ServiceDescriptor(
    id = backOfficeServiceId,
    groups = Seq(backOfficeGroupId),
    name = "otoroshi-admin-api",
    env = "prod",
    subdomain = adminApiExposedSubDomain,
    hosts = adminApiAdditionalExposedDomain.toSeq,
    domain = domain,
    targets = Seq(
      Target(
        host = if (adminApiProxyUseLocal) s"127.0.0.1:$port" else s"$adminApiHost:$port",
        scheme = if (adminApiProxyHttps) "https" else "http"
      )
    ),
    detectApiKeySooner = false,
    redirectToLocal = false,
    localHost = s"127.0.0.1:$port",
    forceHttps = false,
    additionalHeaders = Map(
      "Host" -> backOfficeDescriptorHostHeader
    ),
    publicPatterns = Seq("/health", "/metrics"),
    allowHttp10 = true,
    letsEncrypt = false,
    removeHeadersIn = Seq.empty,
    removeHeadersOut = Seq.empty,
    accessValidator = AccessValidatorRef(),
    missingOnlyHeadersIn = Map.empty,
    missingOnlyHeadersOut = Map.empty,
    stripPath = true,
    useAkkaHttpClient = true
  )

  lazy val otoroshiVersion     = "1.5.0-dev"
  lazy val checkForUpdates     = configuration.getOptionalWithFileSupport[Boolean]("app.checkForUpdates").getOrElse(true)

  lazy val jmxEnabled = configuration.getOptionalWithFileSupport[Boolean]("otoroshi.jmx.enabled").getOrElse(false)
  lazy val jmxPort    = configuration.getOptionalWithFileSupport[Int]("otoroshi.jmx.port").getOrElse(16000)

  if (jmxEnabled) {
    LocateRegistry.createRegistry(jmxPort)
    val mbs = ManagementFactory.getPlatformMBeanServer
    val url = new JMXServiceURL(s"service:jmx:rmi://localhost/jndi/rmi://localhost:$jmxPort/jmxrmi")
    val svr = JMXConnectorServerFactory.newJMXConnectorServer(url, null, mbs)
    svr.start()
    logger.info(s"Starting JMX remote server at 127.0.0.1:$jmxPort")
  }

  private def setupLoggers(): Unit = {
    val loggerContext = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
    val loggersAndLevel: Seq[(String, String)] = configuration
      .getOptionalWithFileSupport[Configuration]("otoroshi.loggers")
      .map { loggers =>
        loggers.entrySet.map {
          case (key, value) => (key, value.unwrapped().asInstanceOf[String])
        }.toSeq
      }
      .getOrElse(Seq.empty) ++ {
      sys.env.toSeq
        .filter {
          case (key, _) if key.toLowerCase().startsWith("otoroshi_loggers_") => true
          case _                                                             => false
        }
        .map {
          case (key, value) => (key.toLowerCase.replace("otoroshi_loggers_", "").replaceAll("_", "-"), value)
        }
    }
    loggersAndLevel.foreach {
      case (logName, level) =>
        logger.info(s"Setting logger $logName to level $level")
        val _logger = loggerContext.getLogger(logName)
        _logger.setLevel(Level.valueOf(level))
    }
  }

  timeout(300.millis).andThen {
    case _ =>
      implicit val ec = otoroshiExecutionContext // internalActorSystem.dispatcher

      setupLoggers()

      DynamicSSLEngineProvider.setCurrentEnv(this)

      clusterAgent.warnAboutHttpLeaderUrls()
      if (clusterConfig.mode == ClusterMode.Leader) {
        logger.info(s"Running Otoroshi Leader agent !")
        clusterLeaderAgent.start()
      } else if (clusterConfig.mode == ClusterMode.Worker) {
        logger.info(s"Running Otoroshi Worker agent !")
        clusterAgent.startF()
      } else {
        configuration.getOptionalWithFileSupport[Seq[String]]("otoroshi.ssl.cipherSuites").filterNot(_.isEmpty).foreach { s =>
          DynamicSSLEngineProvider.logger.warn(s"Using custom SSL cipher suites: ${s.mkString(", ")}")
        }
        configuration.getOptionalWithFileSupport[Seq[String]]("otoroshi.ssl.protocols").filterNot(_.isEmpty).foreach { p =>
          DynamicSSLEngineProvider.logger.warn(s"Using custom SSL protocols: ${p.mkString(", ")}")
        }
      }
      datastores.globalConfigDataStore
        .isOtoroshiEmpty()
        .andThen {
          case Success(true) if clusterConfig.mode == ClusterMode.Worker => {
            logger.info(s"The main datastore seems to be empty, registering default config.")
            defaultConfig.save()(ec, this)
          }
          case Success(true) if clusterConfig.mode != ClusterMode.Worker => {
            logger.info(s"The main datastore seems to be empty, registering some basic services")
            val login    = configuration.getOptionalWithFileSupport[String]("app.adminLogin").getOrElse("admin@otoroshi.io")
            val password = configuration.getOptionalWithFileSupport[String]("app.adminPassword").getOrElse(IdGenerator.token(32))
            val headers: Seq[(String, String)] = configuration
              .getOptionalWithFileSupport[Seq[String]]("app.importFromHeaders")
              .map(headers => headers.toSeq.map(h => h.split(":")).map(h => (h(0).trim, h(1).trim)))
              .getOrElse(Seq.empty[(String, String)])
            if (configuration.has("app.importFrom")) {
              configuration.getOptionalWithFileSupport[String]("app.importFrom") match {
                case Some(url) if url.startsWith("http://") || url.startsWith("https://") => {
                  logger.info(s"Importing from URL: $url")
                  _internalClient.url(url).withHttpHeaders(headers: _*).get().fast.map { resp =>
                    val json = resp.json.as[JsObject]
                    datastores.globalConfigDataStore
                      .fullImport(json)(ec, this)
                      .andThen {
                        case Success(_) => logger.info("Successful import !")
                        case Failure(e) => logger.error("Error while importing initial data !", e)
                      }(ec)
                  }
                }
                case Some(path) => {
                  logger.info(s"Importing from: $path")
                  val source = Source.fromFile(path).getLines().mkString("\n")
                  val json   = Json.parse(source).as[JsObject]
                  datastores.globalConfigDataStore
                    .fullImport(json)(ec, this)
                    .andThen {
                      case Success(_) => logger.info("Successful import !")
                      case Failure(e) => logger.error("Error while importing initial data !", e)
                    }(ec)
                }
              }
            } else {
              configuration.getOptionalWithFileSupport[play.api.Configuration]("app.initialData") match {
                case Some(obj) => {
                  val importJson = Json
                    .parse(
                      obj.underlying
                        .root()
                        .render(ConfigRenderOptions.concise())
                    )
                    .as[JsObject]
                  logger.info(s"Importing from config file")
                  datastores.globalConfigDataStore
                    .fullImport(importJson)(ec, this)
                    .andThen {
                      case Success(_) => logger.info("Successful import !")
                      case Failure(e) => logger.error("Error while importing initial data !", e)
                    }(ec)
                }
                case _ => {




                  val defaultGroup = ServiceGroup("default", "default-group", "The default service group", Map.empty)
                  val defaultGroupApiKey = ApiKey(IdGenerator.token(16),
                                                  IdGenerator.token(64),
                                                  "default-apikey",
                                                  Seq(ServiceGroupIdentifier("default")),
                                                  validUntil = None)

                  import utils.JsonImplicits._

                  val admin = SimpleOtoroshiAdmin(
                    username = login,
                    password = BCrypt.hashpw(password, BCrypt.gensalt()),
                    label = "Otoroshi Admin",
                    createdAt = DateTime.now(),
                    typ = OtoroshiAdminType.SimpleAdmin,
                    metadata = Map.empty,
                    rights = UserRights.varargs(UserRight(TenantAccess("*"), Seq(TeamAccess("*")))),
                    location = EntityLocation()
                  )

                  val defaultTenant = Tenant(
                    id = TenantId("default"), 
                    name = "Default organization", 
                    description = "The default organization", 
                    metadata = Map.empty[String, String]
                  )

                  val defaultTeam = Team(
                    id = TeamId("default"), 
                    tenant = TenantId("default"), 
                    name = "Default Team", 
                    description = "The default Team of the default organization",
                    metadata = Map.empty[String, String]
                  )

                  val baseExport = OtoroshiExport(
                    config = defaultConfig,
                    descs = Seq(backOfficeDescriptor),
                    apikeys = Seq(backOfficeApiKey, defaultGroupApiKey),
                    groups = Seq(backOfficeGroup, defaultGroup),
                    simpleAdmins = Seq(admin),
                    teams = Seq(defaultTeam),
                    tenants = Seq(defaultTenant)
                  )

                  val initialCustomization = configuration
                    .getOptionalWithFileSupport[String]("app.initialCustomization")
                    .map(Json.parse).map(_.asObject)
                    .orElse(
                      configuration
                        .getOptionalWithFileSupport[play.api.Configuration]("app.initialCustomization")
                        .map(v => Json.parse(v.underlying.root().render(ConfigRenderOptions.concise())).asObject)
                    )
                    .getOrElse(Json.obj())
                  
                  val finalConfig = baseExport.customizeWith(initialCustomization)

                  logger.info(
                    s"You can log into the Otoroshi admin console with the following credentials: $login / $password"
                  )

                  datastores.globalConfigDataStore.fullImport(finalConfig.json)(ec, this)
                }
              }
            }
          }
        }
        .map { _ =>
          datastores.serviceDescriptorDataStore.findById(backOfficeServiceId)(ec, this).map {
            case Some(s) if !s.publicPatterns.contains("/health") =>
              logger.info("Updating BackOffice service to handle health check ...")
              s.copy(publicPatterns = s.publicPatterns :+ "/health").save()(ec, this)
            case Some(s) if !s.publicPatterns.contains("/metrics") =>
              logger.info("Updating BackOffice service to handle metrics ...")
              s.copy(publicPatterns = s.publicPatterns :+ "/metrics").save()(ec, this)
            case _ =>
          }
        }

      {
        datastores.tenantDataStore.findById("default")(ec, this).map {
          case None => datastores.tenantDataStore.set(Tenant(
            id = TenantId("default"),
            name = "Default organization",
            description = "Default organization created for any otoroshi instance",
            metadata = Map.empty
          ))(ec, this)
          case Some(_) =>
        }
        datastores.teamDataStore.findById("default")(ec, this).map {
          case None => datastores.teamDataStore.set(Team(
            id = TeamId("default"),
            tenant = TenantId("default"),
            name = "Default team",
            description = "Default team created for any otoroshi instance",
            metadata = Map.empty
          ))(ec, this)
          case Some(_) =>
        }
      }
      ()
  }(otoroshiExecutionContext)

  timeout(1000.millis).andThen {
    case _ =>
      jobManager.start()
      otoroshiEventsActor! StartExporters
  }(otoroshiExecutionContext)

  timeout(5000.millis).andThen {
    case _ if clusterConfig.mode != ClusterMode.Worker => {
      implicit val ec = otoroshiExecutionContext
      implicit val ev = this
      for {
        _ <- datastores.globalConfigDataStore.migrate()
      } yield ()
    }
  }(otoroshiExecutionContext)

  /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  lazy val sessionDomain = configuration.getOptionalWithFileSupport[String]("play.http.session.domain").get
  lazy val playSecret    = configuration.getOptionalWithFileSupport[String]("play.http.secret.key").get

  def sign(message: String): String =
    scala.util.Try {
      val mac = javax.crypto.Mac.getInstance("HmacSHA256")
      mac.init(new javax.crypto.spec.SecretKeySpec(playSecret.getBytes("utf-8"), "HmacSHA256"))
      org.apache.commons.codec.binary.Hex.encodeHexString(mac.doFinal(message.getBytes("utf-8")))
    } match {
      case scala.util.Success(s) => s
      case scala.util.Failure(e) => {
        logger.error(s"Error while signing: ${message}", e)
        throw e
      }
    }

  def extractPrivateSessionId(cookie: play.api.mvc.Cookie): Option[String] = {
    cookie.value.split("::").toList match {
      case signature :: value :: Nil if sign(value) == signature => Some(value)
      case _                                                     => None
    }
  }

  def extractPrivateSessionIdFromString(value: String): Option[String] = {
    value.split("::").toList match {
      case signature :: value :: Nil if sign(value) == signature => Some(value)
      case _                                                     => None
    }
  }

  def signPrivateSessionId(id: String): String = {
    val signature = sign(id)
    s"$signature::$id"
  }

  def createPrivateSessionCookies(host: String,
                                  id: String,
                                  desc: ServiceDescriptor,
                                  authConfig: AuthModuleConfig): Seq[play.api.mvc.Cookie] = {
    createPrivateSessionCookiesWithSuffix(host, id, authConfig.cookieSuffix(desc), authConfig.sessionMaxAge, authConfig.sessionCookieValues)
  }

  def createPrivateSessionCookiesWithSuffix(host: String,
                                            id: String,
                                            suffix: String,
                                            sessionMaxAge: Int,
                                            sessionCookieValues: SessionCookieValues): Seq[play.api.mvc.Cookie] = {
    if (host.endsWith(sessionDomain)) {
      Seq(
        play.api.mvc.Cookie(
          name = "oto-papps-" + suffix,
          value = signPrivateSessionId(id),
          maxAge = Some(sessionMaxAge),
          path = "/",
          domain = Some(sessionDomain),
          httpOnly = sessionCookieValues.httpOnly,
          secure = sessionCookieValues.secure
        )
      )
    } else {
      Seq(
        play.api.mvc.Cookie(
          name = "oto-papps-" + suffix,
          value = signPrivateSessionId(id),
          maxAge = Some(sessionMaxAge),
          path = "/",
          domain = Some(host),
          httpOnly = sessionCookieValues.httpOnly,
          secure = sessionCookieValues.secure
        ),
        play.api.mvc.Cookie(
          name = "oto-papps-" + suffix,
          value = signPrivateSessionId(id),
          maxAge = Some(sessionMaxAge),
          path = "/",
          domain = Some(sessionDomain),
          httpOnly = sessionCookieValues.httpOnly,
          secure = sessionCookieValues.secure
        )
      )
    }
  }

  def removePrivateSessionCookies(host: String,
                                  desc: ServiceDescriptor,
                                  authConfig: AuthModuleConfig): Seq[play.api.mvc.DiscardingCookie] = {
    removePrivateSessionCookiesWithSuffix(host, authConfig.cookieSuffix(desc))
  }

  def removePrivateSessionCookiesWithSuffix(host: String, suffix: String): Seq[play.api.mvc.DiscardingCookie] =
    Seq(
      play.api.mvc.DiscardingCookie(
        name = "oto-papps-" + suffix,
        path = "/",
        domain = Some(host)
      ),
      play.api.mvc.DiscardingCookie(
        name = "oto-papps-" + suffix,
        path = "/",
        domain = Some(sessionDomain)
      )
    )
}
